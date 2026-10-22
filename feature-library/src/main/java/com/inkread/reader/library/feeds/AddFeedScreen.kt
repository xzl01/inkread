package com.inkread.reader.library.feeds

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.core.model.DiscoveredFeed
import com.inkread.reader.library.components.InkTextField
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkLoadingText
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.components.InkTextButton
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType

/**
 * 添加订阅源页。
 *
 * 流程：输入网站首页或 feed 地址 → 「查找订阅源」自动发现候选 → 点「订阅」入库。
 * 也支持跳过查找直接订阅（粘贴的就是 feed 地址时最省事）。
 * 所有状态切换都是整块静态替换，没有 loading 动画、没有弹窗。
 */
@Composable
fun AddFeedScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AppGraphProvider).container
    val vm: AddFeedViewModel = viewModel {
        AddFeedViewModel(feedRepository = container.feedRepository)
    }

    val url by vm.url.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val subscribingUrl by vm.subscribingUrl.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val clipboardManager = remember(ctx) {
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    fun pasteFromClipboard() {
        val text = runCatching {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        }.getOrNull()
        if (text.isNullOrBlank()) {
            vm.showMessage("剪贴板为空，或没有可读取的文本")
        } else {
            vm.paste(text)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkColor.paper),
    ) {
        InkTopBar(title = "添加订阅源", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 16.dp),
        ) {
            Text(
                text = "输入网站首页地址，或直接粘贴 RSS / Atom 订阅地址",
                style = InkType.body,
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                InkTextField(
                    value = url,
                    onValueChange = vm::onUrlChange,
                    modifier = Modifier.weight(1f),
                    placeholder = "https://example.com",
                    imeAction = ImeAction.Go,
                    keyboardActions = KeyboardActions(
                        onGo = {
                            focusManager.clearFocus()
                            vm.discover()
                        },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                InkButton(
                    text = "粘贴",
                    onClick = { pasteFromClipboard() },
                    compact = true,
                    filled = false,
                )
            }

            Spacer(Modifier.height(12.dp))

            InkButton(
                text = if (state is AddFeedState.Loading) "查找中…" else "查找订阅源",
                onClick = {
                    focusManager.clearFocus()
                    vm.discover()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is AddFeedState.Loading,
            )

            // 允许跳过发现，直接把输入地址当 feed 订阅
            if ((state is AddFeedState.Idle || state is AddFeedState.Error) && url.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                InkTextButton(
                    text = "直接订阅此地址",
                    onClick = {
                        focusManager.clearFocus()
                        vm.subscribeCurrent()
                    },
                    enabled = !adding,
                )
            }

            val currentMessage = message
            if (currentMessage != null && state !is AddFeedState.Success) {
                Spacer(Modifier.height(12.dp))
                InkNotice(
                    text = currentMessage.text,
                    isError = currentMessage.isError,
                    onDismiss = vm::dismissMessage,
                )
            }

            Spacer(Modifier.height(12.dp))

            when (val current = state) {
                AddFeedState.Idle -> {
                    Text(
                        text = "支持 RSS 2.0 / Atom / RSS 1.0；输入网站首页时会自动发现订阅地址。",
                        style = InkType.meta,
                    )
                }

                AddFeedState.Loading -> {
                    InkLoadingText("正在查找订阅源…")
                }

                is AddFeedState.Error -> {
                    InkNotice(text = current.message, isError = true)
                }

                is AddFeedState.Candidates -> {
                    Text(
                        text = "找到 ${current.feeds.size} 个订阅源，选择要订阅的地址",
                        style = InkType.sectionLabel,
                    )
                    Spacer(Modifier.height(10.dp))
                    current.feeds.forEach { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            subscribing = subscribingUrl == candidate.url,
                            enabled = !adding,
                            onSubscribe = { vm.subscribe(candidate.url) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                is AddFeedState.Success -> {
                    InkNotice(text = "已添加：${current.title}")
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InkButton(
                            text = "完成",
                            onClick = onAdded,
                            compact = true,
                        )
                        InkTextButton(
                            text = "继续添加",
                            onClick = vm::reset,
                            tint = InkColor.accent,
                        )
                    }
                }
            }
        }
    }
}

/** 候选订阅源区块：白底 + 1dp 边框，标题 / 地址 / 订阅按钮。 */
@Composable
private fun CandidateCard(
    candidate: DiscoveredFeed,
    subscribing: Boolean,
    enabled: Boolean,
    onSubscribe: () -> Unit,
) {
    val title = candidate.title?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = InkColor.rule)
            .background(InkColor.paper)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title ?: candidate.url,
                style = InkType.listTitleUnread,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (title != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = candidate.url,
                    style = InkType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val type = candidate.type?.takeIf { it.isNotBlank() }
            if (type != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = type, style = InkType.meta)
            }
        }
        Spacer(Modifier.width(10.dp))
        InkButton(
            text = if (subscribing) "订阅中…" else "订阅",
            onClick = onSubscribe,
            compact = true,
            enabled = enabled,
        )
    }
}
