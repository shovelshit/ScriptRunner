package com.qa.prefsreset;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 持久化保存「用户在 App 内手动调整过的每套脚本是否随开机执行」的开关状态。
 *
 * 背景：脚本本身的 runOnBoot 默认值来自 JSON 配置文件（本地/远端），但该文件
 * 可能被远端配置覆盖更新、也可能是只读示例，因此这里用一份独立的
 * SharedPreferences 按 scriptId 记录「本地覆盖值」，加载配置时（见
 * {@link ResetConfigLoader}）会优先使用该覆盖值，未覆盖过的脚本则维持
 * JSON 里配置的（或默认的）runOnBoot 值，且这个选择能跨重启保留。
 */
public final class ScriptBootPrefs {

    private static final String PREFS_NAME = "qa_prefs_reset_boot_prefs";
    private static final String KEY_PREFIX = "run_on_boot_";

    private ScriptBootPrefs() {
    }

    /**
     * 查询某个脚本 id 是否存在本地手动覆盖的 runOnBoot 值。
     * @return 若用户手动设置过，返回 true/false；若从未设置过，返回 null，调用方应回退到 JSON 中的值。
     */
    public static Boolean getOverride(Context context, String scriptId) {
        SharedPreferences prefs = prefs(context);
        String key = KEY_PREFIX + scriptId;
        if (!prefs.contains(key)) {
            return null;
        }
        return prefs.getBoolean(key, ScriptSet.DEFAULT_RUN_ON_BOOT);
    }

    /** 记住用户手动切换的开关状态，跨重启持久化 */
    public static void setOverride(Context context, String scriptId, boolean runOnBoot) {
        prefs(context).edit().putBoolean(KEY_PREFIX + scriptId, runOnBoot).apply();
    }

    /** 清除某个脚本的本地覆盖，恢复跟随 JSON 配置文件的值 */
    public static void clearOverride(Context context, String scriptId) {
        prefs(context).edit().remove(KEY_PREFIX + scriptId).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
