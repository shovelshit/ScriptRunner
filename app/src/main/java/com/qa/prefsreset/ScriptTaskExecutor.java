package com.qa.prefsreset;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行重置任务的核心逻辑。
 *
 * 任务模型已统一为「一组以 root 身份顺序执行的 shell 命令」（见 {@link ResetTaskConfig}），
 * 可以直接写任意命令（mkdir/rm/chmod/…），不受限于固定语义。
 *
 * 整体流程：
 * 1. 校验目标包名是否在白名单内（未配置包名的任务不受白名单限制）；
 * 2. 把 commands 列表交给 {@link RootShellExecutor} 以 root 身份顺序执行；
 * 3. 可选：执行完成后强制停止目标应用进程，让它下次启动时读取到最新状态。
 */
public final class ScriptTaskExecutor {

    private static final String TAG = "ScriptTaskExecutor";
    private static final long SHELL_TIMEOUT_SECONDS = 20;

    /**
     * 匹配 shell 重定向目标文件路径（"> /path" 或 ">> /path"）。
     * 用于执行完成后回读写入的文件内容并记录日志，方便核对。
     * 仅匹配绝对路径（以 / 开头），排除管道、分号等后续符号。
     */
    private static final Pattern REDIRECT_FILE_PATTERN =
            Pattern.compile("(?:>>?)\\s*(/[^\\s|;&]+)");

    private final Context appContext;

    public ScriptTaskExecutor(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static final class TaskResult {
        public final ResetTaskConfig config;
        public final boolean success;
        public final String message;

        TaskResult(ResetTaskConfig config, boolean success, String message) {
            this.config = config;
            this.success = success;
            this.message = message;
        }
    }

    /**
     * 依次执行给定任务列表，白名单外的包名会被跳过并记录日志。
     */
    public List<TaskResult> executeAll(List<ResetTaskConfig> tasks, List<String> whitelist) {
        List<TaskResult> results = new java.util.ArrayList<>();
        for (ResetTaskConfig task : tasks) {
            // 未配置包名的任务视为与具体应用无关的通用命令，不做白名单限制；
            // 一旦配置了包名，必须在白名单内才会执行。
            if (task.requiresWhitelistCheck() && !whitelist.contains(task.packageName)) {
                String msg = "包名不在白名单内，已跳过: " + task.packageName;
                ResetLogRepository.log(msg);
                results.add(new TaskResult(task, false, msg));
                continue;
            }
            results.add(executeOne(task));
        }
        return results;
    }

    /**
     * 执行指定的一套脚本（ScriptSet），语义等同于
     * {@link #executeAll(List, List)}，只是从 ScriptSet 里取任务与白名单。
     */
    public List<TaskResult> executeScriptSet(ScriptSet scriptSet) {
        ResetLogRepository.log("开始执行脚本: " + scriptSet.name + " (id=" + scriptSet.id + ")");
        return executeAll(scriptSet.tasks, scriptSet.whitelist);
    }

    /**
     * 执行单条任务：以 root 身份顺序执行 commands 列表，任意一条失败即中断并返回失败结果；
     * 全部成功后，若配置了 restartApp 且填写了包名，则强制停止该应用进程。
     */
    public TaskResult executeOne(ResetTaskConfig task) {
        ResetLogRepository.log("开始执行重置任务: " + task);

        if (task.commands.isEmpty()) {
            String msg = "commands 为空，已跳过: " + (task.packageName.isEmpty() ? "(无包名)" : task.packageName);
            ResetLogRepository.log(msg);
            return new TaskResult(task, false, msg);
        }

        String[] commands = task.commands.toArray(new String[0]);
        RootShellExecutor.ShellResult shellResult =
                RootShellExecutor.execCommands(commands, SHELL_TIMEOUT_SECONDS);

        if (!shellResult.isSuccess()) {
            String msg = "root shell 执行失败, exitCode=" + shellResult.exitCode
                    + ", stderr=" + shellResult.stderr;
            ResetLogRepository.log(msg);
            return new TaskResult(task, false, msg);
        }

        // 可选：重启目标应用进程，使其读取到最新写入的状态
        if (task.restartApp && !task.packageName.isEmpty()) {
            RootShellExecutor.ShellResult stopResult = RootShellExecutor.execCommands(
                    new String[]{"am force-stop " + task.packageName}, SHELL_TIMEOUT_SECONDS);
            if (!stopResult.isSuccess()) {
                Log.w(TAG, "force-stop failed for " + task.packageName + ": " + stopResult.stderr);
            }
        }

        String msg = "执行成功"
                + (task.packageName.isEmpty() ? "" : ("（" + task.packageName + "）"))
                + (task.summary.isEmpty() ? "" : ("：" + task.summary))
                + "，共 " + task.commands.size() + " 条命令"
                + (shellResult.stdout.trim().isEmpty() ? "" : "，输出: " + shellResult.stdout.trim());
        ResetLogRepository.log(msg);
        // 回读写入的文件内容并记录日志，方便后续核对
        logWrittenFileContents(task);
        return new TaskResult(task, true, msg);
    }

    /**
     * 解析 commands 中所有重定向写入的文件路径（"> /path" 或 ">> /path"），
     * 执行成功后用 root 权限读取这些文件的最终内容并写入执行日志。
     *
     * 这样可以记录每次实际写入文件的完整内容（如 vin_cache_prefs.xml），
     * 方便后续核对脚本执行结果是否符合预期。
     */
    private void logWrittenFileContents(ResetTaskConfig task) {
        for (String cmd : task.commands) {
            Matcher matcher = REDIRECT_FILE_PATTERN.matcher(cmd);
            while (matcher.find()) {
                String filePath = matcher.group(1);
                // 跳过 /dev/ 下的设备文件（如 2>/dev/null 会被误匹配为写入目标）
                if (filePath.startsWith("/dev/")) {
                    continue;
                }
                // 用单引号包裹路径，防止路径中的特殊字符干扰 shell 解析
                RootShellExecutor.ShellResult catResult = RootShellExecutor.execCommands(
                        new String[]{"cat '" + filePath + "'"}, SHELL_TIMEOUT_SECONDS);
                if (catResult.isSuccess()) {
                    String content = catResult.stdout.trim();
                    if (content.isEmpty()) {
                        ResetLogRepository.log("写入文件内容 [" + filePath + "]: (空文件)");
                    } else {
                        ResetLogRepository.log("写入文件内容 [" + filePath + "]:\n" + content);
                    }
                } else {
                    Log.w(TAG, "读取写入文件内容失败: " + filePath + " - " + catResult.stderr);
                }
            }
        }
    }
}
