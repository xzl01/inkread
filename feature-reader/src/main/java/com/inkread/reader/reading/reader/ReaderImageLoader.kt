package com.inkread.reader.reading.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import com.inkread.reader.core.prefs.ImageEnhancement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文章配图下载 + 磁盘缓存 + 按屏幕尺寸采样解码。
 *
 * - 缓存目录：`cacheDir/article-images/<sha1(url)>.img`，看过的图断网也能再看；
 * - 只做「一张图」的加载，列表/翻页由 UI 控制；
 * - 不引第三方图片库，避免给 e-ink 包体增加依赖。
 */
internal object ReaderImageLoader {

    private const val MAX_BYTES = 20L * 1024 * 1024

    suspend fun load(
        context: Context,
        url: String,
        referer: String?,
        maxWidth: Int,
        maxHeight: Int,
        enhancement: ImageEnhancement = ImageEnhancement.OFF,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val dir = File(context.cacheDir, "article-images").apply { mkdirs() }
        val cacheFile = File(dir, sha1(url) + ".img")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            try {
                download(url, referer, cacheFile)
            } catch (t: Throwable) {
                cacheFile.delete()
                return@withContext null
            }
        }
        val bitmap = decodeSampled(cacheFile, maxWidth, maxHeight)
        if (bitmap == null) {
            cacheFile.delete()
            return@withContext null
        }
        applyEnhancement(bitmap, enhancement)
    }

    private data class ImageStats(
        val meanLuma: Float,
        val stdLuma: Float,
        val avgChroma: Float,
    )

    /**
     * 彩色墨水屏增强：
     * - 自动：统计亮度均值、亮度标准差、平均色度，再决定饱和度/对比度/提亮幅度；
     *   灰度高（接近黑白）的图片不做饱和，改为更强对比度；
     * - 轻/强：固定参数；关闭：原图。
     */
    private fun applyEnhancement(source: Bitmap, enhancement: ImageEnhancement): Bitmap {
        if (enhancement == ImageEnhancement.OFF) return source
        val (saturation, contrast, brightness) = when (enhancement) {
            ImageEnhancement.AUTO -> autoParams(analyze(source))
            ImageEnhancement.LIGHT -> Triple(1.22f, 1.06f, 4f)
            ImageEnhancement.STRONG -> Triple(1.40f, 1.12f, 8f)
            ImageEnhancement.OFF -> Triple(1f, 1f, 0f)
        }
        return renderMatrix(source, saturation, contrast, brightness)
    }

    private fun autoParams(stats: ImageStats): Triple<Float, Float, Float> {
        val grayscale = stats.avgChroma < 8f
        val saturation = if (grayscale) {
            1f
        } else {
            when {
                stats.avgChroma < 18f -> 1.30f
                stats.avgChroma < 40f -> 1.20f
                stats.avgChroma < 70f -> 1.12f
                else -> 1.06f
            }
        }
        var contrast = when {
            stats.stdLuma < 40f -> 1.24f
            stats.stdLuma < 60f -> 1.14f
            stats.stdLuma < 80f -> 1.08f
            else -> 1.04f
        }
        if (grayscale) contrast = (contrast + 0.04f).coerceAtMost(1.28f)
        // 让画面亮度靠近中亮部，避免彩色墨水屏上整体发灰或过暗。
        val brightness = ((132f - stats.meanLuma) * 0.18f).coerceIn(-10f, 14f)
        return Triple(saturation, contrast, brightness)
    }

    private fun renderMatrix(
        source: Bitmap,
        saturation: Float,
        contrast: Float,
        brightness: Float,
    ): Bitmap {
        val matrix = ColorMatrix().apply { setSaturation(saturation.coerceIn(1f, 1.4f)) }
        val c = contrast.coerceIn(1f, 1.3f)
        val translate = 128f * (1f - c) + brightness
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /** 采样约 1.2 万像素，统计亮度与色度；避免对整张大图逐像素扫描。 */
    private fun analyze(source: Bitmap): ImageStats {
        val width = source.width
        val height = source.height
        val step = maxOf(1, kotlin.math.sqrt((width * height / 12_000f)).toInt())
        var count = 0
        var sumLuma = 0.0
        var sumLumaSq = 0.0
        var sumChroma = 0.0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = source.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                sumLuma += luma
                sumLumaSq += luma.toDouble() * luma
                sumChroma += (max - min)
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return ImageStats(128f, 64f, 32f)
        val mean = (sumLuma / count).toFloat()
        val variance = (sumLumaSq / count - (sumLuma / count) * (sumLuma / count)).toFloat()
        return ImageStats(
            meanLuma = mean,
            stdLuma = kotlin.math.sqrt(variance.coerceAtLeast(0f)),
            avgChroma = (sumChroma / count).toFloat(),
        )
    }

    private fun download(url: String, referer: String?, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) InkRead/0.3")
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
            referer?.takeIf { it.startsWith("http") }?.let { setRequestProperty("Referer", it) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val length = conn.contentLengthLong
            if (length > MAX_BYTES) throw java.io.IOException("图片过大：$length")
            val tmp = File(target.absolutePath + ".tmp")
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_BYTES) throw java.io.IOException("图片过大")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } finally {
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetW = reqWidth.coerceAtLeast(1)
        val targetH = reqHeight.coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
