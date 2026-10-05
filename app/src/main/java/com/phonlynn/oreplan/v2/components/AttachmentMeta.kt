package com.phonlynn.oreplan.v2.components

/**
 * 附件行的「类型 · 大小」文案（设计稿 File Meta，例如「PDF · 2.4 MB」）。
 *
 * 详情页、白板编辑页、白板全屏编辑页**共用这一份** —— 同一个概念只算一次，
 * 否则三处各写一份，改一处就会不一致。
 */
fun attachmentMetaText(bytes: Long, mime: String): String {
    val kb = bytes / 1024.0
    val size = when {
        kb >= 1024 * 1024 -> String.format("%.1f GB", kb / 1024 / 1024)
        kb >= 1024 -> String.format("%.1f MB", kb / 1024)
        else -> "${kb.toInt().coerceAtLeast(1)} KB"
    }
    val type = when {
        mime.contains("pdf") -> "PDF"
        mime.contains("sheet") || mime.contains("excel") -> "Excel"
        mime.contains("word") -> "Word"
        mime.contains("presentation") || mime.contains("powerpoint") -> "PPT"
        mime.startsWith("image/") -> "图片"
        mime.startsWith("audio/") -> "音频"
        mime.startsWith("video/") -> "视频"
        mime.isBlank() -> "文件"
        else -> "文件"
    }
    return "$type · $size"
}
