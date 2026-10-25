package com.inkread.reader.reading.reader

import android.graphics.Bitmap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkLoadingText
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.core.prefs.ImageEnhancement
import com.inkread.reader.ui.theme.InkType

/**
 * 全屏文章配图查看器（彩色墨水屏可显示彩色图片）。
 *
 * - 静态布局：顶栏 + 图片 + 底部上一张/下一张，无动画；
 * - 音量键 / PageUp/PageDown / 方向键切换，返回键关闭；
 * - 加载失败时静态提示，可点「重试」。
 */
@Composable
internal fun ImageGalleryOverlay(
    urls: List<String>,
    referer: String?,
    enhancement: ImageEnhancement = ImageEnhancement.OFF,
    onClose: () -> Unit,
) {
    if (urls.isEmpty()) return
    val context = LocalContext.current
    var index by remember(urls) { mutableIntStateOf(0) }
    var retry by remember(urls, index) { mutableIntStateOf(0) }
    var bitmap by remember(urls, index, enhancement) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(urls, index, enhancement) { mutableStateOf(true) }
    var error by remember(urls, index, enhancement) { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val metrics = context.resources.displayMetrics

    fun previous() {
        if (index > 0) index -= 1
    }

    fun next() {
        if (index < urls.lastIndex) index += 1
    }

    LaunchedEffect(urls, index, retry, enhancement) {
        loading = true
        error = null
        bitmap = null
        val url = urls.getOrNull(index) ?: return@LaunchedEffect
        val loaded = ReaderImageLoader.load(
            context = context,
            url = url,
            referer = referer,
            maxWidth = metrics.widthPixels,
            maxHeight = metrics.heightPixels,
            enhancement = enhancement,
        )
        bitmap = loaded
        loading = false
        if (loaded == null) error = "图片加载失败，可能已失效或网络不可用"
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val handleKey: (KeyEvent) -> Boolean = { event ->
        val native = event.nativeKeyEvent
        if (native.action != NativeKeyEvent.ACTION_DOWN) {
            false
        } else {
            when (native.keyCode) {
                NativeKeyEvent.KEYCODE_BACK -> {
                    onClose()
                    true
                }
                NativeKeyEvent.KEYCODE_VOLUME_DOWN,
                NativeKeyEvent.KEYCODE_PAGE_DOWN,
                NativeKeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    if (native.repeatCount == 0) next()
                    true
                }
                NativeKeyEvent.KEYCODE_VOLUME_UP,
                NativeKeyEvent.KEYCODE_PAGE_UP,
                NativeKeyEvent.KEYCODE_DPAD_LEFT,
                -> {
                    if (native.repeatCount == 0) previous()
                    true
                }
                else -> false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(handleKey),
    ) {
        InkTopBar(
            title = "图片 ${index + 1}/${urls.size}",
            onBack = onClose,
            actions = {
                Text(
                    text = "音量键切换",
                    style = InkType.meta,
                    color = InkColor.inkTertiary,
                )
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            val err = error
            when {
                loading -> InkLoadingText("图片加载中…")
                err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    InkNotice(text = err, isError = true)
                    Spacer(Modifier.height(8.dp))
                    InkButton(
                        text = "重试",
                        compact = true,
                        filled = false,
                        onClick = { retry += 1 },
                    )
                }
                current != null -> Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = "文章配图 ${index + 1}",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        InkDivider(color = InkColor.ruleLight)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InkButton(
                text = "上一张",
                compact = true,
                filled = false,
                enabled = index > 0,
                onClick = { previous() },
            )
            InkButton(
                text = "下一张",
                compact = true,
                filled = false,
                enabled = index < urls.lastIndex,
                onClick = { next() },
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "第 ${index + 1}/${urls.size} 张 · 返回键关闭",
                style = InkType.meta,
                color = InkColor.inkTertiary,
            )
        }
    }
}
