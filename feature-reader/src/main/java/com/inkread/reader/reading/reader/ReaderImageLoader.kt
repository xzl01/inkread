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

    /** 彩色墨水屏增强：饱和度 + 对比度 + 轻微提亮；关闭时直接返回原图。 */
    private fun applyEnhancement(source: Bitmap, enhancement: ImageEnhancement): Bitmap {
        if (enhancement == ImageEnhancement.OFF) return source
        val matrix = ColorMatrix().apply { setSaturation(enhancement.saturation) }
        val c = enhancement.contrast
        val translate = 128f * (1f - c) + enhancement.brightness
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
