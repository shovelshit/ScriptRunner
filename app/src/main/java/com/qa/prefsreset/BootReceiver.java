package com.qa.prefsreset;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 监听开机/解锁相关广播，拉起 ResetService 执行本地配置的重置任务。
 * 仅用于内部测试机场景。
 *
 * 触发源说明（按实际可用性排序）：
 * 1. USER_PRESENT（用户解锁屏幕）：LDPlayer 等模拟器不向非系统应用发送 BOOT_COMPLETED，
 *    但会发送 USER_PRESENT。参考设备上其他能自启的第三方应用（如小八Launcher），都注册了
 *    此广播作为开机自启的实际触发源。这是 LDPlayer 上的主要触发路径。
 * 2. BOOT_COMPLETED：标准开机完成广播，正常设备上可用，LDPlayer 上不分发给非系统应用。
 * 3. QUICKBOOT_POWERON：部分厂商 ROM 的「快速开机」私有广播。
 * 4. LOCKED_BOOT_COMPLETED：设备解锁前触发，此时 /sdcard 通常不可访问，仅记录日志不执行任务。
 *
 * 防重复执行：USER_PRESENT 每次解锁屏幕都会触发，通过 BootIdTracker.shouldExecuteOnBoot()
 * 检查 boot_count，确保同一轮开机只执行一次。MainActivity 启动时也会做同样的补偿检查，
 * 两者互为兜底——谁先触发就由谁执行，另一方检测到已标记则自动跳过。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // 设备尚未解锁，外部存储（/sdcard）通常不可用，这里只记录日志、不执行重置任务，
            // 真正的任务会在稍后的 BOOT_COMPLETED 或 USER_PRESENT 中触发。
            ResetLogRepository.log("收到 LOCKED_BOOT_COMPLETED（设备解锁前），已尽早感知到开机流程");
            return;
        }

        boolean isBootCompleted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean isQuickboot = ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_HTC_QUICKBOOT_POWERON.equals(action);
        boolean isUserPresent = Intent.ACTION_USER_PRESENT.equals(action);
        if (!isBootCompleted && !isQuickboot && !isUserPresent) {
            return;
        }

        // 通过 boot_count 确保同一轮开机只执行一次。
        // USER_PRESENT 每次解锁屏幕都会触发，尤其需要此检查防止重复执行；
        // BOOT_COMPLETED 和 QUICKBOOT_POWERON 理论上每次开机只触发一次，
        // 但加上检查也无害，且能与 MainActivity 的补偿机制互斥。
        if (!BootIdTracker.shouldExecuteOnBoot(context)) {
            return;
        }
        // 先标记，防止执行过程中崩溃或其他广播重复触发
        BootIdTracker.markExecuted(context);

        ResetLogRepository.log("收到开机/解锁广播（" + action + "），准备启动 ResetService");

        Intent serviceIntent = new Intent(context, ResetService.class);
        serviceIntent.putExtra(ResetService.EXTRA_TRIGGERED_ON_BOOT, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
