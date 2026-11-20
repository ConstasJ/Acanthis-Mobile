package io.acanthis.app.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.acanthis.app.app.theme.ReaderPalette
import io.acanthis.app.app.theme.toPalette
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.preferences.ReaderSettings
import io.acanthis.app.core.preferences.ReaderTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderTopOverlayBar(
    visible: Boolean,
    novelTitle: String,
    chapterTitle: String,
    palette: ReaderPalette,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = palette.surface.copy(alpha = 0.96f),
            contentColor = palette.onSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = palette.onSurface,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = chapterTitle.ifBlank { "正文阅读" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (novelTitle.isNotBlank()) {
                            Text(
                                text = novelTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(color = palette.secondaryText.copy(alpha = 0.16f))
            }
        }
    }
}

@Composable
fun ReaderBottomOverlayBar(
    visible: Boolean,
    uiState: ReaderUiState,
    palette: ReaderPalette,
    onOpenToc: () -> Unit,
    onPreviousChapter: () -> Unit,
    onCycleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = palette.surface.copy(alpha = 0.96f),
            contentColor = palette.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                HorizontalDivider(color = palette.secondaryText.copy(alpha = 0.16f))

                // Chapter progress info strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chapterPositionText = if (uiState.currentChapterIndex >= 0 && uiState.flatChapters.isNotEmpty()) {
                        "第 ${uiState.currentChapterIndex + 1} / ${uiState.flatChapters.size} 章"
                    } else {
                        uiState.volumeTitle.ifBlank { "正在阅读" }
                    }
                    Text(
                        text = chapterPositionText,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val statusSuffix = buildString {
                        append("${uiState.scrollPercent}%")
                        if (uiState.isNextChapterPrefetched) {
                            append(" · 下一章已缓存")
                        }
                    }
                    Text(
                        text = statusSuffix,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.secondaryText,
                    )
                }

                // Primary 5-action toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderToolbarAction(
                        icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                        label = "目录",
                        enabled = true,
                        palette = palette,
                        onClick = onOpenToc,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolbarAction(
                        icon = Icons.AutoMirrored.Rounded.NavigateBefore,
                        label = "上一章",
                        enabled = uiState.previousChapter != null,
                        palette = palette,
                        onClick = onPreviousChapter,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolbarAction(
                        icon = themeIcon(uiState.settings.theme),
                        label = themeShortLabel(uiState.settings.theme),
                        enabled = true,
                        palette = palette,
                        onClick = onCycleTheme,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolbarAction(
                        icon = Icons.Rounded.Tune,
                        label = "排版",
                        enabled = true,
                        palette = palette,
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderToolbarAction(
                        icon = Icons.AutoMirrored.Rounded.NavigateNext,
                        label = "下一章",
                        enabled = uiState.nextChapter != null,
                        palette = palette,
                        onClick = onNextChapter,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderToolbarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (enabled) {
        palette.onSurface
    } else {
        palette.secondaryText.copy(alpha = 0.4f)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

private sealed interface TocEntry {
    val key: String

    data class VolumeHeader(
        val volumeIndex: Int,
        val volumeId: String,
        val title: String,
        val chapterCount: Int,
    ) : TocEntry {
        override val key: String = "vol:$volumeIndex:$volumeId"
    }

    data class ChapterRow(
        val volumeIndex: Int,
        val chapterIndex: Int,
        val ref: FlatChapterRef,
    ) : TocEntry {
        override val key: String = "ch:$volumeIndex:$chapterIndex:${ref.chapterId}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    novel: NovelDto?,
    currentChapterId: String,
    cachedChapterIds: Set<String>,
    palette: ReaderPalette,
    onSelectChapter: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tocEntries = remember(novel) {
        buildList {
            novel?.volumes.orEmpty().forEachIndexed { volumeIndex, volume ->
                if (volume.chapters.isNotEmpty()) {
                    add(
                        TocEntry.VolumeHeader(
                            volumeIndex = volumeIndex,
                            volumeId = volume.id,
                            title = volume.title,
                            chapterCount = volume.chapters.size,
                        ),
                    )
                    volume.chapters.forEachIndexed { chapterIndex, chapter ->
                        add(
                            TocEntry.ChapterRow(
                                volumeIndex = volumeIndex,
                                chapterIndex = chapterIndex,
                                ref = FlatChapterRef(
                                    volumeId = volume.id,
                                    volumeTitle = volume.title,
                                    chapterId = chapter.id,
                                    chapterTitle = chapter.title,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    val totalChapters = remember(tocEntries) {
        tocEntries.count { it is TocEntry.ChapterRow }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(tocEntries, currentChapterId) {
        val currentIdx = tocEntries.indexOfFirst { entry ->
            entry is TocEntry.ChapterRow && entry.ref.chapterId == currentChapterId
        }
        if (currentIdx >= 0) {
            listState.scrollToItem((currentIdx - 2).coerceAtLeast(0))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "章节目录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onSurface,
                    )
                    val novelTitle = novel?.title.orEmpty()
                    if (novelTitle.isNotBlank()) {
                        Text(
                            text = novelTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = "共 $totalChapters 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.secondaryText,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = palette.secondaryText.copy(alpha = 0.16f))

            if (tocEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (novel == null) "正在加载目录…" else "暂无可用章节目录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondaryText,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items = tocEntries, key = { it.key }) { entry ->
                        when (entry) {
                            is TocEntry.VolumeHeader -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(palette.background.copy(alpha = 0.5f))
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.title.ifBlank { "未命名分卷" },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${entry.chapterCount} 章",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.secondaryText,
                                    )
                                }
                            }

                            is TocEntry.ChapterRow -> {
                                val isCurrent = entry.ref.chapterId == currentChapterId
                                val isCached = entry.ref.chapterId in cachedChapterIds
                                val highlightColor = MaterialTheme.colorScheme.primary
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isCurrent) {
                                                highlightColor.copy(alpha = 0.12f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .clickable {
                                            onSelectChapter(entry.ref.chapterId)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 20.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = entry.ref.chapterTitle.ifBlank { "未命名章节" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrent) highlightColor else palette.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCached) {
                                        Icon(
                                            imageVector = Icons.Rounded.CloudDone,
                                            contentDescription = "已缓存",
                                            tint = if (isCurrent) {
                                                highlightColor
                                            } else {
                                                palette.secondaryText.copy(alpha = 0.7f)
                                            },
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    if (isCurrent) {
                                        Text(
                                            text = "当前",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = highlightColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "阅读排版设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface,
                )
                TextButton(
                    onClick = { onUpdateSettings { ReaderSettings() } },
                ) {
                    Text("恢复默认")
                }
            }

            // Theme selector (3 themes)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "阅读主题",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.secondaryText,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeOptionButton(
                        theme = ReaderTheme.LIGHT,
                        label = "明亮",
                        selected = settings.theme == ReaderTheme.LIGHT,
                        onClick = { onUpdateSettings { it.copy(theme = ReaderTheme.LIGHT) } },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionButton(
                        theme = ReaderTheme.SEPIA,
                        label = "羊皮纸",
                        selected = settings.theme == ReaderTheme.SEPIA,
                        onClick = { onUpdateSettings { it.copy(theme = ReaderTheme.SEPIA) } },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionButton(
                        theme = ReaderTheme.OLED,
                        label = "OLED 纯黑",
                        selected = settings.theme == ReaderTheme.OLED,
                        onClick = { onUpdateSettings { it.copy(theme = ReaderTheme.OLED) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Font size slider (12..36 sp)
            SliderSettingRow(
                title = "字号",
                valueLabel = "${settings.fontSizeSp.roundToInt()} sp",
                value = settings.fontSizeSp,
                valueRange = ReaderSettings.MIN_FONT_SIZE_SP..ReaderSettings.MAX_FONT_SIZE_SP,
                steps = (ReaderSettings.MAX_FONT_SIZE_SP - ReaderSettings.MIN_FONT_SIZE_SP).toInt() - 1,
                palette = palette,
                onValueChange = { raw ->
                    val snapped = raw.roundToInt().toFloat()
                    onUpdateSettings { it.copy(fontSizeSp = snapped) }
                },
            )

            // Line height slider (1.2..2.8)
            SliderSettingRow(
                title = "行高倍数",
                valueLabel = "${formatOneDecimal(settings.lineHeightMultiplier)}x",
                value = settings.lineHeightMultiplier,
                valueRange = ReaderSettings.MIN_LINE_HEIGHT_MULTIPLIER..ReaderSettings.MAX_LINE_HEIGHT_MULTIPLIER,
                steps = 15,
                palette = palette,
                onValueChange = { raw ->
                    val snapped = (raw * 10f).roundToInt() / 10f
                    onUpdateSettings { it.copy(lineHeightMultiplier = snapped) }
                },
            )

            // Horizontal padding slider (8..64 dp)
            SliderSettingRow(
                title = "左右页边距",
                valueLabel = "${settings.horizontalPaddingDp} dp",
                value = settings.horizontalPaddingDp.toFloat(),
                valueRange = ReaderSettings.MIN_HORIZONTAL_PADDING_DP.toFloat()..ReaderSettings.MAX_HORIZONTAL_PADDING_DP.toFloat(),
                steps = ((ReaderSettings.MAX_HORIZONTAL_PADDING_DP - ReaderSettings.MIN_HORIZONTAL_PADDING_DP) / 2) - 1,
                palette = palette,
                onValueChange = { raw ->
                    val snapped = (raw / 2f).roundToInt() * 2
                    onUpdateSettings { it.copy(horizontalPaddingDp = snapped) }
                },
            )

            HorizontalDivider(color = palette.secondaryText.copy(alpha = 0.16f))

            // First-line indent toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "首行缩进",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.onSurface,
                    )
                    Text(
                        text = "段落开头自动缩进两个全角空格",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryText,
                    )
                }
                Switch(
                    checked = settings.firstLineIndent,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(firstLineIndent = checked) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionButton(
    theme: ReaderTheme,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val optionPalette = theme.toPalette()
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        optionPalette.secondaryText.copy(alpha = 0.35f)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = optionPalette.background,
        contentColor = optionPalette.text,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = optionPalette.text,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SliderSettingRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    palette: ReaderPalette,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onSurface,
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = palette.secondaryText,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun themeIcon(theme: ReaderTheme): ImageVector {
    return when (theme) {
        ReaderTheme.LIGHT -> Icons.Rounded.LightMode
        ReaderTheme.SEPIA -> Icons.Rounded.Palette
        ReaderTheme.OLED -> Icons.Rounded.DarkMode
    }
}

private fun themeShortLabel(theme: ReaderTheme): String {
    return when (theme) {
        ReaderTheme.LIGHT -> "明亮"
        ReaderTheme.SEPIA -> "羊皮纸"
        ReaderTheme.OLED -> "纯黑"
    }
}

private fun formatOneDecimal(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    val whole = tenths / 10
    val frac = abs(tenths % 10)
    return "$whole.$frac"
}
