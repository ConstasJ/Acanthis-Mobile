package io.acanthis.app.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import io.acanthis.app.app.navigation.BookshelfRoute
import io.acanthis.app.app.navigation.ExploreRoute
import io.acanthis.app.app.navigation.HistoryRoute
import io.acanthis.app.app.navigation.NovelDetailRoute
import io.acanthis.app.app.navigation.ReaderRoute
import io.acanthis.app.app.navigation.SettingsRoute
import io.acanthis.app.app.theme.AcanthisTheme
import io.acanthis.app.core.media.DefaultCoilCacheController
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.feature.bookshelf.BookshelfScreen
import io.acanthis.app.feature.bookshelf.BookshelfViewModel
import io.acanthis.app.feature.detail.NovelDetailScreen
import io.acanthis.app.feature.explore.ExploreScreen
import io.acanthis.app.feature.explore.ExploreViewModel
import io.acanthis.app.feature.history.HistoryScreen
import io.acanthis.app.feature.reader.ReaderScreen
import io.acanthis.app.feature.settings.SettingsScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private enum class TopLevelTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
) {
    Bookshelf("书架", Icons.AutoMirrored.Rounded.MenuBook, BookshelfRoute),
    History("历史", Icons.Rounded.History, HistoryRoute),
    Explore("发现", Icons.Rounded.Explore, ExploreRoute),
    Settings("设置", Icons.Rounded.Settings, SettingsRoute),
}

private fun NavDestination.matchesTab(tab: TopLevelTab): Boolean {
    return when (tab) {
        TopLevelTab.Bookshelf -> hasRoute<BookshelfRoute>()
        TopLevelTab.History -> hasRoute<HistoryRoute>()
        TopLevelTab.Explore -> hasRoute<ExploreRoute>()
        TopLevelTab.Settings -> hasRoute<SettingsRoute>()
    }
}

@Composable
fun AcanthisApp(
    configStore: ServerConfigStore = koinInject(),
    libraryRepository: LibraryRepository = koinInject(),
    coilCacheController: DefaultCoilCacheController = koinInject(),
) {
    setSingletonImageLoaderFactory { context ->
        coilCacheController.createImageLoader(context)
    }

    val platformContext = LocalPlatformContext.current
    LaunchedEffect(platformContext) {
        SingletonImageLoader.get(platformContext)
    }

    AcanthisTheme {
        val serverConfig by configStore.configFlow.collectAsStateWithLifecycle(
            initialValue = ServerConfig(),
        )
        val navController = rememberNavController()
        var pendingBookshelfGenre by remember { mutableStateOf<String?>(null) }
        var pendingExploreCachedGenre by remember { mutableStateOf<String?>(null) }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val showBottomBar = currentDestination == null ||
            currentDestination.hierarchy.any { dest ->
                TopLevelTab.entries.any { tab -> dest.matchesTab(tab) }
            }

        LaunchedEffect(serverConfig.apiBaseUrl) {
            if (serverConfig.isConfigured) {
                libraryRepository.syncNow()
            }
        }

        val navigateToTab: (TopLevelTab) -> Unit = { tab ->
            navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        TopLevelTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentDestination?.hierarchy?.any { it.matchesTab(tab) } == true,
                                onClick = { navigateToTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = BookshelfRoute,
                ) {
                    composable<BookshelfRoute> {
                        val bookshelfViewModel: BookshelfViewModel = koinViewModel()
                        LaunchedEffect(pendingBookshelfGenre) {
                            val genre = pendingBookshelfGenre
                            if (!genre.isNullOrBlank()) {
                                bookshelfViewModel.applyGenreFilterFromOutside(genre)
                                pendingBookshelfGenre = null
                            }
                        }
                        BookshelfScreen(
                            onOpenNovel = { platform, novelId ->
                                navController.navigate(
                                    NovelDetailRoute(platform = platform, novelId = novelId),
                                )
                            },
                            onContinueReading = { platform, novelId, chapterId ->
                                navController.navigate(
                                    ReaderRoute(
                                        platform = platform,
                                        novelId = novelId,
                                        chapterId = chapterId,
                                    ),
                                )
                            },
                            onOpenSettings = { navigateToTab(TopLevelTab.Settings) },
                            onExploreCachedGenre = { genre ->
                                pendingExploreCachedGenre = genre
                                navigateToTab(TopLevelTab.Explore)
                            },
                            viewModel = bookshelfViewModel,
                        )
                    }

                    composable<HistoryRoute> {
                        HistoryScreen(
                            onOpenNovel = { platform, novelId ->
                                navController.navigate(
                                    NovelDetailRoute(platform = platform, novelId = novelId),
                                )
                            },
                            onContinueReading = { platform, novelId, chapterId ->
                                navController.navigate(
                                    ReaderRoute(
                                        platform = platform,
                                        novelId = novelId,
                                        chapterId = chapterId,
                                    ),
                                )
                            },
                            onOpenExplore = { navigateToTab(TopLevelTab.Explore) },
                        )
                    }

                    composable<ExploreRoute> {
                        val exploreViewModel: ExploreViewModel = koinViewModel()
                        LaunchedEffect(pendingExploreCachedGenre) {
                            val genre = pendingExploreCachedGenre
                            if (!genre.isNullOrBlank()) {
                                exploreViewModel.openCachedWithGenre(genre)
                                pendingExploreCachedGenre = null
                            }
                        }
                        ExploreScreen(
                            onOpenNovel = { platform, novelId ->
                                navController.navigate(
                                    NovelDetailRoute(platform = platform, novelId = novelId),
                                )
                            },
                            onOpenSettings = { navigateToTab(TopLevelTab.Settings) },
                            viewModel = exploreViewModel,
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen()
                    }

                    composable<NovelDetailRoute> { entry ->
                        val route = entry.toRoute<NovelDetailRoute>()
                        NovelDetailScreen(
                            platform = route.platform,
                            novelId = route.novelId,
                            onBack = { navController.popBackStack() },
                            onOpenChapter = { platform, novelId, chapterId ->
                                navController.navigate(
                                    ReaderRoute(
                                        platform = platform,
                                        novelId = novelId,
                                        chapterId = chapterId,
                                    ),
                                )
                            },
                            onGenreClick = { genre ->
                                pendingBookshelfGenre = genre
                                navigateToTab(TopLevelTab.Bookshelf)
                            },
                        )
                    }

                    composable<ReaderRoute> { entry ->
                        val route = entry.toRoute<ReaderRoute>()
                        ReaderScreen(
                            platform = route.platform,
                            novelId = route.novelId,
                            initialChapterId = route.chapterId,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
