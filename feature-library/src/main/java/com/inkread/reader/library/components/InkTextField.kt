package com.inkread.reader.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkType

/**
 * 手写输入框：白底 + 1dp 实线边框。
 *
 * 不使用 Material 的 TextField / OutlinedTextField —— 它们自带焦点动画、浮动 label、
 * 下划线动效，在墨水屏上会持续残影。这里用 [BasicTextField] 自己画边框，
 * 输入态与静止态完全一致，只多一个光标。
 */
@Composable
fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = InkType.body.copy(color = InkColor.ink),
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .background(InkColor.paper)
            .border(1.dp, InkColor.rule)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        textStyle = textStyle,
        singleLine = singleLine,
        cursorBrush = SolidColor(InkColor.ink),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = keyboardActions,
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = InkColor.inkTertiary),
                        maxLines = 1,
                    )
                }
                innerTextField()
            }
        },
    )
}
