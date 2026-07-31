package com.qa.prefsreset;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 检查新版本：请求 Release 中发布的 version.json，与本地 versionCode 对比。
 *
 * 配套的 GitHub Actions workflow（.github/workflows/build.yml）在推送 tag（如 v1.0.0）
 * 时会自动构建 APK，并把下面这份 version.json 一并发布到：
 * 1) GitHub Release（唯一构建来源）；
 * 2) 同步镜像到 Gitee Release（.github/workflows/sync-to-gitee.yml 负责代码镜像，
 *    build.yml 里的 "Publish APK to Gitee Release" 步骤负责把构建产物也上传过去）。
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "changelog": "本次更新内容说明",
 *   "apkUrl": "https://gitee.com/OWNER/REPO/releases/download/v1.1.0/app-1.1.0.apk"
 * }
 *
 * 国内网络访问 GitHub 可能不稳定，因此优先请求 Gitee 上的 version.json，
 * 请求失败（超时/异常/格式错误）时再自动回退到 GitHub，尽量提高检查更新的成功率。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /**
     * GitHub 仓库地址，格式 "OWNER/REPO"。
     * 通过 GitHub 的 /releases/latest/download/ 固定链接拉取 version.json，
     * 每次 push v*.*.* tag 触发 CI 构建发布新 Release 后，该链接自动指向最新版本。
     */
    public static final String GITHUB_REPO = "shovelshit/ScriptRunner";

    /** Gitee 仓库地址，格式 "OWNER/REPO"，与 sync-to-gitee.yml / build.yml 中保持一致 */
    public static final String GITEE_REPO = "aka-ljf/ScriptRunner";

    /** GitHub Release「latest」固定链接下的 version.json，始终指向最新一次发布 */
    private static final String GITHUB_VERSION_JSON_URL =
            "https://github.com/" + GITHUB_REPO + "/releases/latest/download/version.json";

    /** Gitee Release「latest」固定链接下的 version.json，始终指向最新一次发布 */
    private static final String GITEE_VERSION_JSON_URL =
            "https://gitee.com/" + GITEE_REPO + "/releases/latest/download/version.json";

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private UpdateChecker() {
    }

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String changelog;
        public final String apkUrl;

        UpdateInfo(int versionCode, String versionName, String changelog, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.changelog = changelog;
            this.apkUrl = apkUrl;
        }
    }

    public static final class CheckResult {
        public final boolean success;
        public final String message;
        public final boolean hasUpdate;
        public final UpdateInfo updateInfo;

        private CheckResult(boolean success, String message, boolean hasUpdate, UpdateInfo updateInfo) {
            this.success = success;
            this.message = message;
            this.hasUpdate = hasUpdate;
            this.updateInfo = updateInfo;
        }

        static CheckResult fail(String message) {
            return new CheckResult(false, message, false, null);
        }

        static CheckResult noUpdate(String message) {
            return new CheckResult(true, message, false, null);
        }

        static CheckResult hasUpdate(UpdateInfo info) {
            return new CheckResult(true, "发现新版本 " + info.versionName, true, info);
        }
    }

    /**
     * 同步执行网络请求，必须在后台线程调用。
     * 优先请求 Gitee 上的 version.json，失败时自动回退到 GitHub。
     *
     * @param currentVersionCode 当前已安装版本的 versionCode，来自 BuildConfig.VERSION_CODE
     */
    public static CheckResult checkForUpdate(int currentVersionCode) {
        String body;
        try {
            body = doHttpGet(GITEE_VERSION_JSON_URL);
        } catch (IOException giteeError) {
            Log.w(TAG, "从 Gitee 检查更新失败，尝试回退到 GitHub: " + giteeError.getMessage());
            try {
                body = doHttpGet(GITHUB_VERSION_JSON_URL);
            } catch (IOException githubError) {
                String msg = "检查更新失败（网络请求异常）: " + githubError.getMessage();
                Log.w(TAG, msg);
                return CheckResult.fail(msg);
            }
        }

        UpdateInfo remote;
        try {
            JSONObject obj = new JSONObject(body);
            remote = new UpdateInfo(
                    obj.getInt("versionCode"),
                    obj.optString("versionName", ""),
                    obj.optString("changelog", ""),
                    obj.getString("apkUrl"));
        } catch (JSONException e) {
            String msg = "检查更新失败（version.json 格式不合法）: " + e.getMessage();
            Log.w(TAG, msg);
            return CheckResult.fail(msg);
        }

        if (remote.versionCode <= currentVersionCode) {
            return CheckResult.noUpdate("当前已是最新版本（versionCode=" + currentVersionCode + "）");
        }
        return CheckResult.hasUpdate(remote);
    }

    private static String doHttpGet(String urlString) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " " + connection.getResponseMessage());
            }

            try (InputStream is = connection.getInputStream()) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int n;
                    while ((n = reader.read(buffer)) != -1) {
                        sb.append(buffer, 0, n);
                    }
                }
                return sb.toString();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
