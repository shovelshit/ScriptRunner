package com.qa.prefsreset;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 通过 Settings.Global.BOOT_COUNT 检测设备是否重启过，用于在 App 启动时补偿执行「随开机执行」的脚本。
 *
 * 背景：LDPlayer 等模拟器不向非系统应用发送 BOOT_COMPLETED 广播，导致 BootReceiver
 * 无法被触发。本工具通过比较 boot_count 的变化来判断设备是否重启过，如果重启过且尚未执行过，
 * 就让 MainActivity 在启动时自动拉起 ResetService 作为补偿。
 *
 * 为什么不用 /proc/sys/kernel/boot_id：
 *   boot_id 依赖内核编译选项 CONFIG_BOOT_ID，LDPlayer 等模拟器的内核通常未启用该选项，
 *   /proc/sys/kernel/boot_id 文件不存在，读取会失败。改用 Settings.Global.BOOT_COUNT：
 *   - Android 7.0 (API 24) 起系统原生支持；
 *   - 每次设备开机由系统自动递增，无需 App 写入；
 *   - 所有应用均可读取，无需特殊权限；
 *   - 不依赖内核配置，兼容性更好。
 */
public final class BootIdTracker {

    private static final String TAG = "BootIdTracker";

    private static final String PREFS_NAME = "boot_tracker";
    private static final String KEY_LAST_BOOT_ID = "last_boot_id";

    /**
     * Settings.Global 中 boot 计数器的键名。
     * 该常量在 Android SDK 中未公开（@hide），但其字符串值 "boot_count" 是稳定的系统契约。
     */
    private static final String GLOBAL_KEY_BOOT_COUNT = "boot_count";

    private BootIdTracker() {
    }

    /**
     * 读取当前启动的 boot_count。
     * @return boot_count 的字符串形式；读取失败返回空字符串。
     */
    public static String getCurrentBootId(Context context) {
        try {
            int bootCount = Settings.Global.getInt(
                    context.getContentResolver(), GLOBAL_KEY_BOOT_COUNT);
            return String.valueOf(bootCount);
        } catch (Settings.SettingNotFoundException e) {
            Log.w(TAG, "读取 boot_count 失败: " + e.getMessage());
            return "";
        } catch (Exception e) {
            Log.w(TAG, "读取 boot_count 异常: " + e.getMessage());
            return "";
        }
    }

    /**
     * 判断是否需要执行开机补偿任务：当前 boot_count 与上次记录的不同（即设备重启过）。
     * @return true 表示设备重启过且尚未标记执行过。
     */
    public static boolean shouldExecuteOnBoot(Context context) {
        String currentBootId = getCurrentBootId(context);
        if (currentBootId.isEmpty()) {
            Log.w(TAG, "无法获取 boot_count，跳过开机补偿检测");
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastBootId = prefs.getString(KEY_LAST_BOOT_ID, "");
        boolean shouldExecute = !currentBootId.equals(lastBootId);
        if (shouldExecute) {
            Log.i(TAG, "检测到设备重启（boot_count: " + lastBootId + " → " + currentBootId + "）");
        }
        return shouldExecute;
    }

    /**
     * 标记当前 boot_count 已执行过，避免同一轮开机重复执行。
     * 应在触发 ResetService 之前调用，防止执行过程中 App 崩溃导致重复执行。
     */
    public static void markExecuted(Context context) {
        String currentBootId = getCurrentBootId(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_BOOT_ID, currentBootId).apply();
    }
}
