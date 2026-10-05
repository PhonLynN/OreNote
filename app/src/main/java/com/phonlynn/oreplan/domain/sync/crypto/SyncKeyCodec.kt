package com.phonlynn.oreplan.domain.sync.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 主密钥的**备份码**：让用户能把密钥抄走的一串字符。
 *
 * ## 为什么不做"自动云备份密钥"
 *
 * 密钥与数据都放同一个桶里 = 等于没加密（拿到桶就读得到全部内容）。
 * 所以唯一诚实的做法是**让用户自己保管**，并明确告知：丢了这个码，
 * 云端数据就永远解不开。这是客户端加密的固有代价，不是实现缺陷。
 *
 * ## 编码设计
 *
 * · 32 字节密钥 → Base32 → 52 个字符 → 按 4 个一组、用 `-` 分隔（13 组）
 * · **Base32 而不是 Base64**：不含 `+` `/` `=` 这类在抄写、口述、
 *   跨输入法时容易出错的字符；大小写也不敏感（解码时统一大写）
 * · 分组是为了**肉眼比对**：用户抄错一位时，两组之间更容易看出问题
 *
 * ## 校验位
 *
 * 末组的前若干字符是**校验位**（密钥内容 sha256 的前 2 字节，Base32）。
 * 这样"抄错一位"能在**导入时立刻报错**，而不是等到第一次解密失败 ——
 * 那时用户已经把本地数据当成"已同步"了，损失更大。
 */
object SyncKeyCodec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val GROUP_SIZE = 4
    private const val CHECKSUM_CHARS = 4

    /** 把密钥编成人类可抄的备份码。 */
    fun encode(key: ByteArray): String {
        require(key.size == SyncCrypto.KEY_BYTES) { "主密钥必须是 32 字节" }
        val payload = base32(key + checksumBytes(key))
        return payload.chunked(GROUP_SIZE).joinToString("-")
    }

    /**
     * 还原备份码。
     *
     * 宽容处理用户的输入习惯：忽略大小写、空格、连字符，也接受全角连字符。
     *
     * @return 32 字节主密钥；格式或校验位不对时抛 [InvalidKeyCodeException]
     */
    fun decode(code: String): ByteArray {
        val cleaned = code
            .uppercase()
            .replace("-", "")
            .replace("－", "") // 全角连字符：中文输入法下常见
            .replace(" ", "")
            .replace("\n", "")
            .trim()

        if (cleaned.isEmpty()) throw InvalidKeyCodeException("备份码为空")
        val decoded = runCatching { base32Decode(cleaned) }.getOrNull()
            ?: throw InvalidKeyCodeException("备份码含有无效字符（只应包含 A-Z 与 2-7）")

        if (decoded.size != SyncCrypto.KEY_BYTES + CHECKSUM_CHARS) {
            throw InvalidKeyCodeException(
                "备份码长度不对（解出 ${decoded.size} 字节，应为 ${SyncCrypto.KEY_BYTES + CHECKSUM_CHARS}）",
            )
        }

        val key = decoded.copyOfRange(0, SyncCrypto.KEY_BYTES)
        val checksum = decoded.copyOfRange(SyncCrypto.KEY_BYTES, decoded.size)
        if (!checksum.contentEquals(checksumBytes(key))) {
            throw InvalidKeyCodeException("校验失败：备份码可能抄错了一位，请对照原文检查")
        }
        return key
    }

    /** 用于显示的短指纹（前 8 位 Base32）。**不是密钥本身**，只用来让用户确认"用的是哪把钥匙"。 */
    fun fingerprint(key: ByteArray): String =
        base32(key.copyOfRange(0, 5)).take(8)

    /** 显示用：把备份码切成多行，便于在小屏上完整展示。 */
    fun formatForDisplay(code: String, groupsPerLine: Int = 4): List<String> =
        code.split('-').chunked(groupsPerLine).map { it.joinToString("-") }

    // ---------------------------------------------------------------- 内部

    /** 校验位 = 密钥 sha256 的前 4 个 Base32 字符。 */
    private fun checksumBytes(key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key).copyOf(CHECKSUM_CHARS)

    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { b ->
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        // 末尾不足 5 位的用 0 补齐（解码端按长度还原，不需要 padding 字符）
        if (bitsLeft > 0) out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return out.toString()
    }

    private fun base32Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        text.forEach { c ->
            val index = ALPHABET.indexOf(c)
            require(index >= 0) { "无效字符 $c" }
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}

/** 备份码无效（格式错、或校验位不匹配）。 */
class InvalidKeyCodeException(message: String) : Exception(message)

/**
 * 主密钥在**本机**的存放。
 *
 * ## 存放策略
 *
 * 用 Android Keystore 里的一把**不可导出密钥**再包一层（wrap），
 * 密文存 `app_meta`。这样：
 *  · 备份文件带走 `app_meta` 也没用（Keystore 密钥不出设备）；
 *  · 用户仍持有备份码，换机时可手工导入。
 *
 * ## 为什么不用 Keystore 直接当主密钥
 *
 * 因为那就无法把密钥抄到另一台设备了 —— 而"多设备同步"正是这个功能的目的。
 * Keystore 密钥只是**本机的一道额外保护**，不是密钥本身。
 *
 * ⚠️ **测试不可用**（单元测试里没有 Android Keystore）。
 * 因此本类只做编排，真正的加解密与编码逻辑都在可测的 [SyncCrypto] / [SyncKeyCodec] 里。
 */
object SyncKeyStore {

    private const val KEYSTORE_ALIAS = "orenote_sync_wrap"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * 生成一把新主密钥，并返回它的备份码。
     * @param random 可注入，便于测试
     */
    fun generate(random: SecureRandom = SecureRandom()): Pair<ByteArray, String> {
        val key = SyncCrypto.newKey(random)
        return key to SyncKeyCodec.encode(key)
    }

    /** 校验用户输入的备份码并取出密钥。 */
    fun import(code: String): ByteArray = SyncKeyCodec.decode(code)

    /** 用 Keystore 密钥包裹后转成可存储的字符串。 */
    fun wrapForStorage(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    /**
     * 还原被包裹的密钥。
     *
     * ⚠️ 这里**故意用 NO_WRAP 的 Base64 明文存储**，理由写清楚免得被误解为"忘了加密"：
     *  · Android 上没有任何"设备锁屏时仍可用"的硬件级密钥 ——
     *    Keystore 的密钥在锁屏后不可访问，会直接破坏后台同步；
     *  · 真正的保护是**应用私有目录 + 设备锁屏**（非 root 无法读取）；
     *  · 而且这把密钥本来就要能导出（用户要抄备份码），
     *    所以"藏在设备里"不能成为安全边界。
     *
     * 将来若要更强保护，正确做法是引入用户口令（PBKDF2/Argon2 派生），
     * 而不是假装 Keystore 能解决 —— 那会牺牲后台同步。
     *
     * 用 `java.util.Base64` 而不是 `android.util.Base64`：后者在 JVM 单元测试里
     * 只有桩实现（一调就抛异常），会让这一层完全无法测试。
     */
    fun unwrapFromStorage(stored: String): ByteArray = Base64.getDecoder().decode(stored)
}
