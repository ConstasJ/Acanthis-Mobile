package io.acanthis.app.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.feature.bookshelf.ShelfAssignDialog
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    platform: String,
    novelId: String,
    onBack: () -> Unit,
    onOpenChapter: (platform: String, novelId: String, chapterId: String) -> Unit,
    onGenreClick: (genre: String) -> Unit = {},
    viewModel: NovelDetailViewModel = koinViewModel(
        key = "$platform:$novelId",
        parameters = { parametersOf(platform, novelId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showShelfAssignDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = uiState.novel?.title?.takeIf { it.isNotBlank() } ?: "小说详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (uiState.novel != null) {
                        IconButton(
                            onClick = { viewModel.refresh() },
                            enabled = !uiState.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新目录",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val novel = uiState.novel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                novel == null && uiState.isRefreshing -> {
                    LoadingState(modifier = Modifier.fillMaxSize())
                }

                novel == null -> {
                    ErrorState(
                        message = uiState.errorMessage ?: "无法加载小说详情，请稍后重试",
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        NovelDetailContent(
                            novel = novel,
                            uiState = uiState,
                            onToggleBookshelf = { viewModel.toggleBookshelf() },
                            onManageShelves = { showShelfAssignDialog = true },
                            onGenreClick = onGenreClick,
                            onStartReading = {
                                viewModel.resolveStartChapterId()?.let { chapterId ->
                                    onOpenChapter(platform, novelId, chapterId)
                                }
                            },
                            onToggleReverseOrder = { viewModel.toggleReverseOrder() },
                            onToggleVolumeExpanded = { volumeKey ->
                                viewModel.toggleVolumeExpanded(volumeKey)
                            },
                            onOpenChapter = { chapterId ->
                                onOpenChapter(platform, novelId, chapterId)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showShelfAssignDialog) {
        ShelfAssignDialog(
            novelTitle = uiState.novel?.title?.ifBlank { novelId } ?: novelId,
            categories = uiState.categories,
            initialCategoryIds = uiState.bookshelfCategoryIds,
            onDismiss = { showShelfAssignDialog = false },
            onCreateCategory = { name, onErr -> viewModel.createCategory(name, onErr) },
            onConfirm = { selectedIds ->
                viewModel.setBookshelfCategories(selectedIds)
                showShelfAssignDialog = false
            },
        )
    }
}

@Composable
private fun NovelDetailContent(
    novel: NovelDto,
    uiState: NovelDetailUiState,
    onToggleBookshelf: () -> Unit,
    onManageShelves: () -> Unit,
    onGenreClick: (String) -> Unit,
    onStartReading: () -> Unit,
    onToggleReverseOrder: () -> Unit,
    onToggleVolumeExpanded: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "header") {
            NovelHeaderCard(
                novel = novel,
                onGenreClick = onGenreClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        item(key = "actions") {
            ActionBarSection(
                isInBookshelf = uiState.isInBookshelf,
                isTogglingBookshelf = uiState.isTogglingBookshelf,
                assignedShelfCount = uiState.bookshelfCategoryIds.size,
                hasProgress = uiState.hasProgress,
                canStartReading = uiState.canStartReading,
                progress = uiState.progress,
                onToggleBookshelf = onToggleBookshelf,
                onManageShelves = onManageShelves,
                onStartReading = onStartReading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item(key = "toc_controls") {
            TocControlRow(
                totalChapterCount = uiState.totalChapterCount,
                cachedChapterCount = uiState.cachedChapterCount,
                isReversed = uiState.isReversed,
                onToggleReverseOrder = onToggleReverseOrder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            )
        }

        if (uiState.displayVolumes.isEmpty() || uiState.totalChapterCount == 0) {
            item(key = "empty_toc") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "暂无章节目录",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "可下拉页面刷新以重新获取最新目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            uiState.displayVolumes.forEach { volume ->
                item(key = volume.headerKey) {
                    VolumeHeaderRow(
                        volume = volume,
                        onToggle = { onToggleVolumeExpanded(volume.volumeKey) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                if (volume.isExpanded) {
                    if (volume.chapters.isEmpty()) {
                        item(key = "${volume.headerKey}_empty") {
                            Text(
                                text = "该分卷暂无章节",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                            )
                        }
                    } else {
                        items(
                            items = volume.chapters,
                            key = { it.itemKey },
                        ) { item ->
                            ChapterRow(
                                item = item,
                                onClick = { onOpenChapter(item.chapter.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovelHeaderCard(
    novel: NovelDto,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var summaryExpanded by rememberSaveable(novel.id) { mutableStateOf(false) }
    var summaryHasOverflow by remember(novel.summary) { mutableStateOf(false) }
    val trimmedSummary = remember(novel.summary) { novel.summary.trim() }
    val cleanGenres = remember(novel.genres) {
        novel.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                NovelCoverImage(
                    coverUrl = novel.coverUrl,
                    title = novel.title,
                    modifier = Modifier
                        .width(104.dp)
                        .height(144.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = novel.title.ifBlank { "未命名作品" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = "作者：${novel.author.ifBlank { "未知" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(status = novel.status)

                        if (novel.platform.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = novel.platform,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (cleanGenres.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cleanGenres.forEach { genre ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onGenreClick(genre) },
                        ) {
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = trimmedSummary.isNotEmpty()) {
                        summaryExpanded = !summaryExpanded
                    }
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = trimmedSummary.ifEmpty { "暂无简介" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trimmedSummary.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = if (summaryExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layoutResult ->
                        if (!summaryExpanded) {
                            summaryHasOverflow = layoutResult.hasVisualOverflow
                        }
                    },
                )

                if (trimmedSummary.isNotEmpty() && (summaryExpanded || summaryHasOverflow)) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (summaryExpanded) "收起简介" else "展开全部",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = if (summaryExpanded) {
                                Icons.Rounded.ExpandLess
                            } else {
                                Icons.Rounded.ExpandMore
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelCoverImage(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    var loadFailed by remember(coverUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isNullOrBlank() || loadFailed) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StatusBadge(
    status: String,
) {
    val normalized = status.trim().lowercase()
    val (label, containerColor, contentColor) = when (normalized) {
        "ongoing" -> Triple(
            "连载中",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        "completed" -> Triple(
            "已完结",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            "状态未知",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ActionBarSection(
    isInBookshelf: Boolean,
    isTogglingBookshelf: Boolean,
    assignedShelfCount: Int,
    hasProgress: Boolean,
    canStartReading: Boolean,
    progress: ReadingProgressEntity?,
    onToggleBookshelf: () -> Unit,
    onManageShelves: () -> Unit,
    onStartReading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isInBookshelf) {
                FilledTonalButton(
                    onClick = onToggleBookshelf,
                    enabled = !isTogglingBookshelf,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("已在书架")
                }
            } else {
                OutlinedButton(
                    onClick = onToggleBookshelf,
                    enabled = !isTogglingBookshelf,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("加入书架")
                }
            }

            Button(
                onClick = onStartReading,
                enabled = canStartReading,
                modifier = Modifier.weight(1.25f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (hasProgress) "继续阅读" else "开始阅读")
            }
        }

        if (isInBookshelf) {
            OutlinedButton(
                onClick = onManageShelves,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = if (assignedShelfCount > 0) {
                    "管理所属书架（已加入 $assignedShelfCount 个自定义书架）"
                } else {
                    "管理所属书架（默认书架）"
                }
                Text(label)
            }
        }

        if (progress != null && progress.chapterTitle.isNotBlank()) {
            val percentSuffix = if (progress.totalParagraphs > 0) {
                val pct = (((progress.paragraphIndex + 1) * 100) / progress.totalParagraphs)
                    .coerceIn(0, 100)
                " · $pct%"
            } else {
                ""
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = canStartReading, onClick = onStartReading),
            ) {
                Text(
                    text = "上次阅读：${progress.chapterTitle}$percentSuffix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TocControlRow(
    totalChapterCount: Int,
    cachedChapterCount: Int,
    isReversed: Boolean,
    onToggleReverseOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "目录 · 共 $totalChapterCount 章",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (cachedChapterCount > 0) {
                Text(
                    text = "已缓存 $cachedChapterCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        TextButton(onClick = onToggleReverseOrder) {
            Icon(
                imageVector = Icons.Rounded.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (isReversed) "倒序" else "正序")
        }
    }
}

@Composable
private fun VolumeHeaderRow(
    volume: DisplayVolumeSection,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = volume.title.ifBlank { "正文" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${volume.chapters.size} 章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (volume.isExpanded) {
                    Icons.Rounded.ExpandLess
                } else {
                    Icons.Rounded.ExpandMore
                },
                contentDescription = if (volume.isExpanded) "折叠分卷" else "展开分卷",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ChapterRow(
    item: DisplayChapterItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (item.isCurrentProgress) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.chapter.title.ifBlank { "未命名章节" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isCurrentProgress) FontWeight.SemiBold else FontWeight.Normal,
                color = if (item.isCurrentProgress) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (item.isCurrentProgress) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "上次读到",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (item.isCached) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "已缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun LoadingState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "正在加载小说详情…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "无法加载小说详情",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("重试")
        }
    }
}
