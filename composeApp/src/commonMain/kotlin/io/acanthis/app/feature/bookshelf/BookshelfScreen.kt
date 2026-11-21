package io.acanthis.app.feature.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.acanthis.app.data.SyncStatus
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenNovel: (platform: String, novelId: String) -> Unit,
    onContinueReading: (platform: String, novelId: String, chapterId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onExploreCachedGenre: (genre: String) -> Unit = {},
    viewModel: BookshelfViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var actionTarget by remember { mutableStateOf<BookshelfCardItem?>(null) }
    var assignTarget by remember { mutableStateOf<BookshelfCardItem?>(null) }
    var pendingRemoval by remember { mutableStateOf<BookshelfCardItem?>(null) }
    var showSyncErrorDialog by remember { mutableStateOf(false) }
    var showShelfManager by remember { mutableStateOf(false) }
    var showGenreDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("书架") },
            actions = {
                IconButton(onClick = {
                    showSearchBar = !showSearchBar
                    if (!showSearchBar && uiState.searchQuery.isNotEmpty()) {
                        viewModel.onSearchQueryChange("")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "搜索书架",
                    )
                }

                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "排序",
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        BookshelfSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (uiState.sortOption == option) "✓ ${option.label}" else option.label,
                                    )
                                },
                                onClick = {
                                    viewModel.selectSortOption(option)
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }

                when (uiState.syncState.status) {
                    SyncStatus.SYNCING -> {
                        Box(
                            modifier = Modifier.padding(end = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    SyncStatus.ERROR -> {
                        IconButton(onClick = { showSyncErrorDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "同步出错",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    SyncStatus.IDLE -> {
                        IconButton(
                            onClick = { viewModel.syncNow() },
                            enabled = uiState.isConfigured,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = "同步书架",
                            )
                        }
                    }
                }
            },
        )

        // 书架切换条（全部 / 默认 / 自定义书架 / 管理）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = uiState.tabs,
                key = {
                    when (val t = it.tab) {
                        BookshelfTab.All -> "tab:all"
                        BookshelfTab.Default -> "tab:default"
                        is BookshelfTab.Category -> "tab:cat:${t.id}"
                    }
                },
            ) { tabItem ->
                FilterChip(
                    selected = uiState.selectedTab == tabItem.tab,
                    onClick = { viewModel.selectTab(tabItem.tab) },
                    label = { Text("${tabItem.label} (${tabItem.count})") },
                )
            }
            item(key = "tab:manage") {
                AssistChip(
                    onClick = { showShelfManager = true },
                    label = { Text("管理") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }

        if (showSearchBar || uiState.searchQuery.isNotEmpty()) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("按书名或作者筛选…") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 筛选工具条（标签多选按钮 + 连载状态）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "filter:genres") {
                FilterChip(
                    selected = uiState.selectedGenres.isNotEmpty(),
                    onClick = { showGenreDialog = true },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = {
                        val countSuffix = if (uiState.selectedGenres.isNotEmpty()) {
                            " (${uiState.selectedGenres.size})"
                        } else {
                            ""
                        }
                        Text("标签$countSuffix")
                    },
                )
            }

            items(BookshelfStatusFilter.entries, key = { "status:${it.name}" }) { status ->
                FilterChip(
                    selected = uiState.statusFilter == status,
                    onClick = { viewModel.selectStatusFilter(status) },
                    label = { Text(status.label) },
                )
            }
        }

        // 已激活标签 Chip 条 + 重置筛选
        if (uiState.selectedGenres.isNotEmpty() || uiState.hasActiveFilters) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(uiState.selectedGenres.toList(), key = { "active_genre:$it" }) { genre ->
                    InputChip(
                        selected = true,
                        onClick = { viewModel.toggleGenreFilter(genre) },
                        label = { Text(genre) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "移除标签 $genre",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
                if (uiState.hasActiveFilters) {
                    item(key = "reset_filters") {
                        TextButton(onClick = { viewModel.resetFilters() }) {
                            Text("重置筛选")
                        }
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.syncNow() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!uiState.isConfigured) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "unconfigured_banner") {
                        UnconfiguredServerCard(onOpenSettings = onOpenSettings)
                    }
                }

                uiState.continueReading?.let { banner ->
                    if (!uiState.hasActiveFilters && uiState.selectedTab == BookshelfTab.All) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue_reading") {
                            ContinueReadingCard(
                                banner = banner,
                                onClick = {
                                    onContinueReading(banner.platform, banner.novelId, banner.chapterId)
                                },
                            )
                        }
                    }
                }

                if (uiState.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty_bookshelf") {
                        EmptyBookshelfCard(
                            isConfigured = uiState.isConfigured,
                            totalActiveBooksCount = uiState.totalActiveBooksCount,
                            hasActiveFilters = uiState.hasActiveFilters,
                            firstSelectedGenre = uiState.selectedGenres.firstOrNull(),
                            onResetFilters = { viewModel.resetFilters() },
                            onExploreCachedGenre = onExploreCachedGenre,
                        )
                    }
                } else {
                    items(
                        items = uiState.items,
                        key = { "${it.platform}:${it.novelId}" },
                    ) { item ->
                        BookshelfNovelCard(
                            item = item,
                            onClick = { onOpenNovel(item.platform, item.novelId) },
                            onLongClick = { actionTarget = item },
                        )
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                Text(
                    text = target.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            actionTarget = null
                            assignTarget = target
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("管理所属书架")
                    }
                    TextButton(
                        onClick = {
                            actionTarget = null
                            pendingRemoval = target
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("移出书架", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text("关闭")
                }
            },
        )
    }

    assignTarget?.let { target ->
        ShelfAssignDialog(
            novelTitle = target.title,
            categories = uiState.categories,
            initialCategoryIds = target.categoryIds,
            onDismiss = { assignTarget = null },
            onCreateCategory = { name, onErr -> viewModel.createCategory(name, onErr) },
            onConfirm = { selectedIds ->
                viewModel.setBookshelfCategories(target.platform, target.novelId, selectedIds)
                assignTarget = null
            },
        )
    }

    if (showShelfManager) {
        ShelfManagerDialog(
            categories = uiState.categories,
            onDismiss = { showShelfManager = false },
            onCreateCategory = { name, onErr -> viewModel.createCategory(name, onErr) },
            onRenameCategory = { id, name, onErr -> viewModel.renameCategory(id, name, onErr) },
            onDeleteCategory = { id -> viewModel.deleteCategory(id) },
            onReorderCategories = { ids -> viewModel.reorderCategories(ids) },
        )
    }

    if (showGenreDialog) {
        GenreFilterDialog(
            availableGenres = uiState.availableGenres,
            selectedGenres = uiState.selectedGenres,
            onDismiss = { showGenreDialog = false },
            onConfirm = { genres ->
                viewModel.setSelectedGenres(genres)
                showGenreDialog = false
            },
        )
    }

    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("移出书架") },
            text = { Text("确定要将《${target.title}》移出书架吗？该变更会同步到云端，阅读进度仍保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromBookshelf(target.platform, target.novelId)
                        pendingRemoval = null
                    },
                ) {
                    Text("移出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (showSyncErrorDialog) {
        AlertDialog(
            onDismissRequest = { showSyncErrorDialog = false },
            title = { Text("云同步失败") },
            text = {
                Text(uiState.syncState.lastError ?: "未知网络错误，本地修改已保存在待同步队列中。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSyncErrorDialog = false
                        viewModel.syncNow()
                    },
                ) {
                    Text("立即重试")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncErrorDialog = false }) {
                    Text("关闭")
                }
            },
        )
    }
}

@Composable
private fun UnconfiguredServerCard(
    onOpenSettings: () -> Unit,
) {
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
                text = "请先前往设置页填写 api-server 地址并验证连通性，即可自动同步书架与阅读进度。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onOpenSettings) {
                Text("去配置服务器")
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    banner: ContinueReadingBanner,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(54.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                if (banner.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = banner.coverUrl,
                        contentDescription = banner.novelTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "继续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = banner.novelTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val chapterDisplay = listOf(banner.volumeTitle, banner.chapterTitle)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    text = chapterDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { banner.progressPercent / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = "${banner.progressPercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "继续阅读",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfNovelCard(
    item: BookshelfCardItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.progressLabel?.let { badge ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (item.author.isNotBlank()) {
            Text(
                text = item.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyBookshelfCard(
    isConfigured: Boolean,
    totalActiveBooksCount: Int,
    hasActiveFilters: Boolean,
    firstSelectedGenre: String?,
    onResetFilters: () -> Unit,
    onExploreCachedGenre: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = when {
                    hasActiveFilters -> "没有符合筛选条件的书籍"
                    totalActiveBooksCount > 0 -> "当前书架暂无书籍"
                    else -> "书架还是空的"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when {
                    hasActiveFilters -> "可以尝试调整标签、连载状态或搜索关键词。"
                    totalActiveBooksCount > 0 -> "长按书籍卡片可将其分配到当前书架。"
                    isConfigured -> "可以前往「发现」页收藏轻小说，或下拉刷新从服务端同步书架。"
                    else -> "配置服务器后可同步 Web 端书架或从「发现」页添加小说。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasActiveFilters) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onResetFilters) {
                        Text("重置筛选")
                    }
                    if (!firstSelectedGenre.isNullOrBlank()) {
                        Button(onClick = { onExploreCachedGenre(firstSelectedGenre) }) {
                            Text("去服务器缓存查看「$firstSelectedGenre」")
                        }
                    }
                }
            }
        }
    }
}
