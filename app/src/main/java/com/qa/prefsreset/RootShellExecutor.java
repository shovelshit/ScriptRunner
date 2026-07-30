package com.qa.prefsreset;

import android.util.Log;

/**
 * 封装以 root 身份执行 shell 命令的能力。
 *
 * 实现方式说明：Android 应用进程运行在 SELinux 的 untrusted_app 域下，即使是
 * userdebug/eng 镜像，标准 SELinux 策略也不允许该域直接 execve su_exec
 * 可执行文件（Java 层 Runtime.exec("su") 会报 Permission denied, errno=13），
 * 这是系统级安全限制，无法从 App 代码层面绕过。
 *
 * 因此改为让 App 进程通过 loopback 网络连接本机 adbd 守护进程（{@link AdbClient}），
 * 由 adbd 代为执行命令。支持以下 root 获取路径（按优先级自动尝试）：
 * 1. adbd 本身已是 root：直接执行命令，无需 su 前缀。
 *    适用于部分模拟器（如 LDPlayer）已通过 adb root 让 adbd 运行在 root 域、
 *    但系统内没有 su 二进制的场景。
 * 2. 主动提权 - root: 服务命令：若 adbd 当前不是 root，且 ro.debuggable=1，
 *    通过 ADB 协议发送 "root:" 服务命令让 adbd 重启为 root 模式。
 *    适用于 userdebug/eng 构建或模拟器 root 开关已开启但 adbd 尚未提权的场景。
 * 3. 主动提权 - setprop 属性注入：若 root: 命令被拒绝（ro.debuggable=0），
 *    通过 ADB shell 执行 setprop ro.debuggable 1 + setprop service.adb.root 1
 *    + setprop ctl.restart adbd 让 adbd 以 root 重启。
 *    适用于部分模拟器（如 LDPlayer），其 property_service 允许 shell 用户设置
 *    ro.debuggable 和 service.adb.root 属性。这是从第三方提权工具逆向学到的做法。
 * 4. 传统 su 模式：adbd 运行在 shell 域，通过 "su root sh -c '...'" 切到 root。
 *    适用于 userdebug/eng 镜像且系统内置 su 的设备。
 *
 * 使用前提：
 * 1. 设备已开启 adb 网络调试（模拟器默认即是；真机需要 `adb tcpip <port>`）；
 * 2. adbd 本身已是 root（路径一/二），或设备是 userdebug/eng 镜像且存在可用的 su（路径三）。
 *
 * 仅限内部测试机使用，请勿在生产 / 用户设备上使用本类。
 */
public final class RootShellExecutor {

    private static final String TAG = "RootShellExecutor";

    /**
     * adbd 是否本身就是 root。null=尚未检测，true=已是 root（直接执行），false=需要 su 前缀。
     * 进程级缓存：adbd 的 root 状态在一次 app 生命周期内不会变化（只有重启或主动 adb root 才会改变）。
     * 注意：调用 {@link #tryElevateAdbdToRoot()} 后会主动清除此缓存，以便重新检测。
     */
    private static volatile Boolean adbdAlreadyRoot = null;

    /** 最近一次 root 检测的详细信息，供调用方写入执行日志。 */
    private static volatile String lastDetectionDetail = null;

    /**
     * 提权方式枚举，标识最近一次 {@link #isRootAvailable()} 成功获取 root 所用的路径。
     * 供 UI 展示提权状态时使用。
     */
    public enum ElevationMethod {
        /** 未检测或 root 不可用 */
        NONE,
        /** adbd 本身已是 root，无需提权 */
        ALREADY_ROOT,
        /** 通过 root: 服务命令让 adbd 以 root 重启 */
        ROOT_SERVICE,
        /** 通过 setprop 属性注入让 adbd 以 root 重启（模拟器专用） */
        SETPROP,
        /** 通过 su 命令获取 root（传统方式） */
        SU
    }

    /** 最近一次成功获取 root 所用的提权方式。 */
    private static volatile ElevationMethod lastElevationMethod = ElevationMethod.NONE;
    /** 最近一次检测时 ADB 是否连接成功。 */
    private static volatile boolean lastAdbConnected = false;

    /** adbd 重启后需要等待其重新就绪的休眠时间（毫秒）。经验值，覆盖模拟器/真机重启耗时。 */
    private static final long ADBD_RESTART_WAIT_MS = 3000;
    /** adbd 重启后重连探测的最大次数。 */
    private static final int ADBD_RESTART_MAX_PROBES = 3;
    /** 每次重连探测之间的间隔（毫秒）。 */
    private static final long ADBD_RESTART_PROBE_INTERVAL_MS = 1000;

    private RootShellExecutor() {
    }

    public static final class ShellResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        ShellResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * 检测当前设备是否可以通过本机 adbd 拿到 root 权限。
     * 按以下优先级依次尝试，任一成功即返回 true：
     * 1. adbd 本身已是 root（无需 su 二进制，无需主动提权）；
     * 2. 主动提权：先尝试 root: 服务命令，失败再尝试 setprop 属性注入，
     *    让 adbd 重启为 root，然后重新检测；
     * 3. 通过 su 命令获取 root（传统方式，需设备内置 su 二进制）。
     *
     * 检测结果会记录到 {@link #lastDetectionDetail}，可通过 {@link #getLastDetectionDetail()} 获取。
     */
    public static boolean isRootAvailable() {
        lastElevationMethod = ElevationMethod.NONE;

        // 路径一：adbd 本身已是 root（adb root 已执行，无需 su 二进制）
        if (detectAdbdRoot()) {
            lastElevationMethod = ElevationMethod.ALREADY_ROOT;
            lastDetectionDetail = "adbd 本身已是 root（uid=0），无需 su 前缀";
            return true;
        }

        // 路径二：主动提权——发送 adb root 让 adbd 重启为 root 模式，然后重新检测。
        // 仅在 adbd 当前非 root 且 su 不可用时尝试（避免在已有 su 的设备上做无谓的 adbd 重启）。
        if (tryElevateAdbdToRoot()) {
            // 提权后 adbd 已重启，detectAdbdRoot 内部会重新探测并刷新缓存
            if (detectAdbdRoot()) {
                // lastElevationMethod 已在 tryElevateAdbdToRoot 内部设置
                lastDetectionDetail = "通过 adb root 主动提权成功，adbd 已切换为 root（uid=0）";
                return true;
            }
            lastElevationMethod = ElevationMethod.NONE;
            lastDetectionDetail = "adb root 命令已发送，但重新检测仍非 root（设备可能禁止提权）";
        }

        // 路径三：通过 su 获取 root（传统方式，需设备内置 su 二进制）
        ShellResult result = execViaSu(new String[]{"id"});
        if (result.isSuccess() && result.stdout.contains("uid=0")) {
            lastElevationMethod = ElevationMethod.SU;
            lastDetectionDetail = "通过 su 获取 root 权限成功";
            return true;
        }
        String trimmedErr = result.stderr.trim();
        lastDetectionDetail = "root 不可用: adbd 非 root 且 su 不可用"
                + (trimmedErr.isEmpty() ? "" : "（" + trimmedErr + "）");
        return false;
    }

    /**
     * 返回最近一次 {@link #isRootAvailable()} 的检测详情，供调用方记录日志。
     */
    public static String getLastDetectionDetail() {
        return lastDetectionDetail != null ? lastDetectionDetail : "尚未执行 root 检测";
    }

    /**
     * 返回最近一次成功获取 root 所用的提权方式，供 UI 展示。
     */
    public static ElevationMethod getLastElevationMethod() {
        return lastElevationMethod;
    }

    /**
     * 返回最近一次检测时 ADB 是否连接成功，供 UI 展示。
     */
    public static boolean isLastAdbConnected() {
        return lastAdbConnected;
    }

    /**
     * 检测 adbd 本身是否已经是 root。
     * 直接通过 adb shell 执行 id（不加 su 前缀），检查输出是否包含 uid=0。
     * 结果会缓存到 {@link #adbdAlreadyRoot}，主动提权后会清除缓存触发重新探测。
     */
    private static boolean detectAdbdRoot() {
        if (adbdAlreadyRoot != null) {
            return adbdAlreadyRoot;
        }
        AdbClient.AdbResult result = AdbClient.runShellCommand("id; echo EXIT_CODE:$?");
        if (result.success) {
            lastAdbConnected = true;
            ShellResult parsed = parseResultWithExitCode(result.output);
            adbdAlreadyRoot = parsed.isSuccess() && parsed.stdout.contains("uid=0");
        } else {
            lastAdbConnected = false;
            adbdAlreadyRoot = false;
        }
        return adbdAlreadyRoot;
    }

    /**
     * 主动让 adbd 以 root 身份重启。按优先级尝试两种提权方式：
     *
     * 方式一（root: 服务命令）：通过 ADB 协议发送 "root:" 服务命令。
     *   适用于 ro.debuggable=1 的设备（userdebug/eng 构建，或模拟器 root 开启时）。
     *   若 ro.debuggable=0，adbd 会拒绝并返回 "adbd cannot run as root in production builds"。
     *
     * 方式二（setprop 属性注入）：通过 ADB shell 执行 setprop 修改系统属性后重启 adbd。
     *   流程：setprop ro.debuggable 1 → setprop service.adb.root 1 → setprop ctl.restart adbd
     *   适用于部分模拟器（如 LDPlayer），这些模拟器的 property_service 允许 shell 用户
     *   设置 ro.debuggable 和 service.adb.root 属性（普通 Android 设备不允许）。
     *   这是从第三方提权工具（软件管家 com.yunpan.appmanage）逆向分析学到的做法。
     *
     * 本方法只负责「发送命令并等待 adbd 就绪」，不负责验证是否真的变成 root
     * （验证由调用方在清除缓存后重新调用 {@link #detectAdbdRoot()} 完成）。
     *
     * @return true 表示已成功让 adbd 重启并重新就绪（或本就是 root）；
     *         false 表示两种提权方式均失败。
     */
    private static boolean tryElevateAdbdToRoot() {
        // 方式一：通过 root: 服务命令提权
        if (tryElevateViaRootService()) {
            lastElevationMethod = ElevationMethod.ROOT_SERVICE;
            return true;
        }

        // 方式二：通过 setprop 属性注入提权（模拟器专用）
        if (tryElevateViaSetprop()) {
            lastElevationMethod = ElevationMethod.SETPROP;
            return true;
        }

        return false;
    }

    /**
     * 方式一：通过 ADB 协议发送 "root:" 服务命令让 adbd 以 root 重启。
     */
    private static boolean tryElevateViaRootService() {
        AdbClient.AdbResult result = AdbClient.restartAdbdAsRoot();
        if (!result.success) {
            Log.w(TAG, "发送 adb root 命令失败: " + result.error);
            return false;
        }

        String output = result.output == null ? "" : result.output.toLowerCase();
        Log.i(TAG, "adb root 响应: " + result.output);

        // adbd 返回 "adbd is already running as root" —— 无需重启，直接视为成功
        if (output.contains("already")) {
            adbdAlreadyRoot = null;
            return true;
        }

        // adbd 返回 "restarting adbd as root" —— 会重启，需要等待并重新连接
        if (output.contains("restarting")) {
            adbdAlreadyRoot = null;
            return waitForAdbdRestart();
        }

        // 其他响应（如 "adbd cannot run as root in production builds"）表示设备拒绝提权
        Log.w(TAG, "root: 服务命令被拒绝（可能 ro.debuggable=0）: " + result.output);
        return false;
    }

    /**
     * 方式二：通过 setprop 注入属性让 adbd 以 root 重启（模拟器专用）。
     *
     * 部分模拟器（如 LDPlayer）的 property_service 允许 shell 用户设置
     * ro.debuggable 和 service.adb.root 属性。利用这一点，可以：
     * 1. setprop ro.debuggable 1   —— 让 adbd 认为是可调试构建
     * 2. setprop service.adb.root 1 —— 要求 adbd 以 root 启动
     * 3. setprop ctl.restart adbd   —— 触发 adbd 重启
     * adbd 重启后会读取上述属性，以 root 模式启动。
     *
     * 注意：这种方式在普通 Android 设备上会失败（property_service 拒绝 shell 用户
     * 设置这些属性），因此仅作为 root: 服务命令失败时的回退方案。
     */
    private static boolean tryElevateViaSetprop() {
        Log.i(TAG, "尝试 setprop 属性注入提权方式（模拟器专用）");

        // 将三条 setprop 命令合并为一条，通过 ADB shell 执行。
        // 用 && 连接确保前一条成功才执行下一条；ctl.restart 会导致 adbd 重启，
        // 连接会断开，因此最后一条命令的返回值可能拿不到，这是预期行为。
        String setpropCommand = "setprop ro.debuggable 1"
                + " && setprop service.adb.root 1"
                + " && setprop ctl.restart adbd";

        AdbClient.AdbResult result = AdbClient.runShellCommand(setpropCommand);
        // ctl.restart adbd 会导致连接断开，result.success 可能为 false（IOException），
        // 这是预期行为，不作为失败依据。只要命令已发送即可。
        Log.i(TAG, "setprop 命令已发送"
                + (result.success ? "，响应: " + result.output : "（连接已断开，预期行为）"));

        // 清除缓存：adbd 重启后 root 状态会变化
        adbdAlreadyRoot = null;
        return waitForAdbdRestart();
    }

    /**
     * 等待 adbd 重启完成并重新可连接。
     * 重启期间连接会失败，需要多次探测直到能成功执行一条命令。
     */
    private static boolean waitForAdbdRestart() {
        try {
            Thread.sleep(ADBD_RESTART_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        // 多次探测，覆盖 adbd 重启耗时的波动
        for (int i = 0; i < ADBD_RESTART_MAX_PROBES; i++) {
            AdbClient.AdbResult probe = AdbClient.runShellCommand("echo adb_ready");
            if (probe.success && probe.output.contains("adb_ready")) {
                return true;
            }
            try {
                Thread.sleep(ADBD_RESTART_PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "adbd 重启后多次探测均无法连接");
        return false;
    }

    /**
     * 依次执行多条 shell 命令（在同一个 root shell 会话中），
     * 任意一条失败都会立即中断并返回失败结果。
     * 自动根据 {@link #detectAdbdRoot()} 结果决定是否使用 su 前缀。
     */
    public static ShellResult execCommands(String[] commands, long timeoutSeconds) {
        return exec(commands);
    }

    private static ShellResult exec(String[] commands) {
        String joinedCommand = joinWithAnd(commands);
        // 末尾追加 "; echo EXIT_CODE:$?" 用于从合并输出流中提取真实退出码
        // （AdbClient 的 shell,raw 通道只返回 stdout+stderr 合并文本，没有独立的退出码字段）。
        String fullCommand;
        if (detectAdbdRoot()) {
            // adbd 已是 root，直接执行，不加 su 前缀
            fullCommand = joinedCommand + "; echo EXIT_CODE:$?";
        } else {
            // 需要 su 前缀切换到 root
            fullCommand = "su root sh -c " + shellQuote(joinedCommand) + "; echo EXIT_CODE:$?";
        }
        return executeViaAdb(fullCommand);
    }

    /**
     * 使用 su 前缀执行命令（传统方式，仅用于 root 检测的二次验证）。
     */
    private static ShellResult execViaSu(String[] commands) {
        String joinedCommand = joinWithAnd(commands);
        String fullCommand = "su root sh -c " + shellQuote(joinedCommand) + "; echo EXIT_CODE:$?";
        return executeViaAdb(fullCommand);
    }

    private static ShellResult executeViaAdb(String fullCommand) {
        AdbClient.AdbResult result = AdbClient.runShellCommand(fullCommand);
        if (!result.success) {
            Log.e(TAG, "adb exec failed: " + result.error);
            return new ShellResult(-1, "", "通过本机 adb 执行失败: " + result.error);
        }
        return parseResultWithExitCode(result.output);
    }

    /**
     * 从合并输出中解析出末尾追加的 "EXIT_CODE:<n>" 标记，还原出真实退出码与业务输出内容。
     */
    private static ShellResult parseResultWithExitCode(String rawOutput) {
        String marker = "EXIT_CODE:";
        int idx = rawOutput.lastIndexOf(marker);
        if (idx < 0) {
            // 没有拿到退出码标记，可能命令本身没跑起来（比如 su 都没执行成功），
            // 保守起见视为失败，把原始输出放进 stderr 方便排查。
            return new ShellResult(-1, rawOutput, rawOutput);
        }

        String businessOutput = rawOutput.substring(0, idx);
        String exitCodeStr = rawOutput.substring(idx + marker.length()).trim();
        int exitCode;
        try {
            exitCode = Integer.parseInt(exitCodeStr);
        } catch (NumberFormatException e) {
            exitCode = -1;
        }

        if (exitCode == 0) {
            return new ShellResult(0, businessOutput, "");
        }
        return new ShellResult(exitCode, businessOutput, businessOutput);
    }

    private static String joinWithAnd(String[] commands) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commands.length; i++) {
            if (i > 0) {
                sb.append(" && ");
            }
            sb.append(commands[i]);
        }
        return sb.toString();
    }

    /**
     * 用单引号包裹命令字符串，作为 "sh -c '<...>'" 的参数，
     * 并对字符串内部原本存在的单引号做转义（'\'' 技巧）。
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
