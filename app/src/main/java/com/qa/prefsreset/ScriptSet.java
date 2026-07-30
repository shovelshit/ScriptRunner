package com.qa.prefsreset;

import java.util.Collections;
import java.util.List;

/**
 * 一套「脚本」：包含一组重置任务 + 对应的包名白名单。
 *
 * 之所以引入这一层，是为了支持同一台测试机上维护多套互不干扰的重置方案，
 * 例如「登录态重置」「首启引导重置」等，可在 App 内用列表形式挑选其中一套
 * 单独执行，而不必每次都重写整个脚本文件。
 */
public final class ScriptSet {

    /** 未在 JSON / 本地覆盖中显式配置时的默认值：保持旧版本「开机即执行全部脚本」的行为 */
    public static final boolean DEFAULT_RUN_ON_BOOT = true;

    /** 脚本唯一标识，用于日志定位和本地存储文件命名 */
    public final String id;

    /** 展示用名称 */
    public final String name;

    /** 展示用描述，可为空 */
    public final String description;

    /** 该脚本允许操作的包名白名单 */
    public final List<String> whitelist;

    /** 该脚本包含的具体重置任务 */
    public final List<ResetTaskConfig> tasks;

    /** 数据来源：本地内置 / 远端拉取，仅用于列表展示 */
    public final String source;

    /**
     * 是否随开机自动执行。
     * 每套脚本可以独立配置：默认为 true（不配置时保持原有行为，向后兼容旧配置文件）。
     * 关闭后该脚本只能在 App 内通过「执行」按钮手动单独触发，不会在开机时被自动执行；
     * 手动触发（单个执行 / 全部执行）不受此开关影响，任一脚本随时都可以手动执行。
     */
    public final boolean runOnBoot;

    public ScriptSet(String id, String name, String description,
                      List<String> whitelist, List<ResetTaskConfig> tasks, String source) {
        this(id, name, description, whitelist, tasks, source, DEFAULT_RUN_ON_BOOT);
    }

    public ScriptSet(String id, String name, String description,
                      List<String> whitelist, List<ResetTaskConfig> tasks, String source,
                      boolean runOnBoot) {
        this.id = id;
        this.name = name;
        this.description = description == null ? "" : description;
        this.whitelist = whitelist == null ? Collections.emptyList() : whitelist;
        this.tasks = tasks == null ? Collections.emptyList() : tasks;
        this.source = source == null ? "" : source;
        this.runOnBoot = runOnBoot;
    }

    /**
     * 该脚本中，实际会被执行的任务数：
     * - 配置了包名的任务，必须命中白名单才算可执行；
     * - 未配置包名的通用命令任务（SHELL_COMMAND 且 packageName 为空）不受白名单限制，
     *   始终计入可执行任务数。
     */
    public int countRunnableTasks() {
        int count = 0;
        for (ResetTaskConfig task : tasks) {
            if (!task.requiresWhitelistCheck() || whitelist.contains(task.packageName)) {
                count++;
            }
        }
        return count;
    }

    /** 返回一份 runOnBoot 字段被覆盖后的副本，其余字段保持不变（用于 UI 切换开关后持久化） */
    public ScriptSet withRunOnBoot(boolean newRunOnBoot) {
        return new ScriptSet(id, name, description, whitelist, tasks, source, newRunOnBoot);
    }

    @Override
    public String toString() {
        return "ScriptSet{id='" + id + "', name='" + name + "', tasks=" + tasks.size()
                + ", whitelist=" + whitelist.size() + ", source='" + source
                + "', runOnBoot=" + runOnBoot + "}";
    }
}
