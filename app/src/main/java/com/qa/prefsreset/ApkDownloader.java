package com.qa.prefsreset;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 下载新版本 APK 到 App 私有的外部存储目录，并唤起系统安装器。
 *
 * 下载目录：getExternalFilesDir(null)/updates/，随 App 卸载自动清理，
 * 无需申请额外的存储权限（属于本 App 的私有外部目录）。
 */
public final class ApkDownloader {

    private static final String TAG = "ApkDownloader";
    private static final String UPDATE_DIR_NAME = "updates";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private ApkDownloader() {
    }

    /** 下载进度回调，运行在下载所在的后台线程，调用方需自行切换到主线程更新 UI */
    public interface ProgressListener {
        /**
         * @param downloadedBytes 已下载字节数
         * @param totalBytes      文件总字节数；服务端未返回 Content-Length 时为 -1（未知总量）
         */
        void onProgress(long downloadedBytes, long totalBytes);
    }

    public static final class DownloadResult {
        public final boolean success;
        public final String message;
        public final File apkFile;

        private DownloadResult(boolean success, String message, File apkFile) {
            this.success = success;
            this.message = message;
            this.apkFile = apkFile;
        }

        static DownloadResult ok(File apkFile) {
            return new DownloadResult(true, "下载完成: " + apkFile.getAbsolutePath(), apkFile);
        }

        static DownloadResult fail(String message) {
            return new DownloadResult(false, message, null);
        }
    }

    /**
     * 同步下载 APK，必须在后台线程调用。下载完成后可用返回结果里的 apkFile
     * 配合 {@link #installApk(Context, File)} 唤起系统安装器。
     */
    public static DownloadResult download(Context context, String apkUrl, String fileName) {
        return download(context, apkUrl, fileName, null);
    }

    /**
     * 同步下载 APK，必须在后台线程调用，下载过程中通过 {@code listener} 实时上报进度，
     * 供前台展示下载进度条。下载完成后可用返回结果里的 apkFile
     * 配合 {@link #installApk(Context, File)} 唤起系统安装器。
     */
    public static DownloadResult download(Context context, String apkUrl, String fileName,
                                           ProgressListener listener) {
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            return DownloadResult.fail("下载地址为空");
        }
        File dir = new File(context.getExternalFilesDir(null), UPDATE_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return DownloadResult.fail("创建下载目录失败: " + dir.getAbsolutePath());
        }
        File target = new File(dir, fileName);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(apkUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return DownloadResult.fail("下载失败: HTTP " + code);
            }

            long totalBytes = connection.getContentLength();

            try (InputStream is = connection.getInputStream();
                 FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                long downloadedBytes = 0;
                int n;
                while ((n = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, n);
                    downloadedBytes += n;
                    if (listener != null) {
                        listener.onProgress(downloadedBytes, totalBytes);
                    }
                }
            }
            return DownloadResult.ok(target);
        } catch (IOException e) {
            Log.e(TAG, "download apk failed", e);
            return DownloadResult.fail("下载失败: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 唤起系统安装器安装指定 APK 文件。Android 7.0+ 需要通过 FileProvider
     * 生成 content:// Uri 才能授予安装器读取权限，直接传 file:// Uri 会被系统拒绝。
     */
    public static void installApk(Context context, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e("ScriptRunner", "唤起安装器失败: " + e.getMessage(), e);
            throw new RuntimeException("唤起安装器失败: " + e.getMessage(), e);
        }
    }
}
