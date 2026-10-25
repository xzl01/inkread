package com.inkread.reader.library.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** 可选字体（系统字体或用户导入的字体）。 */
data class FontOption(
    val name: String,
    val path: String,
    val isSystem: Boolean,
)

/**
 * 字体发现 / 导入。
 *
 * 系统字体只列常见的 CJK / 中文字体，避免把 260 个阿拉伯/emoji 字体塞进列表；
 * 用户可以通过「导入字体文件」选择任意 TTF/OTF。
 */
object FontStore {

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
    private val CJK_KEYWORDS = Regex(
        """(?i)(CJK|MiSans|LXGW|KaiTi|JiangCheng|NotoSerifCJK|NotoSansCJK|NotoSansSC|NotoSansTC|FZ|Song|Hei|Ming|Fang|WenKai)""",
    )
    private val PRETTY_NAMES = mapOf(
        "NotoSansCJK-Regular" to "思源黑体",
        "NotoSerifCJK-Regular" to "思源宋体",
        "LXGWWenKai-Regular" to "霞鹜文楷",
        "MiSansL3" to "MiSans",
        "KaiTi_GB2312-Regular" to "楷体 GB2312",
        "FZJuZhenXinFangS-R-GB-Regular" to "方正聚珍新仿",
        "FZZJ-LYYBXSJW" to "方正凌云硬笔行书",
        "FZZJ-ZSXKJW" to "方正钟旭行楷",
        "JiangChengYuanTi400W" to "江城圆体",
        "DroidSans" to "Droid Sans",
        "DroidSansMono" to "Droid Sans Mono",
    )

    fun systemFonts(): List<FontOption> {
        val dir = File("/system/fonts")
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .filter { it.extension.lowercase() in FONT_EXTENSIONS }
            .filter { CJK_KEYWORDS.containsMatchIn(it.name) }
            .map { FontOption(prettify(it.nameWithoutExtension), it.absolutePath, true) }
            .distinctBy { it.path }
            .sortedBy { it.name }
            .toList()
    }

    fun importedFonts(context: Context): List<FontOption> {
        val dir = File(context.filesDir, "fonts")
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.length() > 1024 }
            .map { FontOption(it.nameWithoutExtension, it.absolutePath, false) }
            .sortedByDescending { it.name }
            .toList()
    }

    /** 把 SAF 选中的字体复制到 files/fonts 并校验能被系统加载。 */
    fun importFont(context: Context, uri: Uri): Result<FontOption> = runCatching {
        val displayName = queryDisplayName(context, uri) ?: "自定义字体.ttf"
        val baseName = displayName.substringBeforeLast('.', displayName)
            .replace(Regex("""[^\w\u4e00-\u9fff-]"""), "_")
            .take(40)
            .ifBlank { "自定义字体" }
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        val target = File(dir, "${baseName}-${System.currentTimeMillis()}.ttf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取字体文件")
        if (target.length() < 1024) {
            target.delete()
            error("字体文件为空或格式不支持")
        }
        runCatching { Typeface.createFromFile(target) }.getOrElse {
            target.delete()
            throw IllegalArgumentException("无法加载字体文件，请确认是 TTF/OTF 字体")
        }
        FontOption(baseName, target.absolutePath, false)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun prettify(fileName: String): String =
        PRETTY_NAMES[fileName] ?: fileName.replace('_', ' ').replace('-', ' ')
}
