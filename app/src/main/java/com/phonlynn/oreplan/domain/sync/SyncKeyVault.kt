package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import com.phonlynn.oreplan.domain.sync.crypto.SyncCrypto
import com.phonlynn.oreplan.domain.sync.crypto.SyncKeyCodec
import com.phonlynn.oreplan.domain.sync.crypto.SyncKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** R2 连通性测试的结果。 */
sealed interface ConnectionState {
    /** 还没测过（或改了配置，之前的结论已作废）。 */
    data object Untested : ConnectionState

    data object Success : ConnectionState

    data class Failure(val message: String) : ConnectionState
}

/**
 * 主密钥的本地保管。
 *
 * ## 存在哪
 *
 * `app_meta`（KV 表），键 `sync.master_key`，值是 Base64。
 * 不加表、不动 schema —— 与 `SyncSettings` 同一策略。
 *
 * ## 为什么是"明文 Base64"而不是"Keystore 加密"
 *
 * 这一点在 [SyncKeyStore.unwrapFromStorage] 的注释里写透了，这里再强调一次，
 * 因为它很容易被后来者当成"忘了加密"：
 *
 *  · Android 上没有"设备锁屏时仍可用"的硬件级密钥 —— Keystore 的密钥
 *    在锁屏后不可访问，会直接让后台同步失效；
 *  · 这把密钥**本来就必须可导出**（用户要抄备份码到另一台设备），
 *    所以"藏在设备里"不能构成安全边界；
 *  · 真正的保护是**应用私有目录 + 设备锁屏**（非 root 无法读取）。
 *
 * 若将来要更强保护，正确做法是引入用户口令（PBKDF2/Argon2 派生），
 * 而不是假装 Keystore 能解决。
 *
 * ## 缓存的必要性
 *
 * 同步过程中每个对象都要用密钥，若每次都读数据库就是每对象一次查询。
 * 这里在内存里缓存，**只在进程内**有效（不落盘）。
 */
@Singleton
class SyncKeyVault @Inject constructor(
    private val database: AppDatabase,
) {

    private val mutex = Mutex()
    private var cached: ByteArray? = null

    /** 当前主密钥；没有则 null。首次调用会读库。 */
    suspend fun currentKey(): ByteArray? = mutex.withLock {
        cached ?: readStored()?.also { cached = it }
    }

    /** 生成一把新密钥并落库，返回（密钥, 备份码）。 */
    suspend fun generateAndStore(): Pair<ByteArray, String> = mutex.withLock {
        val key = SyncCrypto.newKey()
        writeStored(key)
        cached = key
        key to SyncKeyCodec.encode(key)
    }

    /** 存入一把已有的密钥（导入备份码时用）。 */
    suspend fun store(key: ByteArray) = mutex.withLock {
        require(key.size == SyncCrypto.KEY_BYTES) { "主密钥必须是 32 字节" }
        writeStored(key)
        cached = key
    }

    /** 清除（解除本机同步）。**不动云端数据。** */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            database.appMetaDao().upsert(AppMetaEntity(KEY_MASTER, ""))
        }
        cached = null
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun readStored(): ByteArray? {
        val stored = withContext(Dispatchers.IO) {
            database.appMetaDao().find(KEY_MASTER)?.value
        } ?: return null
        if (stored.isBlank()) return null
        // 读出来可能是坏的（手工改过库、版本升级）—— 那种情况当作"没有密钥"，
        // 而不是让整个设置页崩掉。
        return runCatching { SyncKeyStore.unwrapFromStorage(stored) }
            .getOrNull()
            ?.takeIf { it.size == SyncCrypto.KEY_BYTES }
    }

    private suspend fun writeStored(key: ByteArray) = withContext(Dispatchers.IO) {
        database.appMetaDao().upsert(
            AppMetaEntity(KEY_MASTER, SyncKeyStore.wrapForStorage(key)),
        )
    }

    private companion object {
        const val KEY_MASTER = "sync.master_key"
    }
}
