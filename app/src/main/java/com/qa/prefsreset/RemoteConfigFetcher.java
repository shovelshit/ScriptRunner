package com.qa.prefsreset;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 负责从配置的远端 HTTP(S) 地址拉取脚本配置 JSON，并落盘到本地
 * {@link ResetConfigLoader#getRemoteScriptsDir()} 目录下，供后续重置任务读取。
 *
 * 远端返回的 JSON 格式与本地脚本文件完全一致（见 {@link ResetConfigLoader}），
 * 既可以是单个脚本对象，也可以是 {"scripts": [...]} 的多脚本数组。
 *
 * 远端地址会持久化保存在 SharedPreferences 中，下次打开 App 自动带出。
 */
public final class RemoteConfigFetcher {

    private static final String TAG = "RemoteConfigFetcher";
    private static final String PREFS_NAME = "remote_config_fetcher";
    private static final String KEY_LAST_URL = "last_url";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long MAX_RESPONSE_BYTES = 5L * 1024 * 1024; // 5MB 上限，避免异常响应撑爆内存/存储

    private RemoteConfigFetcher() {
    }

    public static final class FetchResult {
        public final boolean success;
        public final String message;
        public final int scriptCount;
        public final File savedFile;

        private FetchResult(boolean success, String message, int scriptCount, File savedFile) {
            this.success = success;
            this.message = message;
            this.scriptCount = scriptCount;
            this.savedFile = savedFile;
        }

        static FetchResult ok(int scriptCount, File savedFile) {
            return new FetchResult(true, "拉取成功，共 " + scriptCount + " 套脚本", scriptCount, savedFile);
        }

        static FetchResult fail(String message) {
            return new FetchResult(false, message, 0, null);
        }
    }

    public static String getLastUrl(Context context) {
        return prefs(context).getString(KEY_LAST_URL, "");
    }

    public static void saveLastUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_LAST_URL, url).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 同步执行网络请求，必须在后台线程调用。
     *
     * @param url        远端配置地址，仅支持 http/https
     * @param fileName   落盘时使用的文件名（不含目录），为空时使用时间戳自动生成
     */
    public static FetchResult fetchAndSave(String url, String fileName) {
        if (url == null || url.trim().isEmpty()) {
            return FetchResult.fail("远端地址不能为空");
        }
        String trimmedUrl = url.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return FetchResult.fail("仅支持 http:// 或 https:// 开头的地址");
        }

        String body;
        try {
            body = doHttpGet(trimmedUrl);
        } catch (IOException e) {
            String msg = "网络请求失败: " + e.getMessage();
            Log.e(TAG, msg, e);
            return FetchResult.fail(msg);
        }

        // 远端地址若指向 .json.b64 密文文件（URL 路径以 .b64 结尾），需先解码为
        // JSON 明文再解析，否则 base64 密文会被当作非法 JSON 直接拒绝。
        String json = body;
        if (isBase64Url(trimmedUrl)) {
            try {
                json = ResetConfigLoader.decodeFromBase64(body);
            } catch (IllegalArgumentException e) {
                String msg = "远端返回内容不是合法 base64: " + e.getMessage();
                Log.e(TAG, msg, e);
                return FetchResult.fail(msg);
            }
        }

        List<ScriptSet> parsed;
        try {
            parsed = ResetConfigLoader.parseScriptSetsFromJson(json, ResetConfigLoader.SOURCE_REMOTE, safeFileName(fileName, trimmedUrl));
        } catch (JSONException e) {
            String msg = "远端返回内容不是合法 JSON: " + e.getMessage();
            Log.e(TAG, msg, e);
            return FetchResult.fail(msg);
        }

        if (parsed.isEmpty()) {
            return FetchResult.fail("远端返回内容中未解析到任何脚本任务");
        }

        File dir = ResetConfigLoader.getRemoteScriptsDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return FetchResult.fail("创建本地存储目录失败: " + dir.getAbsolutePath());
        }

        // 落盘时统一转成 base64 密文（.json.b64），避免脚本里的敏感信息
        // 以明文形式留在 sdcard 上被任意持有 shell 权限的人直接搜到。
        // 注意：传入的是已解码的 JSON 明文（json），由 writeAsBase64Script 统一编码落盘，
        // 无论远端返回的是明文 JSON 还是 base64 密文，落盘结果都一致。
        String baseName = safeFileName(fileName, trimmedUrl).replaceAll("(?i)\\.json$", "");
        File targetFile;
        try {
            targetFile = ResetConfigLoader.writeAsBase64Script(dir, baseName, json);
        } catch (IOException e) {
            String msg = "写入本地文件失败: " + e.getMessage();
            Log.e(TAG, msg, e);
            return FetchResult.fail(msg);
        }

        ResetLogRepository.log("已从远端拉取脚本配置并以密文(base64)保存到本地: " + targetFile.getAbsolutePath()
                + "，共 " + parsed.size() + " 套脚本");
        return FetchResult.ok(parsed.size(), targetFile);
    }

    private static String doHttpGet(String urlString) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " " + connection.getResponseMessage());
            }

            try (InputStream is = connection.getInputStream()) {
                return readStreamWithLimit(is, MAX_RESPONSE_BYTES);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readStreamWithLimit(InputStream is, long maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("响应体超过大小上限 " + maxBytes + " 字节");
                }
                sb.append(buffer, 0, n);
            }
        }
        return sb.toString();
    }

    private static String safeFileName(String fileName, String url) {
        String base = (fileName == null || fileName.trim().isEmpty())
                ? "remote_" + Math.abs(url.hashCode())
                : fileName.trim();
        String withoutExt = base.replaceAll("(?i)\\.json$", "");
        String sanitized = withoutExt.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return sanitized + ".json";
    }

    /**
     * 判断远端地址是否指向 base64 密文文件：忽略 query 参数后，URL 路径以 .b64 结尾。
     * 与本地脚本目录按文件名后缀判断 base64 的逻辑（见 ResetConfigLoader）保持一致。
     */
    private static boolean isBase64Url(String url) {
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".b64");
    }
}
