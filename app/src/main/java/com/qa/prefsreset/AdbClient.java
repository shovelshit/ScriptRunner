package com.qa.prefsreset;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.concurrent.TimeUnit;

/**
 * 一个极简的 ADB 客户端实现（基于 ADB wire protocol），
 * 用于让 App 进程通过 loopback 连接本机的 adbd 守护进程，
 * 借助 adbd 本身运行在 "shell" 权限域（而非 App 所在的 untrusted_app 域）的特性，
 * 以及 adbd 内部对 "su" 的调用许可，间接实现「无需 App 自身 exec su」也能完成 root 命令执行。
 *
 * 背景（重要）：
 * Android 应用进程运行在 SELinux 的 untrusted_app 域下，即使设备本身是 userdebug/eng
 * 且系统内置了 su 二进制，标准 SELinux 策略也不允许 untrusted_app 域直接 execve
 * su_exec 类型的可执行文件（表现为 Java 层 Runtime.exec("su") 抛出 Permission denied，
 * errno=13）。但 adbd 进程运行在 adbd 域，被系统允许调用 su 切换到 root 身份执行命令
 * （这也是我们平时用 "adb shell su -c ..." 能成功的原因）。
 * 因此让 App 进程连接本机 adbd 的 TCP 端口，走 ADB 协议把 "su ... " 命令转发给 adbd
 * 去执行，就可以绕开 App 自身进程的 SELinux 限制。
 *
 * 端口选取说明：
 * adbd 实际监听的端口由系统属性 service.adb.tcp.port / persist.adb.tcp.port 决定
 * （模拟器默认是 5555，真机开启 `adb tcpip <port>` 后可以是任意端口）。
 * 本类优先读取这两个系统属性拿到「设备真实在用」的端口，避免硬编码端口号
 * 与其他工具/多开模拟器场景产生冲突；读不到时才回退到内置的候选端口列表探测，
 * 且候选列表已避开 5037（PC 端 adb server 自身端口，设备内部不会监听）这类
 * 容易与其他 adb 相关进程冲突的端口，改用较少被占用的高位端口。
 *
 * 仅限内部测试机（userdebug/eng，且已开启 adb tcpip）使用。
 */
public final class AdbClient {

    private static final String TAG = "AdbClient";

    private static final int A_SYNC = 0x434e5953;
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;

    /**
     * 内置候选端口，仅在读取不到系统属性时才会用到（兜底探测）。
     * 5555 是模拟器默认端口，保留以确保模拟器场景零配置可用；
     * 其余改用不常用的高位端口，降低和其他本机服务/多实例 adb 冲突的概率。
     */
    private static final int[] FALLBACK_CANDIDATE_PORTS = new int[]{5555, 47225, 58221};

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    /** 最多尝试几轮 AUTH 签名挑战（部分设备要求先 publickey 再 signature） */
    private static final int MAX_AUTH_ROUNDS = 2;

    /** 通过 {@link #setPreferredPort(Integer)} 显式指定的端口，优先级最高 */
    private static volatile Integer preferredPort;

    /** 最近一次成功连接的 adbd 端口，供 UI 展示 ADB 连接状态时使用。-1 表示尚未成功连接过。 */
    private static volatile int lastConnectedPort = -1;

    private AdbClient() {
    }

    /**
     * 显式指定希望优先尝试的 adbd 端口（例如设备把 adb tcpip 端口改成了非默认值），
     * 传 null 表示恢复自动探测。
     */
    public static void setPreferredPort(Integer port) {
        preferredPort = port;
    }

    /**
     * 返回最近一次成功连接的 adbd 端口号，供 UI 展示连接状态。
     * @return 端口号；-1 表示尚未成功连接过。
     */
    public static int getLastConnectedPort() {
        return lastConnectedPort;
    }

    public static final class AdbResult {
        public final boolean success;
        public final String output;
        public final String error;

        private AdbResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        static AdbResult ok(String output) {
            return new AdbResult(true, output, "");
        }

        static AdbResult fail(String error) {
            return new AdbResult(false, "", error);
        }
    }

    /**
     * 通过本机 adbd 执行一条 shell 命令。
     * 端口尝试顺序：显式指定的 preferredPort &gt; 系统属性读到的真实监听端口 &gt; 内置兜底候选端口。
     */
    public static AdbResult runShellCommand(String command) {
        StringBuilder attemptErrors = new StringBuilder();

        for (int port : resolveCandidatePorts()) {
            AdbResult result = runShellCommandOnPort("127.0.0.1", port, command);
            if (result.success) {
                return result;
            }
            attemptErrors.append("[port ").append(port).append(": ").append(result.error).append("] ");
        }
        return AdbResult.fail("所有端口均连接失败: " + attemptErrors);
    }

    /**
     * 让本机 adbd 以 root 身份重启（等价于 PC 端执行 "adb root"）。
     * 通过 ADB 协议发送 "root:" 服务命令，adbd 收到后会：
     * - 若已是 root：返回 "adbd is already running as root"，连接保持；
     * - 若可提权：返回 "restarting adbd as root"，随后 adbd 重启，当前连接断开；
     * - 若不可提权（production build / ro.debuggable=0）：返回拒绝原因，如
     *   "adbd cannot run as root in production builds"。
     *
     * 注意：调用此方法后，adbd 可能会重启，调用方需要等待几秒再重新连接，
     * 并清除任何关于 adbd root 状态的缓存。
     *
     * @return AdbResult.success=true 表示命令已成功发送并收到响应（不代表 adbd 一定已变成 root，
     *         需根据 output 内容判断；success=false 表示连接/协议层面失败）。
     */
    public static AdbResult restartAdbdAsRoot() {
        return sendServiceCommand("root:");
    }

    /**
     * 让本机 adbd 重新以 rw 挂载 /system、/vendor 等分区（等价于 PC 端执行 "adb remount"）。
     * 通常在 {@link #restartAdbdAsRoot()} 成功后调用，用于让 /system 可写。
     * 非 root 状态下调用会失败（adbd 需先具有 root 权限才能 remount）。
     *
     * @return AdbResult.success=true 表示 remount 命令已发送并收到响应。
     */
    public static AdbResult remountSystem() {
        return sendServiceCommand("remount:");
    }

    /**
     * 发送一个 ADB 服务命令（如 "root:"、"remount:"、"unroot:"）。
     * 与 shell 命令不同，这类服务命令通过 A_OPEN 打开一个服务流，
     * adbd 返回一条简短文本响应后通常会关闭流（或重启导致连接断开）。
     *
     * 端口尝试顺序与 {@link #runShellCommand(String)} 一致。
     */
    private static AdbResult sendServiceCommand(String serviceName) {
        StringBuilder attemptErrors = new StringBuilder();
        for (int port : resolveCandidatePorts()) {
            AdbResult result = sendServiceCommandOnPort("127.0.0.1", port, serviceName);
            if (result.success) {
                return result;
            }
            attemptErrors.append("[port ").append(port).append(": ").append(result.error).append("] ");
        }
        return AdbResult.fail("所有端口均连接失败: " + attemptErrors);
    }

    /**
     * 汇总本次要尝试的端口列表，按优先级去重排列：
     * 1. App 显式设置的 preferredPort；
     * 2. 从系统属性 service.adb.tcp.port / persist.adb.tcp.port 读到的、设备当前真实监听的端口；
     * 3. 内置兜底候选端口（{@link #FALLBACK_CANDIDATE_PORTS}）。
     */
    private static int[] resolveCandidatePorts() {
        java.util.LinkedHashSet<Integer> ports = new java.util.LinkedHashSet<>();

        Integer preferred = preferredPort;
        if (preferred != null && preferred > 0) {
            ports.add(preferred);
        }

        Integer devicePort = readDeviceAdbTcpPort();
        if (devicePort != null && devicePort > 0) {
            ports.add(devicePort);
        }

        for (int p : FALLBACK_CANDIDATE_PORTS) {
            ports.add(p);
        }

        int[] result = new int[ports.size()];
        int i = 0;
        for (int p : ports) {
            result[i++] = p;
        }
        return result;
    }

    /**
     * 通过反射读取系统属性 service.adb.tcp.port / persist.adb.tcp.port，
     * 拿到设备上 adbd 真实在监听的端口号（比盲猜端口更准确，也是避免端口冲突的根本手段）。
     * 读取失败（反射不可用、属性未设置等）时返回 null，由调用方回退到候选端口探测。
     */
    private static Integer readDeviceAdbTcpPort() {
        String fromServiceProp = getSystemProperty("service.adb.tcp.port");
        Integer parsed = parsePositivePort(fromServiceProp);
        if (parsed != null) {
            return parsed;
        }
        String fromPersistProp = getSystemProperty("persist.adb.tcp.port");
        return parsePositivePort(fromPersistProp);
    }

    private static Integer parsePositivePort(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method method = clazz.getMethod("get", String.class);
            Object result = method.invoke(null, key);
            return result == null ? null : result.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static AdbResult runShellCommandOnPort(String host, int port, String command) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            if (!performHandshake(out, in)) {
                return AdbResult.fail("adb 握手失败（可能需要 RSA 授权或设备未开启 adb tcpip）");
            }
            lastConnectedPort = port;

            return openShellStream(out, in, command);
        } catch (IOException e) {
            return AdbResult.fail(String.valueOf(e.getMessage()));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 在指定端口上发送 ADB 服务命令（root: / remount: / unroot: 等）。
     *
     * 与 shell 命令的区别：
     * 1. service 名直接作为 A_OPEN 的 payload（不带 "shell,raw:" 前缀）；
     * 2. 服务命令的响应通常很短（一行文本），读取到 A_CLSE 或连接断开即可；
     * 3. 对于 root: / unroot: 命令，adbd 会重启导致连接被强制关闭，
     *    此时若已经读到部分响应，仍视为成功（success=true），调用方根据 output 内容判断结果。
     */
    private static AdbResult sendServiceCommandOnPort(String host, int port, String serviceName) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            // 服务命令响应很快，但 adbd 重启时可能在读取过程中断开连接，
            // 用较短的 timeout 让「连接被对方关闭」时尽快返回，避免长时间阻塞。
            socket.setSoTimeout(8000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            if (!performHandshake(out, in)) {
                return AdbResult.fail("adb 握手失败（可能需要 RSA 授权或设备未开启 adb tcpip）");
            }
            lastConnectedPort = port;

            return openServiceStream(out, in, serviceName);
        } catch (IOException e) {
            return AdbResult.fail(String.valueOf(e.getMessage()));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 完成 CNXN 握手；若设备要求 AUTH，则尝试用本地生成/持久化的 RSA 密钥对完成签名挑战。
     */
    private static boolean performHandshake(DataOutputStream out, DataInputStream in) throws IOException {
        byte[] identity = "host::features=cmd,shell_v2".getBytes(StandardCharsets.UTF_8);
        sendMessage(out, A_CNXN, VERSION, MAX_PAYLOAD, identity);

        Message resp = readMessage(in);
        int round = 0;
        while (resp.command == A_AUTH && round < MAX_AUTH_ROUNDS) {
            round++;
            if (resp.arg0 != AUTH_TOKEN) {
                Log.w(TAG, "unexpected AUTH arg0=" + resp.arg0);
                return false;
            }
            byte[] token = resp.data;
            KeyPair keyPair = AdbKeyManager.getOrCreateKeyPair();
            byte[] signature = signToken(keyPair.getPrivate(), token);

            sendMessage(out, A_AUTH, AUTH_SIGNATURE, 0, signature);
            resp = readMessage(in);
            if (resp.command == A_CNXN) {
                return true;
            }
            if (resp.command == A_AUTH) {
                // 签名失败，尝试把公钥发送过去（部分设备首次连接需要先注册公钥，用户需在设备上手动确认弹窗，
                // 但本机 loopback 场景一般不会弹窗，也可能对方直接拒绝，此时降级失败）
                byte[] publicKeyBytes = AdbKeyManager.getAdbPublicKeyBytes(keyPair);
                sendMessage(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes);
                resp = readMessage(in);
                if (resp.command == A_CNXN) {
                    return true;
                }
            }
        }
        return resp.command == A_CNXN;
    }

    private static AdbResult openShellStream(DataOutputStream out, DataInputStream in, String command)
            throws IOException {
        int localId = 1;
        // "shell,raw:" 前缀表示不分配 pty、原样返回 stdout+stderr 合并流，适合脚本化执行
        String service = "shell,raw:" + command;
        byte[] serviceBytes = (service + "\u0000").getBytes(StandardCharsets.UTF_8);
        sendMessage(out, A_OPEN, localId, 0, serviceBytes);

        Message openResp = readMessage(in);
        if (openResp.command != A_OKAY) {
            return AdbResult.fail("打开 shell 流失败: cmd=" + cmdName(openResp.command));
        }
        int remoteId = openResp.arg0;

        StringBuilder output = new StringBuilder();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            Message m = readMessage(in);
            if (m.command == A_WRTE) {
                output.append(new String(m.data, StandardCharsets.UTF_8));
                sendMessage(out, A_OKAY, localId, remoteId, new byte[0]);
            } else if (m.command == A_CLSE) {
                break;
            } else {
                Log.w(TAG, "unexpected message while reading shell stream: " + cmdName(m.command));
            }
        }
        return AdbResult.ok(output.toString());
    }

    /**
     * 打开一个 ADB 服务流（root: / remount: / unroot: 等）并读取响应。
     *
     * 与 shell 流的区别：
     * 1. service 名直接作为 A_OPEN payload（不带 "shell,raw:" 前缀，末尾仍需 \0 结束符）；
     * 2. 服务命令响应通常只有一行文本，读取到 A_CLSE 即结束；
     * 3. root:/unroot: 会让 adbd 重启，连接可能在读取过程中被对方强制关闭（IOException），
     *    此时若已读到部分响应，仍视为成功，调用方根据 output 判断实际结果。
     */
    private static AdbResult openServiceStream(DataOutputStream out, DataInputStream in, String serviceName) {
        int localId = 2; // 与 openShellStream 区分开，便于抓包排查
        byte[] serviceBytes = (serviceName + "\u0000").getBytes(StandardCharsets.UTF_8);
        try {
            sendMessage(out, A_OPEN, localId, 0, serviceBytes);
        } catch (IOException e) {
            return AdbResult.fail("发送服务命令失败: " + e.getMessage());
        }

        StringBuilder output = new StringBuilder();
        try {
            Message openResp = readMessage(in);
            if (openResp.command != A_OKAY) {
                // adbd 拒绝了该服务（如 production build 拒绝 root:），部分设备会把拒绝原因放在 A_CLSE 前
                if (openResp.command == A_CLSE) {
                    return AdbResult.ok(""); // 服务被拒绝，返回空成功响应，调用方按空输出判断
                }
                return AdbResult.fail("打开服务流失败: cmd=" + cmdName(openResp.command));
            }
            int remoteId = openResp.arg0;

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            while (System.currentTimeMillis() < deadline) {
                Message m = readMessage(in);
                if (m.command == A_WRTE) {
                    output.append(new String(m.data, StandardCharsets.UTF_8));
                    try {
                        sendMessage(out, A_OKAY, localId, remoteId, new byte[0]);
                    } catch (IOException ignored) {
                        // adbd 重启可能导致 ack 写入失败，但响应已读到，继续退出
                        break;
                    }
                } else if (m.command == A_CLSE) {
                    break;
                } else {
                    Log.w(TAG, "unexpected message while reading service stream: " + cmdName(m.command));
                }
            }
        } catch (IOException e) {
            // adbd 重启（root:/unroot:）会导致连接断开，这是预期行为。
            // 若已读到响应内容（如 "restarting adbd as root"），仍视为成功。
            if (output.length() == 0) {
                return AdbResult.fail("读取服务响应时连接断开: " + e.getMessage());
            }
            Log.i(TAG, "服务流连接断开（adbd 可能正在重启）: " + e.getMessage());
        }
        return AdbResult.ok(output.toString());
    }

    private static byte[] signToken(PrivateKey privateKey, byte[] token) {
        try {
            // ADB 的签名协议使用裸 RSA（PKCS#1 v1.5 padding，无摘要算法前缀），
            // 因此不能直接用 "SHA256withRSA" 之类的 Signature，需要用 NONEwithRSA 对
            // 已经按 PKCS#1 规则填充好的数据签名。这里退化为标准 RSA/ECB/PKCS1Padding 加密方式，
            // 等价于对 token 做 RSA 私钥运算。
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey);
            return cipher.doFinal(token);
        } catch (Exception e) {
            Log.e(TAG, "sign token failed", e);
            return new byte[0];
        }
    }

    private static String cmdName(int cmd) {
        if (cmd == A_SYNC) return "SYNC";
        if (cmd == A_CNXN) return "CNXN";
        if (cmd == A_OPEN) return "OPEN";
        if (cmd == A_OKAY) return "OKAY";
        if (cmd == A_CLSE) return "CLSE";
        if (cmd == A_WRTE) return "WRTE";
        if (cmd == A_AUTH) return "AUTH";
        return "UNK(0x" + Integer.toHexString(cmd) + ")";
    }

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        int dataLength;
        byte[] data;
    }

    private static void sendMessage(DataOutputStream out, int command, int arg0, int arg1, byte[] data)
            throws IOException {
        int dataLength = data.length;
        int dataCheck = 0;
        for (byte b : data) {
            dataCheck += (b & 0xFF);
        }
        int magic = command ^ 0xFFFFFFFF;

        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(dataLength);
        header.putInt(dataCheck);
        header.putInt(magic);
        out.write(header.array());
        if (dataLength > 0) {
            out.write(data);
        }
        out.flush();
    }

    private static Message readMessage(DataInputStream in) throws IOException {
        byte[] headerBytes = new byte[24];
        in.readFully(headerBytes);
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        m.dataLength = header.getInt();
        header.getInt(); // dataCheck，读取但不校验
        header.getInt(); // magic，读取但不校验
        m.data = new byte[m.dataLength];
        if (m.dataLength > 0) {
            in.readFully(m.data);
        }
        return m;
    }
}
