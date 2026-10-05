package com.phonlynn.oreplan.domain.sync.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端对象的加解密：**AES-256-GCM**。
 *
 * ## 为什么是 GCM 而不是 CBC
 *
 * GCM 是**认证加密**（AEAD）：密文自带完整性校验，被改一位就解不开。
 * CBC 只保密、不防篡改 —— 攻击者（或一次传输损坏）改出来的密文仍能"解密成功"，
 * 只是内容是垃圾。对同步来说那比报错更糟：垃圾会**静默写进本地数据库**。
 *
 * ## 每个对象独立的 nonce（最重要的一条纪律）
 *
 * GCM 的 nonce **绝不能在同一把密钥下重复**。重复的后果不是"加密变弱"，
 * 而是**灾难性失效**：攻击者能据此还原出认证密钥、伪造任意密文。
 *
 * 所以：
 *  · 每次加密都**新生成** 12 字节随机 nonce（GCM 的标准长度）；
 *  · nonce 随密文一起存（它不是秘密），格式为 `[12 字节 nonce][密文+GCM tag]`；
 *  · **绝不做"固定 nonce + 递增"** 这类优化 —— 计数器一旦回退（重装、恢复备份）
 *    就会立刻造成 nonce 复用。
 *
 * 12 字节随机 nonce 在单人多设备的量级下，碰撞概率可忽略（生日界约 2^48 次加密）。
 *
 * ## 主密钥从哪来
 *
 * 由用户生成/保管（见 `SyncKeyManager`）。它**不在这个类里** ——
 * 本类是纯函数式的密码学原语，便于单独测试。
 */
object SyncCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    const val KEY_BYTES = 32 // AES-256

    /** GCM 认证标签长度（字节）。用于判断密文是否短到非法。 */
    private const val TAG_BYTES = TAG_BITS / 8

    /**
     * 加密。
     *
     * @param aad 附加认证数据（不被加密、但参与完整性校验）。
     *   这里传**对象的 key** —— 于是把密文从 A 对象挪到 B 对象会导致解不开，
     *   阻止"调换密文"这类攻击。默认为空表示不绑定。
     * @return `[12 字节 nonce][密文+tag]`
     */
    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        aad: ByteArray = ByteArray(0),
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(key.size == KEY_BYTES) { "主密钥必须是 $KEY_BYTES 字节（AES-256），实际 ${key.size}" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /**
     * 解密。
     *
     * @throws SyncCryptoException 密钥不对、密文被篡改、或 aad 不匹配。
     *   调用方**必须**把它当作"这个对象不可用"处理（跳过或重下），
     *   绝不能让异常冒泡中断整次同步 —— 一个坏对象不该阻塞其余数据。
     */
    fun decrypt(
        stored: ByteArray,
        key: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(key.size == KEY_BYTES) { "主密钥必须是 $KEY_BYTES 字节（AES-256），实际 ${key.size}" }
        if (stored.size < NONCE_BYTES + TAG_BYTES) {
            throw SyncCryptoException("密文长度不足（${stored.size} 字节），不是本应用写入的对象")
        }
        val nonce = stored.copyOfRange(0, NONCE_BYTES)
        val ciphertext = stored.copyOfRange(NONCE_BYTES, stored.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                if (aad.isNotEmpty()) updateAAD(aad)
            }
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // 统一成自己的异常类型：调用方只关心"这个对象能不能用"，
            // 不关心底层是 AEADBadTag 还是 IllegalBlockSize。
            throw SyncCryptoException("解密失败：密钥不匹配、密文损坏，或对象被搬动过", e)
        }
    }

    /** 生成一把新主密钥。 */
    fun newKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(KEY_BYTES).also(random::nextBytes)
}

/** 加解密失败。**可恢复**：跳过该对象即可，不该中断同步。 */
class SyncCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
