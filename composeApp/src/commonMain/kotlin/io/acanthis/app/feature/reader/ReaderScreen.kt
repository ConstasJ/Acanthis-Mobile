package io.acanthis.app.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import io.acanthis.app.app.theme.ReaderPalette
import io.acanthis.app.app.theme.toPalette
import io.acanthis.app.core.parser.ReaderBlock
import io.acanthis.app.core.preferences.ReaderSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReaderScreen(
    platform: String,
    novelId: String,
    initialChapterId: String,
    onBack: () -> Unit,
) {
    val viewModel: ReaderViewModel = koinViewModel(
        key = "reader:$platform:$novelId:$initialChapterId",
        parameters = { parametersOf(platform, novelId, initialChapterId) },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = uiState.settings.theme.toPalette()

    var overlaysVisible by rememberSaveable { mutableStateOf(false) }
    var showTocSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var activeIllustration by remember { mutableStateOf<ReaderBlock.Image?>(null) }

    val listState = rememberLazyListState()

    val showChapterHeader = remember(uiState.chapterTitle, uiState.blocks) {
        uiState.chapterTitle.isNotBlank() &&
            (uiState.blocks.firstOrNull() as? ReaderBlock.Heading)?.text?.trim() != uiState.chapterTitle.trim()
    }
    val headerCount = if (showChapterHeader) 1 else 0

    // Ensure pending progress & sync are flushed immediately when leaving the reader.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushProgressOnExit()
        }
    }

    // One-shot scroll restoration (or reset to top on chapter navigation).
    LaunchedEffect(uiState.chapterLoadRevision, uiState.initialScrollIndex, uiState.blocks.size) {
        val targetBlock = uiState.initialScrollIndex ?: return@LaunchedEffect
        if (!uiState.isLoading && uiState.blocks.isNotEmpty()) {
            val clampedBlock = targetBlock.coerceIn(0, uiState.blocks.lastIndex)
            val targetListIndex = if (clampedBlock == 0) 0 else clampedBlock + headerCount
            listState.scrollToItem(targetListIndex)
            viewModel.consumeInitialScroll()
        }
    }

    // Observe scroll position once restoration has completed.
    LaunchedEffect(
        listState,
        uiState.currentChapterId,
        uiState.blocks.size,
        uiState.isLoading,
        uiState.initialScrollIndex,
        headerCount,
    ) {
        if (uiState.isLoading || uiState.blocks.isEmpty() || uiState.initialScrollIndex != null) {
            return@LaunchedEffect
        }
        val maxBlockIndex = uiState.blocks.lastIndex
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val firstListIndex = listState.firstVisibleItemIndex
            val lastListIndex = visibleItems.lastOrNull()?.index ?: firstListIndex
            val firstBlock = (firstListIndex - headerCount).coerceIn(0, maxBlockIndex)
            val lastBlock = (lastListIndex - headerCount).coerceIn(firstBlock, maxBlockIndex)
            firstBlock to lastBlock
        }
            .distinctUntilChanged()
            .collect { (firstBlock, lastBlock) ->
                viewModel.onScrollPositionChanged(
                    firstVisibleIndex = firstBlock,
                    lastVisibleIndex = lastBlock,
                )
            }
    }

    val bodyInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        when {
            uiState.isLoading -> {
                ReaderLoadingView(
                    chapterTitle = uiState.chapterTitle,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = bodyInteractionSource,
                            indication = null,
                        ) { overlaysVisible = !overlaysVisible },
                )
            }

            uiState.errorMessage != null && uiState.blocks.isEmpty() -> {
                ReaderErrorView(
                    message = uiState.errorMessage.orEmpty(),
                    retryAfterSeconds = uiState.retryAfterSeconds,
                    palette = palette,
                    onRetry = viewModel::retry,
                    onBack = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = bodyInteractionSource,
                            indication = null,
                        ) { overlaysVisible = !overlaysVisible },
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = uiState.settings.horizontalPaddingDp.dp,
                        end = uiState.settings.horizontalPaddingDp.dp,
                        top = 24.dp,
                        bottom = 48.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = bodyInteractionSource,
                            indication = null,
                        ) { overlaysVisible = !overlaysVisible },
                ) {
                    if (showChapterHeader) {
                        item(key = "chapter_header:${uiState.currentChapterId}") {
                            ChapterHeaderBlock(
                                volumeTitle = uiState.volumeTitle,
                                chapterTitle = uiState.chapterTitle,
                                settings = uiState.settings,
                                palette = palette,
                            )
                        }
                    }

                    if (uiState.blocks.isEmpty()) {
                        item(key = "empty_chapter:${uiState.currentChapterId}") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "本章暂无可显示正文内容",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.secondaryText,
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = uiState.blocks,
                            key = { _, block -> "${uiState.currentChapterId}:${block.blockIndex}" },
                        ) { _, block ->
                            when (block) {
                                is ReaderBlock.Heading -> {
                                    ReaderHeadingItem(
                                        block = block,
                                        settings = uiState.settings,
                                        palette = palette,
                                    )
                                }

                                is ReaderBlock.Paragraph -> {
                                    ReaderParagraphItem(
                                        block = block,
                                        settings = uiState.settings,
                                        palette = palette,
                                    )
                                }

                                is ReaderBlock.Image -> {
                                    ReaderImageItem(
                                        block = block,
                                        palette = palette,
                                        onClick = { activeIllustration = block },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "chapter_footer:${uiState.currentChapterId}") {
                        ChapterNavigationFooterCard(
                            previousChapter = uiState.previousChapter,
                            nextChapter = uiState.nextChapter,
                            isNextPrefetched = uiState.isNextChapterPrefetched,
                            palette = palette,
                            onPrevious = {
                                overlaysVisible = false
                                viewModel.previousChapter()
                            },
                            onNext = {
                                overlaysVisible = false
                                viewModel.nextChapter()
                            },
                        )
                    }
                }

                // Subtle pinned bottom status strip when overlay bars are hidden
                if (!overlaysVisible && uiState.blocks.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(palette.background.copy(alpha = 0.92f))
                            .padding(
                                horizontal = uiState.settings.horizontalPaddingDp.dp,
                                vertical = 6.dp,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.chapterTitle.ifBlank { uiState.novelTitle },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.secondaryText.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${uiState.scrollPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.secondaryText.copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }

        // Top overlay bar
        ReaderTopOverlayBar(
            visible = overlaysVisible,
            novelTitle = uiState.novelTitle,
            chapterTitle = uiState.chapterTitle,
            palette = palette,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Bottom overlay bar
        ReaderBottomOverlayBar(
            visible = overlaysVisible,
            uiState = uiState,
            palette = palette,
            onOpenToc = {
                overlaysVisible = false
                showTocSheet = true
            },
            onPreviousChapter = {
                overlaysVisible = false
                viewModel.previousChapter()
            },
            onCycleTheme = viewModel::cycleTheme,
            onOpenSettings = {
                overlaysVisible = false
                showSettingsSheet = true
            },
            onNextChapter = {
                overlaysVisible = false
                viewModel.nextChapter()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showTocSheet) {
        ReaderTocSheet(
            novel = uiState.novel,
            currentChapterId = uiState.currentChapterId,
            cachedChapterIds = uiState.cachedChapterIds,
            palette = palette,
            onSelectChapter = { selectedId ->
                if (selectedId != uiState.currentChapterId) {
                    viewModel.openChapter(selectedId)
                }
            },
            onDismiss = { showTocSheet = false },
        )
    }

    if (showSettingsSheet) {
        ReaderSettingsSheet(
            settings = uiState.settings,
            palette = palette,
            onUpdateSettings = viewModel::updateSettings,
            onDismiss = { showSettingsSheet = false },
        )
    }

    activeIllustration?.let { imageBlock ->
        IllustrationViewerDialog(
            imageUrl = imageBlock.src,
            contentDescription = imageBlock.alt.ifBlank { "章节插图" },
            onDismiss = { activeIllustration = null },
        )
    }
}

@Composable
private fun ChapterHeaderBlock(
    volumeTitle: String,
    chapterTitle: String,
    settings: ReaderSettings,
    palette: ReaderPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (volumeTitle.isNotBlank()) {
            Text(
                text = volumeTitle,
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = chapterTitle,
            fontSize = (settings.fontSizeSp * 1.3f).sp,
            lineHeight = (settings.fontSizeSp * 1.65f).sp,
            fontWeight = FontWeight.Bold,
            color = palette.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(
            color = palette.secondaryText.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth(0.35f),
        )
    }
}

@Composable
private fun ReaderHeadingItem(
    block: ReaderBlock.Heading,
    settings: ReaderSettings,
    palette: ReaderPalette,
) {
    val scaleFactor = when (block.tag.lowercase()) {
        "h1" -> 1.4f
        "h2" -> 1.28f
        "h3" -> 1.18f
        else -> 1.1f
    }
    val headingFontSize = (settings.fontSizeSp * scaleFactor).sp
    val headingLineHeight = (settings.fontSizeSp * scaleFactor * 1.35f).sp

    Text(
        text = block.text,
        fontSize = headingFontSize,
        lineHeight = headingLineHeight,
        fontWeight = FontWeight.Bold,
        color = palette.text,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun ReaderParagraphItem(
    block: ReaderBlock.Paragraph,
    settings: ReaderSettings,
    palette: ReaderPalette,
) {
    val displayText = if (settings.firstLineIndent) {
        "\u3000\u3000${block.text}"
    } else {
        block.text
    }

    Text(
        text = displayText,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
        color = palette.text,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReaderImageItem(
    block: ReaderBlock.Image,
    palette: ReaderPalette,
    onClick: () -> Unit,
) {
    var imageState by remember(block.src) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface.copy(alpha = 0.7f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = block.src,
                contentDescription = block.alt.ifBlank { "章节插图" },
                contentScale = ContentScale.FillWidth,
                onState = { imageState = it },
                modifier = Modifier.fillMaxWidth(),
            )

            when (imageState) {
                is AsyncImagePainter.State.Loading -> {
                    CircularProgressIndicator(
                        color = palette.secondaryText,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }

                is AsyncImagePainter.State.Error -> {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = null,
                            tint = palette.secondaryText,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "插图加载失败（点击查看大图）",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryText,
                        )
                    }
                }

                else -> Unit
            }
        }

        if (block.alt.isNotBlank()) {
            Text(
                text = block.alt,
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChapterNavigationFooterCard(
    previousChapter: FlatChapterRef?,
    nextChapter: FlatChapterRef?,
    isNextPrefetched: Boolean,
    palette: ReaderPalette,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = palette.surface,
        contentColor = palette.onSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.secondaryText.copy(alpha = 0.16f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (nextChapter != null) {
                    if (isNextPrefetched) {
                        "本章已读完 · 下一章已预取，点击秒开"
                    } else {
                        "本章已读完"
                    }
                } else {
                    "已阅读至最新章节"
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondaryText,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    enabled = previousChapter != null,
                    onClick = onPrevious,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.NavigateBefore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "上一章",
                        maxLines = 1,
                    )
                }

                Button(
                    enabled = nextChapter != null,
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (nextChapter != null) "下一章" else "已到末章",
                        maxLines = 1,
                    )
                    if (nextChapter != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (nextChapter != null) {
                Text(
                    text = "下一章：${nextChapter.chapterTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReaderLoadingView(
    chapterTitle: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            CircularProgressIndicator(
                color = palette.secondaryText,
            )
            Text(
                text = if (chapterTitle.isNotBlank()) "正在加载「$chapterTitle」…" else "正在加载章节…",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReaderErrorView(
    message: String,
    retryAfterSeconds: Int?,
    palette: ReaderPalette,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.surface,
            contentColor = palette.onSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, palette.secondaryText.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = "章节加载失败",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondaryText,
                    textAlign = TextAlign.Center,
                )
                if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                    Text(
                        text = "上游触发限流/验证，建议约 $retryAfterSeconds 秒后重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    OutlinedButton(onClick = onBack) {
                        Text("返回")
                    }
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
        }
    }
}
