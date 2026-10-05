package com.phonlynn.oreplan.platform.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.phonlynn.oreplan.core.rt.RichDoc
import com.phonlynn.oreplan.core.rt.RichLine
import com.phonlynn.oreplan.core.rt.RichLineKind
import com.phonlynn.oreplan.core.rt.RichTextMark
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.platform.attachment.AttachmentOpener
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.ClipData
import android.content.ClipboardManager

/**
 * 把一张卡片的「笔记内容」渲染成**一张长图**（用户 2026-09-28 定稿）。
 *
 * ## 口径
 *
 * - 只导出**笔记内容**：标题 + 正文 + 图片 +（非图片附件的**完整名称**）+ 底部水印；
 * - **所有图片完整高清**：按原图比例、铺满内容宽、**纵向一张一行**
 *（展示页是三列缩略，导出时展开 —— 用户明确要求）；
 * - 非图片附件**不出图**，但名称**完整保留、不截断**（展示页只显示一行会截断）；
 * - 极少数失败（文件丢失/解码失败）不阻断整张图：跳过该图并继续。
 *
 * ## 为什么用 StaticLayout 而不是把 Compose 树离屏渲染
 *
 * 长图的宽高要**先算出来**才能建位图，而 Compose 的离屏测量需要往窗口树里挂
 * 一个 `ComposeView`（要 `ViewTreeLifecycleOwner`、`SavedStateRegistryOwner`…），
 * 在导出这种一次性场景里既脆弱又难测。`StaticLayout` 是同一套底层文本引擎
 *（Compose 的 Text 最终也走它），排版结果一致，且不必依赖窗口。
 */
object CardLongImage {

    /** 导出宽度（px）。固定 1080：手机屏幕宽度量级，微信/相册查看都清晰。 */
    private const val WIDTH = 1080

    /** 渲染密度：3x（15sp 正文 → 45px）。 */
    private const val DENSITY = 3f

    /** 内容左右留白。 */
    private const val PAD = 62f

    /** 渲染一张卡片需要的一切（都在 UI 层算好传进来，本对象不依赖 v2 主题）。 */
    data class Spec(
        val title: String?,
        val body: String?,
        val attachments: List<Attachment>,
        /** 卡片底色（ARGB）。 */
        val background: Int,
        /** 正文色（ARGB）。 */
        val ink: Int,
        /** 次要文字色：列表符号、附件名、水印（ARGB）。 */
        val ink3: Int,
        val titleSp: Float = 20f,
        val bodySp: Float = 15f,
        /** 正文行距倍数。与全屏页一致（用户 2026-09-28：全屏行距加大到 1.7）。 */
        val lineHeightMultiplier: Float = 1.7f,
    )

    // ---------------------------------------------------------------- 渲染

    fun render(storage: AttachmentStorage, spec: Spec): Bitmap {
        val contentW = (WIDTH - 2 * PAD).toInt()
        val doc = RichDoc.decode(spec.body)

        // 正文（带结构前缀与行内样式）→ 文本排版。
        val bodyPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = spec.bodySp * DENSITY
            color = spec.ink
            typeface = Typeface.DEFAULT
        }
        val bodyLayout = layout(styledBody(doc, spec.ink3), bodyPaint, contentW, spec.lineHeightMultiplier)

        // 标题。
        val title = spec.title?.trim().orEmpty()
        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = spec.titleSp * DENSITY
            color = spec.ink
            typeface = Typeface.DEFAULT_BOLD
        }
        val titleLayout = if (title.isNotEmpty()) {
            layout(SpannableStringBuilder(title), titlePaint, contentW, 1.3f)
        } else {
            null
        }

        // 图片：原图比例、铺满内容宽、纵向依次排列。
        val imageBitmaps = spec.attachments
            .filter { it.isImage }
            .mapNotNull { decode(storage.fileOf(it), contentW) }

        // 非图片附件：只留**完整名称**，一行一个。
        val namePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 12f * DENSITY
            color = spec.ink3
            typeface = Typeface.DEFAULT
        }
        val nameLayouts = spec.attachments
            .filterNot { it.isImage }
            .map { layout(SpannableStringBuilder("附件 · ${it.displayName}"), namePaint, contentW, 1.35f) }

        // 底部水印。
        val mark = layout(
            SpannableStringBuilder(watermark()),
            namePaint,
            contentW,
            1.3f,
            Layout.Alignment.ALIGN_CENTER,
        )

        // 逐块累加高度。
        val gapTitle = 26f
        val gapBlock = 42f
        val gapImage = 24f
        var y = PAD
        val titleH = (titleLayout?.height ?: 0).toFloat()
        if (titleH > 0f) y += titleH + gapTitle
        val bodyH = bodyLayout.height.toFloat()
        if (bodyH > 0f) y += bodyH
        imageBitmaps.forEach { bmp ->
            y += gapBlock
            y += contentW * bmp.height.toFloat() / bmp.width.toFloat()
        }
        nameLayouts.forEach { l ->
            y += if (imageBitmaps.isNotEmpty() || nameLayouts.size > 1) gapImage else gapBlock
            y += l.height.toFloat()
        }
        y += gapBlock + mark.height + PAD

        val bitmap = Bitmap.createBitmap(WIDTH, y.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(spec.background)

        var cy = PAD
        titleLayout?.let {
            canvas.save(); canvas.translate(PAD, cy); it.draw(canvas); canvas.restore()
            cy += it.height + gapTitle
        }
        if (bodyH > 0f) {
            canvas.save(); canvas.translate(PAD, cy); bodyLayout.draw(canvas); canvas.restore()
            cy += bodyH
        }
        val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        imageBitmaps.forEach { bmp ->
            cy += gapBlock
            val h = contentW * bmp.height.toFloat() / bmp.width.toFloat()
            canvas.drawBitmap(bmp, null, RectF(PAD, cy, PAD + contentW, cy + h), imgPaint)
            cy += h
        }
        nameLayouts.forEachIndexed { index, l ->
            cy += if (imageBitmaps.isNotEmpty() || index > 0) gapImage else gapBlock
            canvas.save(); canvas.translate(PAD, cy); l.draw(canvas); canvas.restore()
            cy += l.height
        }
        cy += gapBlock
        canvas.save(); canvas.translate(PAD, cy); mark.draw(canvas); canvas.restore()
        return bitmap
    }

    /** 正文 → 带样式文本：结构前缀弱色、行内样式映射成 Span、勾选项加删除线。 */
    private fun styledBody(doc: RichDoc, weak: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        doc.lines.forEachIndexed { index, line ->
            if (index > 0) sb.append('\n')
            val prefix = RichTextMark.prefixOf(line.kind, line.checked)
            if (prefix.isNotEmpty()) {
                val ps = sb.length
                sb.append(prefix)
                sb.setSpan(ForegroundColorSpan(weak), ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val start = sb.length
            sb.append(line.text)
            applyRuns(sb, start, line)
            if (line.kind == RichLineKind.CHECK && line.checked) {
                sb.setSpan(
                    StrikethroughSpan(),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                sb.setSpan(
                    ForegroundColorSpan(weak),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return sb
    }

    /** 行内样式区间 → Span（按连续同属性合并，避免一字一个 Span）。 */
    private fun applyRuns(sb: SpannableStringBuilder, start: Int, line: RichLine) {
        val n = line.text.length
        var i = 0
        while (i < n) {
            val bold = line.isBoldAt(i)
            val strike = line.isStrikeAt(i)
            val under = line.isUnderlineAt(i)
            val italic = line.isItalicAt(i)
            var j = i + 1
            while (j < n &&
                line.isBoldAt(j) == bold &&
                line.isStrikeAt(j) == strike &&
                line.isUnderlineAt(j) == under &&
                line.isItalicAt(j) == italic
            ) {
                j++
            }
            if (bold || strike || under || italic) {
                val from = start + i
                val to = start + j
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (italic) sb.setSpan(StyleSpan(Typeface.ITALIC), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (strike) sb.setSpan(StrikethroughSpan(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (under) sb.setSpan(UnderlineSpan(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i = j
        }
    }

    private fun layout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        multiplier: Float,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(align)
        .setLineSpacing(0f, multiplier)
        .setIncludePad(false)
        .build()

    /**
     * 解码图片并做**不损失清晰度**的采样：取「采样后宽度仍不小于内容宽」的最小采样率。
     * 这样超大图不会 OOM，正常图也不会被降采样。
     */
    private fun decode(file: File, targetW: Int): Bitmap? = runCatching {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    // ---------------------------------------------------------------- 输出

    fun watermark(): String {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        return "OreNote · 拓记    $day"
    }

    /** 导出文件名（相册里的显示名）。 */
    fun fileName(title: String?): String {
        val base = title?.trim()?.take(24)?.takeIf { it.isNotEmpty() } ?: "OreNote"
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        return "$base-$stamp.png"
    }

    /** 存进系统相册（`Pictures/OreNote/`）。返回是否成功。 */
    fun saveToPictures(context: Context, bitmap: Bitmap, displayName: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/OreNote",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val ok = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        ok
    }.getOrDefault(false)

    /** 写到应用内 `files/exports/`（分享用；该目录已在 FileProvider 白名单里）。 */
    fun writeForShare(context: Context, bitmap: Bitmap, displayName: String): File? = runCatching {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, displayName)
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        file
    }.getOrNull()

    fun shareImage(context: Context, file: File, title: String?) {
        val uri: Uri = AttachmentOpener.uriOf(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title.orEmpty())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 卡片的**纯文本**形态：标题 + 空行 + 正文（富文本转纯文字）。
     *
     * 「分享为文字」与「复制」（三点菜单）用的是**同一份文本** —— 只此一处构造，
     * 避免两处各写一遍后分叉（本项目已多次因「同一内容两处各写一份」返工）。
     */
    fun cardPlainText(title: String?, body: String?): String = buildString {
        if (!title.isNullOrBlank()) append(title.trim()).append("\n\n")
        append(RichDoc.decode(body).plainText)
    }.trim()

    /** 分享为文字：走系统分享面板。 */
    fun shareText(context: Context, title: String?, body: String?) {
        val text = cardPlainText(title, body)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title.orEmpty())
        }
        context.startActivity(
            Intent.createChooser(intent, "分享文字").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 复制卡片的**纯文本**到剪贴板（三点菜单「复制」）。
     *
     * 注意：这是「复制文本」，**不是**再生成一张卡片副本（用户 2026-09-29 明确纠正：
     * 之前的实现做成了 duplicate 卡片，理解错了）。
     */
    fun copyText(context: Context, title: String?, body: String?) {
        val text = cardPlainText(title, body)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("OreNote 卡片", text))
    }
}
