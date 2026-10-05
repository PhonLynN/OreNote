package com.phonlynn.oreplan.platform.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附件的文件存储。
 *
 * 只负责磁盘：库里的索引行由仓储管，两边由用例/界面配合。分成两层的原因是
 * 「复制文件」这件事既不能放进仓储（仓储要保持纯数据、可测），也不该散在界面里
 * （界面拿不到稳定的目录约定）。
 *
 * **文件复制进应用内部存储，而不是记下 SAF 的 URI。** SAF 给的是临时授权，
 * 重启或被清理后就打不开了；而「点开还能看」正是这份清单存在的意义。
 * 代价是多占一份空间，所以配一个孤儿清理（见 [sweep]）。
 */
@Singleton
class AttachmentStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 相对 `filesDir` 的根目录名。库里存的是相对路径，不含这个前缀之外的任何绝对路径。 */
    private val rootName = "attachments"

    private val root: File get() = File(context.filesDir, rootName)

    /** 附件对应的文件。 */
    fun fileOf(attachment: Attachment): File = File(context.filesDir, attachment.storedPath)

    fun fileOf(relativePath: String): File = File(context.filesDir, relativePath)

    /**
     * 文件是否还在。
     *
     * 恢复备份之后会出现「索引在、文件不在」的情况（备份不带二进制），
     * 界面必须先问这一句，才能把「文件已丢失」说清楚，而不是点下去没反应。
     */
    fun exists(attachment: Attachment): Boolean = fileOf(attachment).isFile

    /**
     * 把 [uri] 指向的内容复制进来，返回可落库的附件索引。
     *
     * [ownerId] 决定文件放在哪个子目录下；[id] 允许调用方预先生成，
     * 这样「先写文件、保存时才写库」的两步之间不会出现 id 对不上的情况。
     *
     * 函数名不能用 `import`：它是 Kotlin 的软关键字，做函数名会让解析器直接报语法错误。
     */
    suspend fun importAttachment(
        uri: Uri,
        ownerType: AttachmentOwner,
        ownerId: String,
        id: String = Ids.newId(),
    ): Attachment? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: "附件"
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val target = fileOf(relativePathOf(id, displayName))
        target.parentFile?.mkdirs()

        val size = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target.length()
        }.getOrNull() ?: return@withContext null

        Attachment(
            id = id,
            ownerType = ownerType,
            ownerId = ownerId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = size,
            storedPath = relativePathOf(id, displayName),
            createdAt = Instant.now(),
        )
    }

    /** 删掉附件对应的文件（以及它独占的目录）。索引行由仓储删。 */
    suspend fun delete(attachment: Attachment) = withContext(Dispatchers.IO) {
        val file = fileOf(attachment)
        file.delete()
        // 每个附件一个目录；文件删掉后目录空了就一并删，否则会留下成百上千个空目录。
        file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    /**
     * 清理孤儿文件：磁盘上存在、但库里没有对应索引的文件。
     *
     * 孤儿从哪来：新建条目时先把附件复制进来，用户最后没点保存就退出了。
     * 这一扫在应用启动时后台跑一次，代价与附件总数成正比（通常几十个文件），可以忽略。
     *
     * 返回删掉的条数，供日志与调试查看。
     */
    suspend fun sweep(validRelativePaths: Set<String>): Int = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext 0
        var removed = 0
        root.listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val relative = "$rootName/${dir.name}/${file.name}"
                if (relative !in validRelativePaths) {
                    if (file.delete()) removed++
                }
            }
            // 目录空了就删；非空说明还有活着的附件
            dir.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        }
        removed
    }

    /** 相对路径。保留原始文件名是为了恢复备份后还能看出附件叫什么。 */
    private fun relativePathOf(id: String, displayName: String): String =
        "$rootName/$id/${sanitize(displayName)}"

    /** 文件名里不能出现路径分隔符，否则会写到目录之外。 */
    private fun sanitize(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim().ifBlank { "附件" }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    companion object {
        /**
         * 选择附件时允许的类型。
         *
         * 按需求列到「图片 / 音频 / pdf / word / excel」，最后用一种通配类型兜底：
         * 白名单太窄会让用户遇到「文件明明在手机里却选不到」而不知道原因，
         * 这类失败比多收一个类型更让人困惑。
         */
        val PICKER_MIME_TYPES: Array<String> = arrayOf(
            "image/*",
            "audio/*",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "*/*",
        )
    }
}
