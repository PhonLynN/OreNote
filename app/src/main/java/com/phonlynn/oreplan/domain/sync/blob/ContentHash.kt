package com.phonlynn.oreplan.domain.sync.blob

import java.io.File
import java.security.MessageDigest

/**
 * 附件二进制的**内容寻址**：用 sha256 作为它在云端的身份。
 *
 * ## 为什么必须内容寻址
 *
 * 现在的附件存的是「相对 filesDir 的路径」（`attachments/<id>/<文件名>`）——
 * 那个路径**跨设备毫无意义**：另一台设备没有同样的目录结构，
 * 即使有，同名文件也可能是不同内容。
 *
 * 内容哈希解决了三件事：
 *  ① **跨端可寻址**：两端对同一份内容算出同一个 key，不需要任何协调；
 *  ② **天然去重**：同一张图被两条记录引用（或重复导入）时，
 *     云端只存一份（先 `HEAD` 探一下就知道在不在）；
 *  ③ **完整性自证**：下载后重算哈希，对不上就说明传输损坏或被篡改 ——
 *     配合客户端加密，等于给二进制也做了校验。
 *
 * ## 为什么是 sha256 而不是 md5/sha1
 *
 * 那些已经有实际可行的碰撞构造，而这里哈希是**唯一的寻址依据**：
 * 碰撞意味着两个不同文件互相覆盖。sha256 目前没有实用碰撞，
 * 代价只是算得慢一点（附件通常几 MB，可接受）。
 */
object ContentHash {

    /** 流式计算，避免把大文件整个读进内存。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /**
     * 云端对象 key：`blobs/<前两位>/<完整哈希>`。
     *
     * 分两级目录不是为了好看 —— 单目录下几万个对象时，
     * 很多对象存储/文件系统的列举会明显变慢。前两位做分片是通用做法，
     * 且让"同一台设备上传的相邻文件"散开，减少热点。
     */
    fun objectKey(hash: String): String {
        require(hash.length >= 2) { "哈希太短：$hash" }
        return "blobs/${hash.substring(0, 2)}/$hash"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
