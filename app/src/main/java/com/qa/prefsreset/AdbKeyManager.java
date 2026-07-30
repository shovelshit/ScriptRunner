package com.qa.prefsreset;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.nio.charset.StandardCharsets;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * 管理本 App 用于 ADB AUTH 握手的 RSA 密钥对。
 *
 * 大多数 userdebug/eng 测试机会把 ro.adb.secure 设为 0（不校验来源，见 {@link AdbClient} 的说明），
 * 此时根本不会走到 AUTH 流程；但为了在少数开启了 secure 校验的设备上也能尽量成功，
 * 这里补充实现了标准 RSA 密钥生成 + 持久化，供 {@link AdbClient} 在需要时使用。
 *
 * 注意：即使签名正确，adbd 首次连接一个陌生公钥时通常还会在设备屏幕上弹出「是否允许调试」的
 * 授权对话框，需要人工点击确认，无法被 App 绕过。因此该路径在无人值守场景下
 * 不一定能自动成功，属于「尽力而为」的增强。
 */
final class AdbKeyManager {

    private static final String TAG = "AdbKeyManager";
    private static final String KEY_DIR_NAME = "adb_keys";
    private static final String PRIVATE_KEY_FILE = "adbkey";
    private static final String RSA_KEY_ALGORITHM = "RSA";
    private static final int RSA_KEY_SIZE = 2048;

    private static volatile KeyPair cachedKeyPair;
    private static File keyDir;

    private AdbKeyManager() {
    }

    /** 需要在使用前调用一次，传入 App 私有目录，用于持久化密钥，避免每次都重新生成导致设备端重复弹授权框 */
    static synchronized void init(File appPrivateDir) {
        keyDir = new File(appPrivateDir, KEY_DIR_NAME);
        if (!keyDir.exists()) {
            keyDir.mkdirs();
        }
    }

    static synchronized KeyPair getOrCreateKeyPair() {
        if (cachedKeyPair != null) {
            return cachedKeyPair;
        }
        if (keyDir != null) {
            KeyPair loaded = tryLoadFromDisk();
            if (loaded != null) {
                cachedKeyPair = loaded;
                return cachedKeyPair;
            }
        }
        KeyPair generated = generateKeyPair();
        if (keyDir != null) {
            trySaveToDisk(generated);
        }
        cachedKeyPair = generated;
        return cachedKeyPair;
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_KEY_ALGORITHM);
            generator.initialize(RSA_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not supported", e);
        }
    }

    private static File privateKeyFile() {
        return new File(keyDir, PRIVATE_KEY_FILE);
    }

    private static KeyPair tryLoadFromDisk() {
        File file = privateKeyFile();
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = fis.read(bytes);
            if (read != bytes.length) {
                return null;
            }
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_KEY_ALGORITHM);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(spec);

            RSAPublicKeySpec publicSpec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
            java.security.PublicKey publicKey = keyFactory.generatePublic(publicSpec);
            return new KeyPair(publicKey, privateKey);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | ClassCastException e) {
            Log.w(TAG, "load adb key from disk failed, will regenerate: " + e.getMessage());
            return null;
        }
    }

    private static void trySaveToDisk(KeyPair keyPair) {
        try (FileOutputStream fos = new FileOutputStream(privateKeyFile())) {
            fos.write(keyPair.getPrivate().getEncoded());
        } catch (IOException e) {
            Log.w(TAG, "save adb key to disk failed: " + e.getMessage());
        }
    }

    /**
     * 生成 ADB 协议要求的公钥二进制格式（adb_pkt 里 AUTH_RSAPUBLICKEY 使用的格式，
     * 即 Android 特有的 "ADB RSA public key" 结构，而非标准 X.509 DER）。
     *
     * 该结构定义可参考 AOSP system/core/libcrypto_utils/android_pubkey.c，
     * 由固定长度的模数、n0inv、rr 等字段拼接，再做 Base64，末尾追加 " user@host" 注释。
     */
    static byte[] getAdbPublicKeyBytes(KeyPair keyPair) {
        try {
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            byte[] androidPubkeyStruct = encodeAndroidPubkey(publicKey);
            String base64 = Base64.encodeToString(androidPubkeyStruct, Base64.NO_WRAP);
            String withComment = base64 + " script-runner@device";
            return withComment.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "encode adb public key failed", e);
            return new byte[0];
        }
    }

    /**
     * 按 AOSP android_pubkey 格式编码 RSA 公钥：
     * struct RSAPublicKey {
     *   uint32_t modulus_size_words; // = RSANUMWORDS (每 word 4 字节)
     *   uint32_t n0inv;              // -1 / n[0] mod 2^32
     *   uint8_t modulus[RSANUMBYTES];
     *   uint8_t rr[RSANUMBYTES];     // R^2 mod n
     *   uint32_t exponent;
     * }
     */
    private static byte[] encodeAndroidPubkey(RSAPublicKey publicKey) {
        BigInteger n = publicKey.getModulus();
        int modulusBits = n.bitLength();
        int words = (modulusBits + 31) / 32;
        int modulusBytes = words * 4;

        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.mod(r32);
        BigInteger n0inv = n0.modInverse(r32);
        BigInteger negN0inv = r32.subtract(n0inv).mod(r32);

        BigInteger r = BigInteger.ONE.shiftLeft(modulusBytes * 8);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + modulusBytes + modulusBytes + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(words);
        buffer.putInt(negN0inv.intValue());
        putLittleEndianBigInteger(buffer, n, modulusBytes);
        putLittleEndianBigInteger(buffer, rr, modulusBytes);
        buffer.putInt(publicKey.getPublicExponent().intValue());
        return buffer.array();
    }

    private static void putLittleEndianBigInteger(ByteBuffer buffer, BigInteger value, int length) {
        byte[] bigEndian = toFixedLengthBigEndian(value, length);
        for (int i = length - 1; i >= 0; i--) {
            buffer.put(bigEndian[i]);
        }
    }

    private static byte[] toFixedLengthBigEndian(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[length];
        int copyLength = Math.min(length, raw.length);
        int rawStart = raw.length - copyLength;
        int resultStart = length - copyLength;
        System.arraycopy(raw, rawStart, result, resultStart, copyLength);
        return result;
    }
}
