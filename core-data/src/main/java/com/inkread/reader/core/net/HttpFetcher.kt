package com.inkread.reader.core.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 一次 HTTP 抓取的结果。[notModified] 为 true（HTTP 304）时 [body] 为空。 */
data class FetchResponse(
    /** 跟随重定向后的最终地址，用于补全相对链接。 */
    val url: String,
    val body: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
    val contentType: String? = null,
    val notModified: Boolean = false,
)

/** 抓取失败；[message] 是可直接展示给用户的中文提示。 */
class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OkHttp 抓取封装（单例 client：connect 15s / read 20s、跟随重定向、gzip 自动解压）。
 *
 * - 支持 HTTP 条件请求：传入 [FetchResponse.etag] / [FetchResponse.lastModified] 后，
 *   服务端返回 304 时 [FetchResponse.notModified] 为 true，可省流量；
 * - 错误统一转成中文 [FetchException]（含状态码）；
 * - 自动识别 BOM / Content-Type / XML 声明里的 charset（不少中文源是 GBK）。
 */
class HttpFetcher(private val client: OkHttpClient = defaultClient) {

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        accept: String = ACCEPT_FEED,
    ): FetchResponse {
        val httpUrl = url.trim().toHttpUrlOrNull()
            ?: throw FetchException("无效的地址：${url.take(120)}")
        val builder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) builder.header("If-Modified-Since", lastModified)

        val response = execute(builder.build())
        response.use { result ->
            val finalUrl = result.request.url.toString()
            val contentType = result.header("Content-Type")
            if (result.code == 304) {
                return FetchResponse(
                    url = finalUrl,
                    notModified = true,
                    etag = etag,
                    lastModified = lastModified,
                    contentType = contentType,
                )
            }
            if (!result.isSuccessful) throw FetchException(httpErrorMessage(result.code))
            val body = result.body ?: return FetchResponse(url = finalUrl, contentType = contentType)
            val bytes = body.bytes()
            return FetchResponse(
                url = finalUrl,
                body = decodeBody(bytes, contentType),
                etag = result.header("ETag"),
                lastModified = result.header("Last-Modified"),
                contentType = contentType,
            )
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(mapIoException(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private fun mapIoException(e: IOException): FetchException {
        val raw = e.message.orEmpty()
        val message = when {
            raw.contains("CLEARTEXT", ignoreCase = true) ||
                raw.contains("cleartext", ignoreCase = true) ->
                "该地址不支持 HTTP 明文访问，请改用 https:// 地址"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("No address associated", ignoreCase = true) ->
                "无法解析服务器地址，请检查网络连接"
            raw.contains("timed out", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "连接超时，请稍后重试"
            raw.contains("SSL", ignoreCase = true) ||
                raw.contains("certificate", ignoreCase = true) ->
                "HTTPS 证书校验失败"
            e is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络连接"
            e is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
            else -> "网络请求失败：${raw.ifBlank { e.javaClass.simpleName }}"
        }
        return FetchException(message, e)
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        301, 302, 303, 307, 308 -> "地址重定向异常（HTTP $code）"
        400 -> "请求格式错误（HTTP 400）"
        401 -> "需要登录或授权（HTTP 401）"
        403 -> "访问被拒绝（HTTP 403）"
        404 -> "地址不存在（HTTP 404）"
        408 -> "请求超时（HTTP 408）"
        429 -> "请求过于频繁，请稍后重试（HTTP 429）"
        in 500..599 -> "服务器错误（HTTP $code），请稍后重试"
        else -> "请求失败（HTTP $code）"
    }

    /**
     * 按 BOM → `Content-Type` charset → XML 声明 → HTML meta → UTF-8/GB18030 的顺序解码
     * （具体探测逻辑见 [TextDecoder]，与 OPML 导入共用）。
     */
    private fun decodeBody(bytes: ByteArray, contentType: String?): String =
        TextDecoder.decode(bytes, charsetFromContentType(contentType))

    private fun charsetFromContentType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val match = CHARSET_PARAM.find(contentType) ?: return null
        return match.groupValues[1].trim().trim('"', '\'')
    }

    companion object {
        const val USER_AGENT = "InkRead/0.1 (Android; RSS reader)"

        /** 抓取 feed 时使用的 Accept。 */
        const val ACCEPT_FEED =
            "application/rss+xml, application/atom+xml, application/rdf+xml, " +
                "application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"

        /** 抓取网站首页（用于发现订阅源）时使用的 Accept。 */
        const val ACCEPT_HTML = "text/html, application/xhtml+xml;q=0.9, */*;q=0.8"

        private val CHARSET_PARAM = Regex("""charset\s*=\s*([^\s;]+)""", RegexOption.IGNORE_CASE)

        /** 进程内共享的 OkHttpClient（连接池复用；不关闭证书校验）。 */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
