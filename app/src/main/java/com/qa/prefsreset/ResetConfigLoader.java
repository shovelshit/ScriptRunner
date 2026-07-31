package com.qa.prefsreset;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责从外部配置文件读取本次开机需要执行的重置任务列表。
 *
 * 目录结构：
 * /sdcard/qa_prefs_reset/
 *   scripts/     本地脚本，每个文件是一套独立命名的脚本（ScriptSet）
 *     default.json / xxx.json.b64（base64 密文，推荐）
 *   remote/      从远端拉取下来的脚本，落盘位置与 scripts/ 同构
 *
 * 可直接用 adb push 覆盖这些文件切换测试方案，也可在 App 内配置 HTTP(S)
 * 地址由 App 自动拉取并落盘到 remote/ 目录。
 *
 * 【隐私保护：.json.b64 密文格式】
 * 脚本内容通常包含真实业务包名等敏感信息，明文落盘容易被 grep/cat 搜出。
 * 因此支持将整份 JSON 文本做一次 base64 编码后以 ".json.b64" 后缀落盘：
 * 加载时自动识别并解码还原成 JSON，对上层调用方透明；".json" 明文仍兼容，
 * 两种后缀可在同一目录混用。可用 {@link #encodeToBase64(String)} 或
 * 命令行 `base64 -w0 xxx.json > xxx.json.b64` 生成密文。
 *
 * 单个脚本文件格式示例（任务模型统一为「一组以 root 身份顺序执行的 shell
 * 命令」，可以是 mkdir/rm/chmod/写文件等任意命令）：
 * {
 *   "id": "login_reset",
 *   "name": "登录态重置",
 *   "description": "清空登录 token 和首启引导标记",
 *   "runOnBoot": true,
 *   "whitelist": ["com.example.myapp"],
 *   "tasks": [
 *     {
 *       "packageName": "com.example.myapp",
 *       "summary": "清空缓存目录并重建",
 *       "commands": [
 *         "rm -rf /data/data/com.example.myapp/cache",
 *         "mkdir -p /data/data/com.example.myapp/cache"
 *       ],
 *       "restartApp": true
 *     }
 *   ]
 * }
 *
 * "tasks" 数组字段说明：
 * - "packageName"：可选，填写时按该包名做白名单校验，不填则不受限制；
 * - "commands"：必填，以 root 身份顺序执行的 shell 命令数组，任意一条失败即中断；
 * - "summary"：可选，仅用于列表 UI / 日志展示；
 * - "restartApp"：可选（默认 false），全部命令成功后是否强制停止对应进程。
 *
 * "runOnBoot"（可选，默认 true）控制该脚本是否参与「开机自动执行」，关闭后
 * 只能在 App 内手动触发；也可以直接在列表页用开关切换，持久化保存在本地
 * （见 {@link ScriptBootPrefs}），优先级高于 JSON 文件配置。
 *
 * 一个文件里也可以通过顶层 "scripts": [...] 数组一次声明多套脚本。
 */
public final class ResetConfigLoader {

    private static final String TAG = "ResetConfigLoader";

    public static final String CONFIG_DIR_NAME = "qa_prefs_reset";
    public static final String SCRIPTS_DIR_NAME = "scripts";
    public static final String REMOTE_SCRIPTS_DIR_NAME = "remote";
    public static final String SOURCE_LOCAL_SCRIPT = "本地脚本";
    public static final String SOURCE_REMOTE = "远端拉取";
    /** 明文脚本文件后缀 */
    public static final String PLAIN_EXT = ".json";
    /** base64 密文脚本文件后缀：内容为整份 JSON 文本的 base64 编码结果 */
    public static final String BASE64_EXT = ".json.b64";

    private ResetConfigLoader() {
    }

    public static File getConfigDir() {
        File externalStorage = Environment.getExternalStorageDirectory();
        return new File(externalStorage, CONFIG_DIR_NAME);
    }

    public static File getScriptsDir() {
        return new File(getConfigDir(), SCRIPTS_DIR_NAME);
    }

    public static File getRemoteScriptsDir() {
        return new File(getConfigDir(), REMOTE_SCRIPTS_DIR_NAME);
    }

    /**
     * 汇总加载所有可用的脚本集，来源包括 scripts/ 本地目录和 remote/ 远端拉取目录；
     * 同一 id 后出现的会覆盖先出现的（remote 优先级最高）。
     *
     * 注意：这个重载不感知用户手动切换的「随开机执行」覆盖设置，
     * 大多数场景请优先使用 {@link #loadAllScriptSets(Context)}。
     */
    public static List<ScriptSet> loadAllScriptSets() {
        java.util.LinkedHashMap<String, ScriptSet> merged = new java.util.LinkedHashMap<>();

        for (ScriptSet s : loadScriptSetsFromDir(getScriptsDir(), SOURCE_LOCAL_SCRIPT)) {
            merged.put(s.id, s);
        }
        for (ScriptSet s : loadScriptSetsFromDir(getRemoteScriptsDir(), SOURCE_REMOTE)) {
            merged.put(s.id, s);
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * 与 {@link #loadAllScriptSets()} 相同，但会用 {@link ScriptBootPrefs} 中保存的
     * 本地手动覆盖值替换每套脚本的 runOnBoot 字段，避免列表里手动切换过的
     * 开关因 JSON 配置没变而被无视。
     */
    public static List<ScriptSet> loadAllScriptSets(Context context) {
        List<ScriptSet> scriptSets = loadAllScriptSets();
        if (context == null) {
            return scriptSets;
        }
        List<ScriptSet> result = new ArrayList<>(scriptSets.size());
        for (ScriptSet s : scriptSets) {
            Boolean override = ScriptBootPrefs.getOverride(context, s.id);
            result.add(override == null ? s : s.withRunOnBoot(override));
        }
        return result;
    }

    private static List<ScriptSet> loadScriptSetsFromDir(File dir, String source) {
        List<ScriptSet> result = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles((FilenameFilter) (d, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(PLAIN_EXT) || lower.endsWith(BASE64_EXT);
        });
        if (files == null) {
            return result;
        }
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) {
            try {
                // 跳过空文件（如远端拉取失败后残留的 0 字节文件），避免无意义的 JSON 解析异常
                if (file.length() == 0) {
                    Log.w(TAG, "skip empty script file: " + file.getAbsolutePath());
                    continue;
                }
                boolean isBase64 = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(BASE64_EXT);
                String raw = readFileToString(file);
                String json = isBase64 ? decodeFromBase64(raw) : raw;
                String fallbackName = isBase64
                        ? file.getName().substring(0, file.getName().length() - BASE64_EXT.length()) + PLAIN_EXT
                        : file.getName();
                result.addAll(parseScriptSetsFromJson(json, source, fallbackName));
            } catch (IOException | JSONException | IllegalArgumentException e) {
                Log.e(TAG, "parse script file failed: " + file.getAbsolutePath(), e);
            }
        }
        return result;
    }

    /**
     * 解析一段 JSON 文本为脚本集列表：
     * - 若顶层含 "scripts" 数组，按数组逐个解析；
     * - 否则把整个 JSON 对象当作单个脚本解析（此时 id 缺省时用文件名去掉后缀兜底）。
     */
    public static List<ScriptSet> parseScriptSetsFromJson(String json, String source, String fallbackFileName)
            throws JSONException {
        List<ScriptSet> result = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray scriptsArray = root.optJSONArray("scripts");
        if (scriptsArray != null) {
            for (int i = 0; i < scriptsArray.length(); i++) {
                JSONObject item = scriptsArray.getJSONObject(i);
                result.add(parseSingleScriptSet(item, source, fallbackFileName + "#" + i));
            }
        } else {
            result.add(parseSingleScriptSet(root, source, fallbackFileName));
        }
        return result;
    }

    private static ScriptSet parseSingleScriptSet(JSONObject obj, String source, String fallbackId)
            throws JSONException {
        String defaultId = fallbackId.replaceAll("(?i)\\.json$", "");
        String id = obj.optString("id", defaultId);
        String name = obj.optString("name", id);
        String description = obj.optString("description", "");
        // 未配置该字段时默认为 true，保持「开机自动执行全部已加载脚本」的原有行为
        boolean runOnBoot = obj.optBoolean("runOnBoot", ScriptSet.DEFAULT_RUN_ON_BOOT);

        List<String> whitelist = new ArrayList<>();
        JSONArray whitelistArray = obj.optJSONArray("whitelist");
        if (whitelistArray != null) {
            for (int i = 0; i < whitelistArray.length(); i++) {
                String pkg = whitelistArray.optString(i, "").trim();
                if (!pkg.isEmpty()) {
                    whitelist.add(pkg);
                }
            }
        }

        List<ResetTaskConfig> tasks = parseTaskArray(obj.optJSONArray("tasks"));
        return new ScriptSet(id, name, description, whitelist, tasks, source, runOnBoot);
    }

    /**
     * 解析任务数组：每一项必须带 "commands" 数组（以 root 身份顺序执行的 shell 命令，
     * 支持任意命令 mkdir/rm/chmod/…），"packageName" 可选（填写则按该包名做白名单校验，
     * 不填则不受白名单限制）：
     *    {
     *      "packageName": "com.example.myapp",   // 可选：填写则按该包名做白名单校验
     *      "commands": [
     *        "mkdir -p /sdcard/qa_test_dir",
     *        "rm -rf /data/data/com.example.myapp/cache/*"
     *      ],
     *      "restartApp": false                   // 可选：全部命令成功后是否强制停止该应用进程
     *    }
     */
    private static List<ResetTaskConfig> parseTaskArray(JSONArray taskArray) throws JSONException {
        List<ResetTaskConfig> tasks = new ArrayList<>();
        if (taskArray == null) {
            return tasks;
        }
        for (int i = 0; i < taskArray.length(); i++) {
            JSONObject item = taskArray.getJSONObject(i);
            String packageName = item.optString("packageName", "");
            boolean restartApp = item.optBoolean("restartApp", false);

            JSONArray commandsArray = item.optJSONArray("commands");
            List<String> commands = new ArrayList<>();
            if (commandsArray != null) {
                for (int j = 0; j < commandsArray.length(); j++) {
                    String cmd = commandsArray.optString(j, "").trim();
                    if (!cmd.isEmpty()) {
                        commands.add(cmd);
                    }
                }
            }
            if (commands.isEmpty()) {
                Log.w(TAG, "skip invalid task (empty commands) at index " + i);
                continue;
            }
            String summary = item.optString("summary", "");
            tasks.add(new ResetTaskConfig(packageName, commands, restartApp, summary));
        }
        return tasks;
    }

    private static String readFileToString(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = is.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** 将 JSON 文本编码为 base64 密文字符串（用于生成 .json.b64 文件内容） */
    public static String encodeToBase64(String jsonText) {
        return Base64.encodeToString(jsonText.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    /** 将 .json.b64 文件里的 base64 密文还原为 JSON 明文文本 */
    public static String decodeFromBase64(String base64Text) {
        byte[] decoded = Base64.decode(base64Text.trim(), Base64.DEFAULT);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    /**
     * 判断给定文件名是否是本加载器可识别的脚本文件（.json 或 .json.b64），
     * 供保存远端拉取结果等场景复用文件名规范判断逻辑。
     */
    public static boolean isRecognizedScriptFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(PLAIN_EXT) || lower.endsWith(BASE64_EXT);
    }

    /**
     * 将 JSON 文本以 base64 密文形式写入目标文件（自动补齐 .json.b64 后缀）。
     * 供 App 内「导入为密文脚本」等场景使用；也可以直接用命令行 base64 工具手动生成。
     */
    public static File writeAsBase64Script(File dir, String fileNameWithoutExt, String jsonText) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("创建目录失败: " + dir.getAbsolutePath());
        }
        File target = new File(dir, fileNameWithoutExt + BASE64_EXT);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(encodeToBase64(jsonText).getBytes(StandardCharsets.UTF_8));
        }
        return target;
    }

    /** {@link #deleteScriptById(String)} 的删除结果 */
    public static final class DeleteResult {
        public final boolean success;
        public final String message;

        private DeleteResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static DeleteResult ok(String message) {
            return new DeleteResult(true, message);
        }

        static DeleteResult fail(String message) {
            return new DeleteResult(false, message);
        }
    }

    /**
     * 根据脚本 id 在 scripts/ 和 remote/ 两个目录下查找并删除对应的脚本
     * （支持 .json / .json.b64）。
     *
     * 若命中文件是单个脚本对象，直接删除整份文件；若命中文件通过顶层
     * "scripts": [...] 数组声明了多套脚本，则只移除目标 id 对应的一项，
     * 其余脚本原样写回原文件（保留原有明文/密文格式），数组为空时才删除整份文件。
     */
    public static DeleteResult deleteScriptById(String scriptId) {
        if (scriptId == null || scriptId.isEmpty()) {
            return DeleteResult.fail("脚本 id 不能为空");
        }
        File[] candidateDirs = {getScriptsDir(), getRemoteScriptsDir()};
        for (File dir : candidateDirs) {
            if (dir == null || !dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((FilenameFilter) (d, name) -> {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                return lower.endsWith(PLAIN_EXT) || lower.endsWith(BASE64_EXT);
            });
            if (files == null) {
                continue;
            }
            for (File file : files) {
                boolean isBase64 = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(BASE64_EXT);
                String json;
                JSONObject root;
                try {
                    String raw = readFileToString(file);
                    json = isBase64 ? decodeFromBase64(raw) : raw;
                    root = new JSONObject(json);
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    Log.e(TAG, "read script file failed while deleting: " + file.getAbsolutePath(), e);
                    continue;
                }

                JSONArray scriptsArray = root.optJSONArray("scripts");
                if (scriptsArray == null) {
                    // 单脚本文件：id 不匹配则跳过，匹配则直接删除整份文件
                    String fallbackId = file.getName().replaceAll("(?i)(\\.json\\.b64|\\.json)$", "");
                    String idInFile = root.optString("id", fallbackId);
                    if (!scriptId.equals(idInFile)) {
                        continue;
                    }
                    return deleteFile(file);
                }

                // 多脚本数组文件：找到目标 id 在数组中的下标
                int matchedIndex = -1;
                for (int i = 0; i < scriptsArray.length(); i++) {
                    JSONObject item = scriptsArray.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String fallbackId = file.getName().replaceAll("(?i)(\\.json\\.b64|\\.json)$", "") + "#" + i;
                    String idInArray = item.optString("id", fallbackId);
                    if (scriptId.equals(idInArray)) {
                        matchedIndex = i;
                        break;
                    }
                }
                if (matchedIndex < 0) {
                    continue;
                }
                if (scriptsArray.length() <= 1) {
                    // 数组里只有这一个脚本，等价于单脚本文件，直接删除整份文件
                    return deleteFile(file);
                }

                // 数组里还有其他脚本：移除目标项，把剩余脚本写回原文件（保持原有明文/密文格式）
                int remainingCount;
                try {
                    JSONArray remaining = new JSONArray();
                    for (int i = 0; i < scriptsArray.length(); i++) {
                        if (i != matchedIndex) {
                            remaining.put(scriptsArray.optJSONObject(i));
                        }
                    }
                    remainingCount = remaining.length();
                    root.put("scripts", remaining);
                    writeScriptFile(file, root.toString(2), isBase64);
                } catch (JSONException | IOException e) {
                    String msg = "写回剩余脚本失败: " + e.getMessage();
                    Log.e(TAG, msg, e);
                    return DeleteResult.fail(msg);
                }
                return DeleteResult.ok("已从 " + file.getName() + " 中删除脚本 [" + scriptId
                        + "]，文件内其余 " + remainingCount + " 套脚本已保留");
            }
        }
        return DeleteResult.fail("未找到 id=" + scriptId + " 对应的脚本文件");
    }

    private static DeleteResult deleteFile(File file) {
        if (file.delete()) {
            return DeleteResult.ok("已删除脚本文件: " + file.getAbsolutePath());
        }
        return DeleteResult.fail("删除文件失败（可能权限不足）: " + file.getAbsolutePath());
    }

    /** 将 JSON 文本写回目标文件：isBase64 为 true 时先编码为 base64 密文再写入，否则直接写明文 */
    private static void writeScriptFile(File file, String jsonText, boolean isBase64) throws IOException {
        String content = isBase64 ? encodeToBase64(jsonText) : jsonText;
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
