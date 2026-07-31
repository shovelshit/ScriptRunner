package com.qa.prefsreset;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：开机后由 BootReceiver / BootNotificationListenerService 拉起，
 * 读取配置并执行 shared_prefs 重置任务。
 * 使用前台服务是为了避免 Android 8.0+ 对后台服务的限制导致任务被系统提前杀死。
 *
 * 执行完成后会发送一条高优先级通知告知用户结果（成功/失败/跳过），
 * 点击通知可打开 App 查看详情，让用户明确感知到开机自启已执行。
 */
public class ResetService extends Service {

    private static final String CHANNEL_ID = "qa_prefs_reset_channel";
    private static final String CHANNEL_ID_RESULT = "qa_prefs_reset_result";
    private static final int NOTIFICATION_ID = 1001;
    private static final int RESULT_NOTIFICATION_ID = 1002;

    /** 若 Intent 携带该 extra，则只执行指定 id 的脚本；不携带则执行全部已加载脚本 */
    public static final String EXTRA_SCRIPT_ID = "extra_script_id";

    /**
     * 若 Intent 携带该 extra 且为 true，表示这是「开机自动触发」而非用户在 App 内手动点击，
     * 此时只会执行 runOnBoot=true 的脚本；手动触发（无论全部执行还是单独执行某一套）
     * 始终会忽略 runOnBoot 开关，任一脚本都可以随时手动执行。
     */
    public static final String EXTRA_TRIGGERED_ON_BOOT = "extra_triggered_on_boot";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 防止开机时 BootReceiver 和 BootNotificationListenerService 同时触发导致重复执行 */
    private static volatile boolean alreadyRunning = false;

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
        // 防止开机时 BootReceiver 和 BootNotificationListenerService 同时触发导致重复执行
        if (alreadyRunning) {
            ResetLogRepository.log("检测到已有重置任务在执行中，跳过本次重复触发");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        alreadyRunning = true;

        startForeground(NOTIFICATION_ID, buildNotification("正在执行数据重置任务..."));

        String scriptId = intent == null ? null : intent.getStringExtra(EXTRA_SCRIPT_ID);
        boolean triggeredOnBoot = intent != null && intent.getBooleanExtra(EXTRA_TRIGGERED_ON_BOOT, false);
        executor.execute(() -> {
            String resultSummary = runResetTasks(scriptId, triggeredOnBoot);
            // 先停止前台服务通知（不移除通知），再发送结果通知
            stopForeground(false);
            // 发送执行结果通知（高优先级，用户可见），让用户知道开机自启已执行及结果
            showResultNotification(resultSummary, triggeredOnBoot);
            alreadyRunning = false;
            stopSelf();
        });

        return START_NOT_STICKY;
    }

    /**
     * @param scriptId        为空时执行所有已加载的脚本；非空时只执行匹配 id 的那一套
     * @param triggeredOnBoot 是否为开机自动触发：为 true 时会额外过滤掉 runOnBoot=false 的脚本；
     *                        手动触发（false）始终忽略该开关，任一脚本随时可以手动执行
     * @return 执行结果摘要文本，用于结果通知展示
     */
    private String runResetTasks(String scriptId, boolean triggeredOnBoot) {
        ResetLogRepository.log("ResetService 启动，开始检查 root 权限"
                + (triggeredOnBoot ? "（开机自动触发）" : "（手动触发）"));

        if (!RootShellExecutor.isRootAvailable()) {
            ResetLogRepository.log("root 检测失败: " + RootShellExecutor.getLastDetectionDetail());
            ResetLogRepository.log("任务终止。请确认设备已开启 adb 调试，且 adbd 已是 root 或 su 可用。");
            return "❌ root 权限不可用，任务未执行";
        }

        ResetLogRepository.log("root 检测成功: " + RootShellExecutor.getLastDetectionDetail());

        // 模拟器重启后 MANAGE_EXTERNAL_STORAGE 权限可能被重置，导致无法读取 /sdcard 下的脚本文件。
        // 这里在 root 可用的前提下，检测到权限缺失时自动用 root 恢复，确保开机自启能正常加载脚本。
        ensureExternalStoragePermission();
        // Android 13+ 通知权限也可能在重启后丢失，用 root 恢复后才能发送结果通知
        ensureNotificationPermission();

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
            return "⚠️ 未找到可执行的脚本配置";
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

        String summary = "✅ 执行完成: 成功 " + totalSuccess + " / 总数 " + totalCount
                + "，共涉及 " + scriptSets.size() + " 套脚本";
        ResetLogRepository.log("本次重置任务全部完成: 成功 " + totalSuccess
                + " / 总数 " + totalCount + "，共涉及 " + scriptSets.size() + " 套脚本");
        return summary;
    }

    /**
     * 检查 MANAGE_EXTERNAL_STORAGE 权限，如果缺失且 root 可用，则用 root 命令自动恢复。
     * 解决模拟器重启后 appops 权限被重置导致无法读取 /sdcard 脚本文件的问题。
     */
    private void ensureExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            ResetLogRepository.log("MANAGE_EXTERNAL_STORAGE 权限缺失，尝试用 root 自动恢复");
            String packageName = getPackageName();
            RootShellExecutor.ShellResult result = RootShellExecutor.execCommands(
                    new String[]{"appops set " + packageName + " MANAGE_EXTERNAL_STORAGE allow"},
                    10);
            if (result.isSuccess()) {
                ResetLogRepository.log("MANAGE_EXTERNAL_STORAGE 权限已通过 root 自动恢复");
            } else {
                ResetLogRepository.log("MANAGE_EXTERNAL_STORAGE 权限自动恢复失败: " + result.stderr);
            }
        }
    }

    /**
     * Android 13+ 需要运行时 POST_NOTIFICATIONS 权限才能显示普通通知。
     * 开机自启时没有 UI 无法弹权限请求框，在 root 可用的前提下用 root 命令直接授予。
     */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ResetLogRepository.log("POST_NOTIFICATIONS 权限缺失，尝试用 root 自动恢复");
            String packageName = getPackageName();
            RootShellExecutor.ShellResult result = RootShellExecutor.execCommands(
                    new String[]{"pm grant " + packageName + " android.permission.POST_NOTIFICATIONS"},
                    10);
            if (result.isSuccess()) {
                ResetLogRepository.log("POST_NOTIFICATIONS 权限已通过 root 自动恢复");
            } else {
                ResetLogRepository.log("POST_NOTIFICATIONS 权限自动恢复失败: " + result.stderr);
            }
        }
    }

    /**
     * 发送一条高优先级通知展示执行结果，点击可打开 App 查看详情。
     * 仅在开机自动触发时发送（手动触发时用户已在 App 内，不需要额外通知）。
     */
    private void showResultNotification(String summary, boolean triggeredOnBoot) {
        if (!triggeredOnBoot) {
            return;
        }
        createResultNotificationChannelIfNeeded();

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID_RESULT)
                .setContentTitle("Script Runner 开机自启执行完毕")
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(true)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(RESULT_NOTIFICATION_ID, notification);
        }

        // 车机等设备通知可能不弹出，用 Toast 做兜底提示
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        });
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

    /**
     * 创建高优先级通知渠道，用于展示开机自启执行结果。
     * 与执行中的低优先级渠道分开，确保结果通知能够弹出且用户可见。
     */
    private void createResultNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID_RESULT) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID_RESULT, "执行结果通知", NotificationManager.IMPORTANCE_HIGH);
                channel.setSound(null, null);
                channel.enableVibration(false);
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
