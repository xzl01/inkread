package com.inkread.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 墨水屏专用配色。
 *
 * 设计约束（彩色电子墨水屏，Kaleido 3 / Gallery 3 一类）：
 * 1. 底色必须是纯白，正文用近纯黑，保证最高对比度、最少残影；
 * 2. 彩色墨水屏色域窄、饱和度低，大面积彩色区块会显得脏灰，因此强调色只用于
 *    小面积元素（未读点、下划线、按钮边框），不放大面积色块；
 * 3. 不用阴影/渐变（墨水屏上会变成灰色脏块），层级靠 1px 细线 + 留白表达。
 */
object InkColor {
    /** 纸面底色 */
    val paper = Color(0xFFFFFFFF)

    /** 次级底色（顶栏/底栏/卡片），与纸面几乎无差别，只做轻微分区 */
    val paperAlt = Color(0xFFF7F7F7)

    /** 正文墨色 */
    val ink = Color(0xFF111111)

    /** 次级文字（来源、时间、摘要） */
    val inkSecondary = Color(0xFF3D3D3D)

    /** 三级文字（提示、计数） */
    val inkTertiary = Color(0xFF6E6E6E)

    /** 分隔线 */
    val rule = Color(0xFFC8C8C8)

    /** 浅分隔线 */
    val ruleLight = Color(0xFFE0E0E0)

    /** 主强调色：深靛蓝，彩色墨水屏上可辨识且不刺眼 */
    val accent = Color(0xFF1F4E79)

    /** 强调色浅底（选中项背景） */
    val accentSoft = Color(0xFFE6ECF3)

    /** 未读标记（暗红，小面积） */
    val unread = Color(0xFF8C1D18)

    /** 危险操作（删除） */
    val danger = Color(0xFF8C1D18)

    /** 选中/按下反馈背景（无动画，纯色替换） */
    val pressed = Color(0xFFE8E8E8)

    /** 纯黑，用于大字标题 */
    val black = Color(0xFF000000)
}
