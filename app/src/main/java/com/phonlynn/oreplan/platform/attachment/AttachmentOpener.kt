package com.phonlynn.oreplan.platform.attachment

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 用外部应用打开附件。
 *
 * pdf / word / excel 这些格式在应用内渲染需要一个完整的渲染库，而本项目的 Kotlin
 * 被 AGP 9 锁在 2.2.x，新依赖的编译版本不可控（与不用 Haze、不用 Glance 是同一条理由）。
 * 交给系统查看器既能保证「点开就能看」，又不多担一层依赖风险。
 *
 * 图片与音频另有应用内查看/播放（图片看不到外部应用的差异，音频用系统播放器还得离开应用），
 * 所以那两类不经过这里。
 */
object AttachmentOpener {

    /** FileProvider 的 authority。必须与 AndroidManifest 里的声明一致。 */
    fun authorityOf(context: Context): String = "${context.packageName}.fileprovider"

    fun uriOf(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        authorityOf(context),
        file,
    )

    /**
     * 打开外部应用。返回 false 表示设备上没有能处理这个类型的应用 ——
     * 调用方要给出提示，不能让用户点一下什么都没有发生。
     */
    fun openExternally(context: Context, file: File, mimeType: String): Boolean {
        if (!file.isFile) {
            Toast.makeText(context, "文件已丢失，请重新添加一次附件", Toast.LENGTH_LONG).show()
            return true
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriOf(context, file), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有能打开这类文件的应用", Toast.LENGTH_LONG).show()
            false
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, "这个文件类型无法交给其他应用打开", Toast.LENGTH_LONG).show()
            false
        }
    }
}
