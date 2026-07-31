package com.qa.prefsreset;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 简单的管理界面：
 * - 展示 ADB 连接状态、提权状态、脚本加载概况
 * - 支持配置一个远端 HTTP(S) 地址，拉取脚本 JSON 并保存到本地
 * - 用列表形式展示本地已加载的所有脚本（ScriptSet），支持展开查看每套脚本的具体任务
 * - 支持对单个脚本单独触发执行，也支持一键全部执行
 * - 展示最近的执行日志
 */
public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView logText;
    private TextView adbStatusText;
    private TextView elevationStatusText;
    private TextView notifListenerStatusText;
    private EditText remoteUrlInput;
    private RecyclerView scriptListView;
    private ScriptSetAdapter scriptSetAdapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 提前初始化 ADB 密钥持久化目录，避免每次连接本机 adbd 都重新生成密钥
        // （若设备开启了 adb secure 校验，重复生成新公钥会导致反复弹出调试授权确认框）。
        AdbKeyManager.init(getFilesDir());

        statusText = findViewById(R.id.text_status);
        logText = findViewById(R.id.text_log);
        adbStatusText = findViewById(R.id.text_adb_status);
        elevationStatusText = findViewById(R.id.text_elevation_status);
        notifListenerStatusText = findViewById(R.id.text_notif_listener_status);
        remoteUrlInput = findViewById(R.id.edit_remote_url);
        scriptListView = findViewById(R.id.recycler_script_list);

        Button runNowButton = findViewById(R.id.button_run_now);
        Button refreshLogButton = findViewById(R.id.button_refresh_log);
        Button fetchRemoteButton = findViewById(R.id.button_fetch_remote);
        Button refreshScriptsButton = findViewById(R.id.button_refresh_scripts);
        Button checkUpdateButton = findViewById(R.id.button_check_update);

        scriptSetAdapter = new ScriptSetAdapter(this::runScript, this::toggleRunOnBoot, this::confirmDeleteScript);
        scriptListView.setLayoutManager(new LinearLayoutManager(this));
        scriptListView.setAdapter(scriptSetAdapter);

        remoteUrlInput.setText(RemoteConfigFetcher.getLastUrl(this));

        runNowButton.setOnClickListener(v -> triggerResetNow(null));
        refreshLogButton.setOnClickListener(v -> refreshLogView());
        fetchRemoteButton.setOnClickListener(v -> fetchRemoteConfig());
        refreshScriptsButton.setOnClickListener(v -> refreshScriptList());
        checkUpdateButton.setOnClickListener(v -> checkForUpdate(true));
        // 点击通知监听状态跳转到「通知使用权」设置页，授权后系统开机时自动拉起进程
        notifListenerStatusText.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        requestExternalStoragePermissionIfNeeded();
        requestNotificationPermissionIfNeeded();
        refreshStatusView();
        refreshScriptList();
        refreshLogView();
        // 静默检查一次更新：无更新或检查失败都不打扰用户，只有发现新版本时才弹窗提示
        checkForUpdate(false);
        // 开机补偿：如果设备重启过且未执行过，自动触发一次脚本执行
        maybeAutoExecuteOnBoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统设置页返回，回到前台时刷新一次状态展示
        refreshStatusView();
    }

    /**
     * Android 11 (API 30) 及以上访问 /sdcard 根目录下的自定义文件夹（qa_prefs_reset）
     * 需要 MANAGE_EXTERNAL_STORAGE 特殊权限，仅在 Manifest 中声明不够，
     * 还需引导用户跳转系统设置页手动授权。
     */
    private void requestExternalStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "请授予「所有文件访问权限」，否则无法读取配置文件", Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }
    }

    /**
     * Android 13 (API 33) 及以上需要运行时请求 POST_NOTIFICATIONS 权限，
     * 否则普通通知（非前台服务通知）无法显示，开机自启执行结果通知用户就看不到。
     * 首次启动时弹出系统授权弹窗，用户授权后后续重启不再弹出。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    /**
     * 检查新版本：请求 GitHub Release 中的 version.json 并与本地 versionCode 对比。
     * @param manual 是否由用户手动点击「检查更新」触发：true 时无论结果如何都会用 Toast 提示，
     *               false（App 启动时的静默检查）只有发现新版本才弹窗，避免打扰用户。
     */
    private void checkForUpdate(boolean manual) {
        if (manual) {
            Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            int currentVersionCode = getCurrentVersionCode();
            UpdateChecker.CheckResult result = UpdateChecker.checkForUpdate(currentVersionCode);
            runOnUiThread(() -> {
                if (!result.success) {
                    if (manual) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if (!result.hasUpdate) {
                    if (manual) {
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                showUpdateDialog(result.updateInfo);
            });
        });
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** 弹窗展示新版本信息，用户确认后才开始下载，避免在流量敏感场景下静默下载 */
    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        String message = "发现新版本 " + info.versionName + "\n\n"
                + (info.changelog.isEmpty() ? "（未提供更新说明）" : info.changelog);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstallUpdate(info))
                .setNegativeButton("以后再说", null)
                .show();
    }

    /**
     * 前台下载新版本 APK：弹出带进度条的对话框展示实时下载进度，下载期间不可取消，
     * 避免用户误以为无响应而反复点击；完成后自动关闭对话框并唤起系统安装器。
     */
    private void downloadAndInstallUpdate(UpdateChecker.UpdateInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            // Android 8.0+ 安装未知来源应用需要用户先在设置页手动授权，引导跳转后本次先不下载，
            // 用户下次点击「立即更新」时会重新走到这里，届时权限通常已经开启
            Toast.makeText(this, "请先允许「安装未知应用」权限，授权后请重新点击更新", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                // 部分设备没有该设置页，忽略即可，用户仍可在系统安装确认弹窗中手动允许
            }
            return;
        }

        View progressView = LayoutInflater.from(this).inflate(R.layout.dialog_download_progress, null);
        ProgressBar progressBar = progressView.findViewById(R.id.progress_download);
        TextView percentText = progressView.findViewById(R.id.text_download_percent);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setView(progressView)
                .setCancelable(false)
                .create();
        progressDialog.show();

        // 记录上一次上报的百分比，避免每读一个 8KB 缓冲区都切主线程刷新 UI，
        // 只在百分比数值真正变化时才更新，减少不必要的开销
        final int[] lastPercent = {-1};
        executor.execute(() -> {
            String fileName = "script-runner-" + info.versionName + ".apk";
            ApkDownloader.DownloadResult result = ApkDownloader.download(
                    getApplicationContext(), info.apkUrl, fileName,
                    (downloadedBytes, totalBytes) -> {
                        if (totalBytes > 0) {
                            int percent = (int) (downloadedBytes * 100 / totalBytes);
                            if (percent == lastPercent[0]) {
                                return;
                            }
                            lastPercent[0] = percent;
                            runOnUiThreadIfAlive(() -> {
                                progressBar.setIndeterminate(false);
                                progressBar.setProgress(percent);
                                percentText.setText(percent + "%  ("
                                        + formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes) + ")");
                            });
                        } else {
                            // 服务端未返回 Content-Length，无法计算百分比，改为不确定进度动画
                            runOnUiThreadIfAlive(() -> {
                                progressBar.setIndeterminate(true);
                                percentText.setText(formatBytes(downloadedBytes));
                            });
                        }
                    });
            ResetLogRepository.log((result.success ? "新版本下载成功: " : "新版本下载失败: ") + result.message);
            runOnUiThreadIfAlive(() -> {
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                if (!result.success) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "下载完成，即将唤起安装", Toast.LENGTH_SHORT).show();
                try {
                    ApkDownloader.installApk(this, result.apkFile);
                } catch (Exception e) {
                    Toast.makeText(this, "唤起安装器失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * 在 UI 线程执行任务，但如果 Activity 已经销毁/正在销毁则跳过，
     * 避免下载过程中用户退出页面后，后台线程仍尝试更新已失效的对话框/控件而抛异常。
     */
    private void runOnUiThreadIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            action.run();
        });
    }

    /** 将字节数格式化为易读的 KB/MB 文本，用于下载进度展示 */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.getDefault(), "%.1fKB", kb);
        }
        double mb = kb / 1024.0;
        return String.format(java.util.Locale.getDefault(), "%.1fMB", mb);
    }

    /** 一键执行全部已加载脚本 */
    private void triggerResetNow(String scriptId) {
        Toast.makeText(this, "已提交重置任务，请查看日志", Toast.LENGTH_SHORT).show();
        Intent serviceIntent = new Intent(this, ResetService.class);
        if (scriptId != null) {
            serviceIntent.putExtra(ResetService.EXTRA_SCRIPT_ID, scriptId);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        // 延迟刷新一次日志，给后台任务留出执行时间
        logText.postDelayed(this::refreshLogView, 3000);
    }

    /** 列表中点击单个脚本的「执行」按钮 */
    private void runScript(ScriptSet scriptSet) {
        Toast.makeText(this, "已提交脚本: " + scriptSet.name, Toast.LENGTH_SHORT).show();
        triggerResetNow(scriptSet.id);
    }

    /**
     * 列表中切换某个脚本的「随开机执行」开关：
     * 持久化保存到本地（{@link ScriptBootPrefs}），下次开机时 ResetService 会按此开关过滤脚本；
     * 不影响手动执行——用户仍可随时点击「执行」按钮手动触发这套脚本。
     */
    private void toggleRunOnBoot(ScriptSet scriptSet, boolean runOnBoot) {
        ScriptBootPrefs.setOverride(this, scriptSet.id, runOnBoot);
        String tip = runOnBoot ? "已开启「随开机执行」: " + scriptSet.name
                : "已关闭「随开机执行」: " + scriptSet.name + "（仍可手动执行）";
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
        refreshStatusView();
    }

    /** 列表中点击单个脚本的「删除」按钮：先弹窗二次确认，避免误删 */
    private void confirmDeleteScript(ScriptSet scriptSet) {
        new AlertDialog.Builder(this)
                .setTitle("删除脚本")
                .setMessage("确定要删除脚本 [" + scriptSet.name + "] 吗？\n"
                        + "若该脚本与其他脚本共存于同一个文件（通过 scripts 数组声明），"
                        + "只会删除这一个脚本，文件内其余脚本会保留；此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> deleteScript(scriptSet))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 实际执行删除：定位并删除脚本文件，同时清理本地的「随开机执行」覆盖记录，删除后刷新列表 */
    private void deleteScript(ScriptSet scriptSet) {
        executor.execute(() -> {
            ResetConfigLoader.DeleteResult result = ResetConfigLoader.deleteScriptById(scriptSet.id);
            if (result.success) {
                ScriptBootPrefs.clearOverride(this, scriptSet.id);
            }
            ResetLogRepository.log((result.success ? "删除脚本成功: " : "删除脚本失败: ")
                    + scriptSet.name + " - " + result.message);
            runOnUiThread(() -> {
                Toast.makeText(this, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    refreshScriptList();
                }
                refreshLogView();
            });
        });
    }

    /** 从远端拉取脚本配置并保存到本地 */
    private void fetchRemoteConfig() {
        String url = remoteUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请先输入远端配置地址", Toast.LENGTH_SHORT).show();
            return;
        }
        RemoteConfigFetcher.saveLastUrl(this, url);
        Toast.makeText(this, "正在拉取...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            RemoteConfigFetcher.FetchResult result = RemoteConfigFetcher.fetchAndSave(url, null);
            runOnUiThread(() -> {
                Toast.makeText(this, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    refreshScriptList();
                }
                refreshLogView();
            });
        });
    }

    /** 重新扫描本地所有脚本来源（scripts/ + remote/）并刷新列表 */
    private void refreshScriptList() {
        executor.execute(() -> {
            List<ScriptSet> scriptSets = ResetConfigLoader.loadAllScriptSets(getApplicationContext());
            runOnUiThread(() -> {
                scriptSetAdapter.submitList(scriptSets);
                refreshStatusView();
            });
        });
    }

    private void refreshStatusView() {
        executor.execute(() -> {
            boolean rootAvailable = RootShellExecutor.isRootAvailable();
            List<ScriptSet> scriptSets = ResetConfigLoader.loadAllScriptSets(getApplicationContext());

            int totalTasks = 0;
            int totalRunnable = 0;
            int runOnBootCount = 0;
            for (ScriptSet s : scriptSets) {
                totalTasks += s.tasks.size();
                totalRunnable += s.countRunnableTasks();
                if (s.runOnBoot) {
                    runOnBootCount++;
                }
            }

            // ADB 连接状态
            boolean adbConnected = RootShellExecutor.isLastAdbConnected();
            int adbPort = AdbClient.getLastConnectedPort();
            String adbStatus = "ADB: " + (adbConnected ? "已连接 :" + adbPort : "未连接");

            // 提权状态
            RootShellExecutor.ElevationMethod method = RootShellExecutor.getLastElevationMethod();
            String elevationStatus;
            if (rootAvailable) {
                switch (method) {
                    case ALREADY_ROOT:
                        elevationStatus = "提权: adbd 已是 root";
                        break;
                    case ROOT_SERVICE:
                        elevationStatus = "提权: root: 命令";
                        break;
                    case SETPROP:
                        elevationStatus = "提权: setprop 注入";
                        break;
                    case SU:
                        elevationStatus = "提权: su 命令";
                        break;
                    default:
                        elevationStatus = "提权: 已获取 root";
                        break;
                }
            } else {
                elevationStatus = "提权: 不可用";
            }

            // 脚本统计
            String statusSummary = "已加载脚本: " + scriptSets.size()
                    + " (任务 " + totalTasks
                    + " 条，白名单命中 " + totalRunnable + " 条，"
                    + "随开机执行 " + runOnBootCount + " 套)";

            // 通知监听权限状态：已授权时系统开机自动拉起进程，未授权时点击跳转设置
            boolean notifListenerEnabled = NotificationManagerCompat
                    .getEnabledListenerPackages(getApplicationContext())
                    .contains(getPackageName());
            String notifStatus = "通知监听: "
                    + (notifListenerEnabled ? "已授权 ✓（开机自启已生效）"
                                            : "未授权 ✗（点击此处授权以实现开机自启）");

            runOnUiThread(() -> {
                adbStatusText.setText(adbStatus);
                elevationStatusText.setText(elevationStatus);
                notifListenerStatusText.setText(notifStatus);
                statusText.setText(statusSummary);
            });
        });
    }

    /**
     * 开机补偿执行：检测设备是否重启过（通过 boot_id），如果是且有 runOnBoot=true 的脚本，
     * 自动触发 ResetService 执行一次。
     *
     * 背景：LDPlayer 等模拟器不向非系统应用发送 BOOT_COMPLETED 广播，导致 BootReceiver
     * 无法被触发。本方法在 App 每次启动时检测 boot_id 变化，作为开机自启的补偿机制。
     *
     * 注意：此方法在单线程 executor 上执行，会排在 refreshStatusView 之后，
     * 因此 isRootAvailable() 可以利用 refreshStatusView 中已完成的 root 检测缓存。
     */
    private void maybeAutoExecuteOnBoot() {
        executor.execute(() -> {
            if (!BootIdTracker.shouldExecuteOnBoot(this)) {
                return;
            }
            // root 检测（可能已在 refreshStatusView 中完成，这里利用缓存快速返回）
            boolean rootAvailable = RootShellExecutor.isRootAvailable();
            if (!rootAvailable) {
                // 不标记 markExecuted：App 刚启动时 ADB 可能尚未就绪或提权尚未完成，
                // 如果此时标记已执行，本次开机周期内即使 root 恢复也不会再触发。
                // 保留 boot_count 未标记状态，用户下次打开 App 时会再次尝试。
                ResetLogRepository.log("启动时自动执行：root 暂时不可用（"
                        + RootShellExecutor.getLastDetectionDetail() + "），本次跳过，下次启动会再次尝试");
                runOnUiThread(this::refreshLogView);
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
                return;
            }
            // 先标记，防止执行过程中崩溃导致重复触发
            BootIdTracker.markExecuted(this);
            ResetLogRepository.log("检测到设备重启，App 启动时自动执行「随开机执行」的脚本");

            Intent serviceIntent = new Intent(this, ResetService.class);
            serviceIntent.putExtra(ResetService.EXTRA_TRIGGERED_ON_BOOT, true);
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                // 延迟刷新日志，让 ResetService 有时间执行完毕并写入日志
                logText.postDelayed(this::refreshLogView, 3000);
            });
        });
    }

    private void refreshLogView() {
        List<String> logs = ResetLogRepository.getRecentLogs();
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, logs.size() - 200);
        for (int i = start; i < logs.size(); i++) {
            sb.append(logs.get(i)).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("暂无日志，点击「全部执行」或某个脚本的「执行」按钮触发一次任务，或等待下次开机自动执行。");
        }
        logText.setText(sb.toString());
        refreshStatusView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
