package com.qa.prefsreset;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：开机后由 BootReceiver 拉起，读取配置并执行 shared_prefs 重置任务。
 * 使用前台服务是为了避免 Android 8.0+ 对后台服务的限制导致任务被系统提前杀死。
 */
public class ResetService extends Service {

    private static final String CHANNEL_ID = "qa_prefs_reset_channel";
    private static final int NOTIFICATION_ID = 1001;

    /** 若 Intent 携带该 extra，则只执行指定 id 的脚本；不携带则执行全部已加载脚本 */
    public static final String EXTRA_SCRIPT_ID = "extra_script_id";

    /**
     * 若 Intent 携带该 extra 且为 true，表示这是「开机自动触发」而非用户在 App 内手动点击，
     * 此时只会执行 runOnBoot=true 的脚本；手动触发（无论全部执行还是单独执行某一套）
     * 始终会忽略 runOnBoot 开关，任一脚本都可以随时手动执行。
     */
    public static final String EXTRA_TRIGGERED_ON_BOOT = "extra_triggered_on_boot";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();
        // 与 MainActivity 保持一致：确保 ADB 密钥落在同一个持久化目录，
        // 这样无论是开机自动触发还是手动触发，用的都是同一份密钥。
        AdbKeyManager.init(getFilesDir());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("正在执行数据重置任务..."));

        String scriptId = intent == null ? null : intent.getStringExtra(EXTRA_SCRIPT_ID);
        boolean triggeredOnBoot = intent != null && intent.getBooleanExtra(EXTRA_TRIGGERED_ON_BOOT, false);
        executor.execute(() -> {
            runResetTasks(scriptId, triggeredOnBoot);
            stopForeground(true);
            stopSelf();
        });

        return START_NOT_STICKY;
    }

    /**
     * @param scriptId        为空时执行所有已加载的脚本；非空时只执行匹配 id 的那一套
     * @param triggeredOnBoot 是否为开机自动触发：为 true 时会额外过滤掉 runOnBoot=false 的脚本；
     *                        手动触发（false）始终忽略该开关，任一脚本随时可以手动执行
     */
    private void runResetTasks(String scriptId, boolean triggeredOnBoot) {
        ResetLogRepository.log("ResetService 启动，开始检查 root 权限"
                + (triggeredOnBoot ? "（开机自动触发）" : "（手动触发）"));

        if (!RootShellExecutor.isRootAvailable()) {
            ResetLogRepository.log("root 检测失败: " + RootShellExecutor.getLastDetectionDetail());
            ResetLogRepository.log("任务终止。请确认设备已开启 adb 调试，且 adbd 已是 root 或 su 可用。");
            return;
        }

        ResetLogRepository.log("root 检测成功: " + RootShellExecutor.getLastDetectionDetail());

        List<ScriptSet> scriptSets = ResetConfigLoader.loadAllScriptSets(getApplicationContext());
        if (scriptId != null && !scriptId.isEmpty()) {
            scriptSets.removeIf(s -> !scriptId.equals(s.id));
        }
        if (triggeredOnBoot) {
            int beforeCount = scriptSets.size();
            scriptSets.removeIf(s -> !s.runOnBoot);
            int skipped = beforeCount - scriptSets.size();
            if (skipped > 0) {
                ResetLogRepository.log("开机自动触发：已跳过 " + skipped + " 套未开启「随开机执行」的脚本");
            }
        }

        if (scriptSets.isEmpty()) {
            ResetLogRepository.log("未找到有效的脚本配置"
                    + (scriptId != null ? "（指定 id=" + scriptId + "）" : "")
                    + (triggeredOnBoot ? "（或已加载脚本均未开启「随开机执行」）" : "")
                    + "，请检查 " + ResetConfigLoader.getScriptsDir().getAbsolutePath());
            return;
        }

        ScriptTaskExecutor resetExecutor = new ScriptTaskExecutor(getApplicationContext());
        long totalSuccess = 0;
        long totalCount = 0;
        for (ScriptSet scriptSet : scriptSets) {
            if (scriptSet.tasks.isEmpty()) {
                ResetLogRepository.log("脚本 [" + scriptSet.name + "] 未包含任何任务，已跳过");
                continue;
            }
            if (scriptSet.whitelist.isEmpty()) {
                ResetLogRepository.log("脚本 [" + scriptSet.name + "] 白名单为空，所有任务都会被跳过");
            }
            List<ScriptTaskExecutor.TaskResult> results = resetExecutor.executeScriptSet(scriptSet);
            long successCount = results.stream().filter(r -> r.success).count();
            totalSuccess += successCount;
            totalCount += results.size();
            ResetLogRepository.log("脚本 [" + scriptSet.name + "] 执行完成: 成功 "
                    + successCount + " / 总数 " + results.size());
        }

        ResetLogRepository.log("本次重置任务全部完成: 成功 " + totalSuccess
                + " / 总数 " + totalCount + "，共涉及 " + scriptSets.size() + " 套脚本");
    }

    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
