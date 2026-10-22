package com.inkread.reader.library.components

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 页面内静态提示（替代 Toast / Snackbar）。
 *
 * 墨水屏上任何自动消失的浮层都会造成整屏重绘与残影，所以提示一律以静态条
 * （[com.inkread.reader.ui.components.InkNotice]）展示，由用户手动点 ✕ 关闭。
 * [id] 用于区分内容相同的连续提示，保证 InkNotice 能收到新值。
 */
data class UiNotice(
    val id: Long,
    val text: String,
    val isError: Boolean = false,
)

/**
 * 把数据层异常转成用户可读的中文提示。
 *
 * 数据层接口约定返回失败结果（AddFeedResult.Failure / SyncResult.error）时已带中文文案；
 * 这里只兜底网络/IO 等意外异常，避免把英文堆栈直接抛给用户。
 */
internal fun userFacingError(error: Throwable): String = when (error) {
    is UnknownHostException -> "无法连接网络，请检查网络设置"
    is SocketTimeoutException -> "连接超时，请稍后重试"
    is SecurityException -> "没有访问该文件的权限"
    is IOException -> "读写失败，请稍后重试"
    else -> error.message?.takeIf { it.isNotBlank() } ?: "未知错误"
}
