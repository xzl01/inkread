package com.inkread.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.inkread.reader.ui.nav.InkNavHost
import com.inkread.reader.ui.theme.InkReadTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 墨水屏：保持系统栏常驻且与纸面同色，避免内容被遮挡或反复重绘
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        setContent {
            InkReadTheme {
                InkNavHost()
            }
        }
    }
}
