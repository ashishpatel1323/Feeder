package com.nononsenseapps.feeder.ui.compose.navigation

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navDeepLink
import com.nononsenseapps.feeder.base.diAwareViewModel
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.compose.youtube.ShortsPlayerScreen
import com.nononsenseapps.feeder.ui.compose.youtube.SubscriptionsManagerScreen
import com.nononsenseapps.feeder.ui.compose.youtube.SubscriptionsScreen
import com.nononsenseapps.feeder.ui.compose.youtube.YouTubeVideoPage
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI

/** The YouTube tab: Feeder's YouTube feeds shown like Flow's Subscriptions. */
data object YouTubeDestination : NavigationDestination(
    path = "youtube",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        SubscriptionsScreen(
            viewModel = backStackEntry.diAwareViewModel(),
            onOpenVideo = { YouTubeVideoDestination.navigate(navController, it.item.id, it.videoId) },
            onOpenShorts = { YouTubeShortsDestination.navigate(navController, it.item.id) },
            onManage = { YouTubeManageDestination.navigate(navController) },
        )
    }
}

/** Search, sort, import and manage the YouTube feeds (Flow's subscriptions manager). */
data object YouTubeManageDestination : NavigationDestination(
    path = "youtube/manage",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) { launchSingleTop = true }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        SubscriptionsManagerScreen(
            viewModel = backStackEntry.diAwareViewModel(),
            onBack = { navController.popBackStack() },
        )
    }
}

/** Flow's video page. Collapsing it (chevron, Back, swipe down) leaves the mini player. */
data object YouTubeVideoDestination : NavigationDestination(
    path = "youtube/watch",
    navArguments =
        listOf(
            QueryParamArgument("item") {
                type = NavType.LongType
                defaultValue = 0L
            },
            QueryParamArgument("video") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks =
        listOf(
            navDeepLink {
                uriPattern = "$DEEP_LINK_BASE_URI/youtube/watch?item={item}&video={video}"
            },
        ),
    enterTransition = { slideInVertically { it } },
    popExitTransition = { slideOutVertically { it } },
) {
    /** Opens a video; from another video page it replaces that one, so Back leaves the player. */
    fun navigate(
        navController: NavController,
        itemId: Long,
        videoId: String,
    ) {
        val fromPage = navController.currentDestination?.route == route
        navController.navigate("$path?item=$itemId&video=$videoId") {
            if (fromPage) popUpTo(route) { inclusive = true }
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val itemId = backStackEntry.arguments?.getLong("item") ?: 0L
        val videoId = backStackEntry.arguments?.getString("video").orEmpty()
        YouTubeVideoPage(
            itemId = itemId,
            videoId = videoId,
            viewModel = backStackEntry.diAwareViewModel(),
            onCollapse = { navController.popBackStack() },
            onOpenVideo = { item, video -> navigate(navController, item, video) },
        )
    }
}

/** The Shorts shelf in full screen: swipe up and down through it, like YouTube Shorts. */
data object YouTubeShortsDestination : NavigationDestination(
    path = "youtube/shorts",
    navArguments =
        listOf(
            QueryParamArgument("item") {
                type = NavType.LongType
                defaultValue = 0L
            },
        ),
    deepLinks = emptyList(),
    enterTransition = { slideInVertically { it } },
    popExitTransition = { slideOutVertically { it } },
) {
    fun navigate(
        navController: NavController,
        itemId: Long,
    ) {
        navController.navigate("$path?item=$itemId") { launchSingleTop = true }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        ShortsPlayerScreen(
            startItemId = backStackEntry.arguments?.getLong("item") ?: 0L,
            viewModel = backStackEntry.diAwareViewModel(),
            onClose = { navController.popBackStack() },
        )
    }
}
