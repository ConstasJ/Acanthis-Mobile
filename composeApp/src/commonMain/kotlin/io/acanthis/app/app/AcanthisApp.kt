package io.acanthis.app.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import io.acanthis.app.app.navigation.NovelDetailRoute
import io.acanthis.app.app.navigation.ReaderRoute
import io.acanthis.app.app.navigation.SettingsRoute
import io.acanthis.app.app.theme.AcanthisTheme
import io.acanthis.app.core.media.DefaultCoilCacheController
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.feature.bookshelf.BookshelfScreen
import io.acanthis.app.feature.settings.SettingsScreen
import org.koin.compose.koinInject

private enum class TopLevelTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
) {
    Bookshelf("书架", Icons.AutoMirrored.Rounded.MenuBook, BookshelfRoute),
    Explore("发现", Icons.Rounded.Explore, ExploreRoute),
    Settings("设置", Icons.Rounded.Settings, SettingsRoute),
}

private fun NavDestination.matchesTab(tab: TopLevelTab): Boolean {
    return when (tab) {
        TopLevelTab.Bookshelf -> hasRoute<BookshelfRoute>()
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
                        )
                    }

                    composable<ExploreRoute> {
                        PlaceholderTabScreen(
                            headerTitle = "Acanthis · 发现",
                            title = "发现（Task 7 接入）",
                            description = "将动态消费服务端 /explore/schema，支持频道推荐、榜单、分类筛选与关键词搜索。",
                            serverConfig = serverConfig,
                            onOpenSettings = { navigateToTab(TopLevelTab.Settings) },
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen()
                    }

                    composable<NovelDetailRoute> { entry ->
                        val route = entry.toRoute<NovelDetailRoute>()
                        SecondaryPlaceholderScreen(
                            title = "小说详情 · ${route.novelId}",
                            subtitle = "平台：${route.platform}（Task 8 接入完整详情与分卷目录）",
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable<ReaderRoute> { entry ->
                        val route = entry.toRoute<ReaderRoute>()
                        SecondaryPlaceholderScreen(
                            title = "阅读器 · ${route.chapterId}",
                            subtitle = "平台：${route.platform} / 小说：${route.novelId}（Task 9 接入完整阅读器）",
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderTabScreen(
    headerTitle: String,
    title: String,
    description: String,
    serverConfig: ServerConfig,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(headerTitle) })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!serverConfig.isConfigured) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "尚未配置 Acanthis 服务器",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "请先前往设置页填写 api-server 地址并验证连通性（Android 模拟器访问本机可使用 http://10.0.2.2:5301）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(onClick = onOpenSettings) {
                            Text("去配置服务器")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecondaryPlaceholderScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
