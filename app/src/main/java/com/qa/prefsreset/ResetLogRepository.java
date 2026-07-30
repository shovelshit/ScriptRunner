package com.qa.prefsreset;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 记录每一次重置任务的执行结果，方便事后查看是否成功、失败原因。
 * 日志会展示在 App 内 UI（明文，仅存于内存）、输出到 logcat，
 * 但落盘到 /sdcard/qa_prefs_reset/logs/ 的文本文件会按行做 base64 编码。
 *
 * 之所以磁盘文件需要编码：执行日志里通常包含真实业务包名、完整 shell 命令等敏感信息，
 * 若明文落在外部存储容易被 grep 直接搜出，与 {@link ResetConfigLoader} 里
 * .json.b64 密文脚本的隐私保护目标一致。编码后每行日志内容形如 "[时间戳] <base64密文>"，
 * 查看时可用 base64 -d 还原明文，App 内界面展示不受影响（走内存明文）。
 */
public final class ResetLogRepository {

    private static final String TAG = "ScriptRunner";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
    private static final SimpleDateFormat FILE_NAME_FORMAT =
            new SimpleDateFormat("yyyyMMdd", Locale.CHINA);

    private static final List<String> MEMORY_LOGS = new ArrayList<>();

    private ResetLogRepository() {
    }

    public static synchronized void log(String message) {
        String line = "[" + TIME_FORMAT.format(new Date()) + "] " + message;
        Log.i(TAG, line);
        MEMORY_LOGS.add(line);
        appendToFile(line);
    }

    public static synchronized List<String> getRecentLogs() {
        return new ArrayList<>(MEMORY_LOGS);
    }

    private static void appendToFile(String line) {
        try {
            File dir = new File(ResetConfigLoader.getConfigDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File logFile = new File(dir, "reset_" + FILE_NAME_FORMAT.format(new Date()) + ".log");
            String encoded = Base64.encodeToString(line.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(encoded);
                writer.write("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "write log file failed", e);
        }
    }
}
