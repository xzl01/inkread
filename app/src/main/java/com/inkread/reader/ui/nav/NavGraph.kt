package com.inkread.reader.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inkread.reader.library.feeds.AddFeedScreen
import com.inkread.reader.library.feeds.FeedsScreen
import com.inkread.reader.library.settings.SettingsScreen
import com.inkread.reader.reading.home.HomeScreen
import com.inkread.reader.reading.reader.ReaderScreen

/**
 * 单 Activity + Compose Navigation。
 *
 * 关键：所有页面切换动画都关闭（EnterTransition.None / ExitTransition.None），
 * 墨水屏上页面切换动画只会带来闪烁和残影，直接整屏重绘反而更快更干净。
 */
@Composable
fun InkNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.Home) {
            HomeScreen(
                onOpenArticle = { articleId -> navController.navigate(Routes.reader(articleId)) },
                onManageFeeds = { navController.navigate(Routes.Feeds) },
                onAddFeed = { navController.navigate(Routes.AddFeed) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }

        composable(
            route = Routes.ReaderPattern,
            arguments = listOf(navArgument(Routes.ArticleIdArg) { type = NavType.LongType }),
        ) { backStackEntry ->
            ReaderScreen(
                articleId = backStackEntry.arguments?.getLong(Routes.ArticleIdArg) ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Feeds) {
            FeedsScreen(
                onBack = { navController.popBackStack() },
                onAddFeed = { navController.navigate(Routes.AddFeed) },
            )
        }

        composable(Routes.AddFeed) {
            AddFeedScreen(
                onBack = { navController.popBackStack() },
                onAdded = { navController.popBackStack() },
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
