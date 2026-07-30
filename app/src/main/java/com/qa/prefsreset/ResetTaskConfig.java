package com.qa.prefsreset;

import java.util.Collections;
import java.util.List;

/**
 * 单条重置任务的配置描述：本质就是一组以 root 身份顺序执行的 shell 命令
 * （mkdir/rm/chmod/写文件/…），不区分“写 prefs 文件”和“通用命令”两种类型，
 * 任意 shell 命令都可以直接写，不受限于固定语义。
 */
public final class ResetTaskConfig {

    /**
     * 目标应用包名，可选：填写时会按该包名做白名单校验，并在日志/列表里
     * 作为展示信息；留空则不做白名单限制，适用于操作与具体应用无关的
     * 公共路径，请谨慎使用。
     */
    public final String packageName;

    /**
     * 依次执行的 shell 命令列表，以 root 身份在同一个 shell 会话中顺序执行，
     * 任意一条失败会中断后续命令。
     */
    public final List<String> commands;

    /** 写完成后是否强制停止目标应用进程（可选，仅当配置了 packageName 时才有意义） */
    public final boolean restartApp;

    /** 展示用备注：这条任务具体在做什么，仅用于列表 UI / 日志展示，不影响执行 */
    public final String summary;

    public ResetTaskConfig(String packageName, List<String> commands, boolean restartApp, String summary) {
        this.packageName = packageName == null ? "" : packageName;
        this.commands = commands == null ? Collections.emptyList() : commands;
        this.restartApp = restartApp;
        this.summary = summary == null ? "" : summary;
    }

    public ResetTaskConfig(String packageName, List<String> commands) {
        this(packageName, commands, false, "");
    }

    /** 该任务是否需要按包名做白名单校验（未指定包名时无需校验） */
    public boolean requiresWhitelistCheck() {
        return !packageName.isEmpty();
    }

    @Override
    public String toString() {
        return "ResetTaskConfig{"
                + "packageName='" + packageName + '\''
                + ", commands=" + commands
                + ", restartApp=" + restartApp
                + '}';
    }
}
