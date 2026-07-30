package com.qa.prefsreset;

import android.content.Intent;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通过 NotificationListenerService 实现开机自动拉起进程。
 *
 * 背景：LDPlayer 等模拟器不向非系统应用发送 BOOT_COMPLETED 广播，
 * 且 USER_PRESENT 等广播受 "Background execution not allowed" 限制，receiver 的 onReceive
 * 不会被调用。NotificationListenerService 是系统级服务，系统会在开机时自动绑定并拉起
 * 应用进程，一旦进程启动，onListenerConnected 就会被调用，此时可以触发脚本执行。
 *
 * 参考设备上小八Launcher（com.xiaoba.launcher）的做法：它注册了 NotificationListenerService
 * 实现开机自启，进程名 xiaoba.launcher:notification_listener。
 *
 * 使用前提：用户需在系统设置中授权「通知使用权」，授权后重启永久有效。
 * 授权引导见 {@link MainActivity} 中的通知监听状态点击逻辑。
 *
 * 与 BootReceiver、MainActivity.maybeAutoExecuteOnBoot 互为冗余：
 * 三者都通过 BootIdTracker.shouldExecuteOnBoot 检查 boot_count 确保同一轮开机只执行一次，
 * 谁先触发就由谁执行，其余检测到已标记则自动跳过。
 */
public class BootNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "BootNotifListener";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        // 与 ResetService、MainActivity 保持一致：确保 ADB 密钥落在同一个持久化目录，
        // 这样无论谁触发 root 检测，用的都是同一份密钥。
        AdbKeyManager.init(getFilesDir());
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知监听服务已连接，检查是否需要执行开机补偿任务");

        executor.execute(() -> {
            // 通过 boot_count 确保同一轮开机只执行一次
            if (!BootIdTracker.shouldExecuteOnBoot(this)) {
                Log.i(TAG, "boot_count 未变化，无需执行开机补偿任务");
                return;
            }

            // root 检测：系统刚拉起进程时 ADB 可能尚未就绪
            boolean rootAvailable = RootShellExecutor.isRootAvailable();
            if (!rootAvailable) {
                // 不标记 markExecuted，保留 boot_count 未标记状态，
                // MainActivity 启动时或下次 onListenerConnected 时会再次尝试
                ResetLogRepository.log("通知监听服务触发：root 暂时不可用（"
                        + RootShellExecutor.getLastDetectionDetail() + "），本次跳过，等待后续重试");
                return;
            }

            // 检查是否有 runOnBoot=true 的脚本
            List<ScriptSet> scriptSets = ResetConfigLoader.loadAllScriptSets(getApplicationContext());
            boolean hasRunOnBoot = false;
            for (ScriptSet s : scriptSets) {
                if (s.runOnBoot) {
                    hasRunOnBoot = true;
                    break;
                }
            }
            if (!hasRunOnBoot) {
                BootIdTracker.markExecuted(this);
                Log.i(TAG, "没有开启「随开机执行」的脚本，已标记 boot_count 跳过");
                return;
            }

            // 先标记，防止执行过程中崩溃或其他触发点重复执行
            BootIdTracker.markExecuted(this);
            ResetLogRepository.log("通知监听服务检测到设备重启，自动执行「随开机执行」的脚本");

            Intent serviceIntent = new Intent(this, ResetService.class);
            serviceIntent.putExtra(ResetService.EXTRA_TRIGGERED_ON_BOOT, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
