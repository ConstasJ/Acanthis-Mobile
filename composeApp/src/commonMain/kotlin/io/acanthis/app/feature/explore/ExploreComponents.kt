package io.acanthis.app.feature.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.acanthis.app.core.network.ExploreFilterGroupDto
import io.acanthis.app.core.network.ExploreNovelItemDto
import io.acanthis.app.core.network.NovelSearchResultDto

@Composable
internal fun NovelCoverThumbnail(
    coverUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp),
        )
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun InBookshelfBadge(
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        modifier = modifier,
    ) {
        Text(
            text = "已在书架",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SelectionCheckBadge(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (isSelected) "已选中" else "未选中",
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(2.dp)
                .size(20.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExploreGridNovelCard(
    item: ExploreNovelItemDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isInBookshelf: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val cardBorderModifier = if (isSelectionMode && isSelected) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(10.dp),
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(cardBorderModifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            NovelCoverThumbnail(
                coverUrl = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
            val statusBadge = formatNovelStatusLabel(item.status)
            if (!isSelectionMode && statusBadge != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        text = statusBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (isSelectionMode) {
                SelectionCheckBadge(
                    isSelected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                )
            }
            if (isInBookshelf) {
                InBookshelfBadge(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = item.author?.takeIf { it.isNotBlank() }
            ?: item.category?.takeIf { it.isNotBlank() }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExploreFeaturedNovelCard(
    item: ExploreNovelItemDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isInBookshelf: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.width(260.dp),
        border = if (isSelectionMode && isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    NovelCoverThumbnail(
                        coverUrl = item.coverUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .width(78.dp)
                            .aspectRatio(3f / 4f),
                    )
                    if (isInBookshelf) {
                        InBookshelfBadge(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = if (isSelectionMode) 24.dp else 0.dp),
                    )
                    item.author?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val meta = listOfNotNull(
                        item.category?.takeIf { it.isNotBlank() },
                        formatNovelStatusLabel(item.status),
                        item.wordCount?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (isSelectionMode) {
                SelectionCheckBadge(
                    isSelected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExploreRankNovelCard(
    item: ExploreNovelItemDto,
    displayRank: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isInBookshelf: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        border = if (isSelectionMode && isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (displayRank != null) {
                    val badgeBg = when (displayRank) {
                        1 -> MaterialTheme.colorScheme.primary
                        2 -> MaterialTheme.colorScheme.secondary
                        3 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val badgeFg = when (displayRank) {
                        1 -> MaterialTheme.colorScheme.onPrimary
                        2 -> MaterialTheme.colorScheme.onSecondary
                        3 -> MaterialTheme.colorScheme.onTertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeBg,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = displayRank.toString(),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = badgeFg,
                            )
                        }
                    }
                }

                NovelCoverThumbnail(
                    coverUrl = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(64.dp)
                        .aspectRatio(3f / 4f),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = if (isSelectionMode) 24.dp else 0.dp),
                        )
                        if (isInBookshelf && !isSelectionMode) {
                            InBookshelfBadge()
                        }
                    }
                    val metaParts = buildList {
                        item.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                        item.category?.takeIf { it.isNotBlank() }?.let { add(it) }
                        formatNovelStatusLabel(item.status)?.let { add(it) }
                        item.wordCount?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.latestChapter?.takeIf { it.isNotBlank() }?.let { latest ->
                        Text(
                            text = "最新：$latest",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isInBookshelf && isSelectionMode) {
                        InBookshelfBadge()
                    }
                }
            }
            if (isSelectionMode) {
                SelectionCheckBadge(
                    isSelected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExploreUpdateNovelRow(
    item: ExploreNovelItemDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isInBookshelf: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = if (isSelectionMode && isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovelCoverThumbnail(
                    coverUrl = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(44.dp)
                        .aspectRatio(3f / 4f),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = if (isSelectionMode) 24.dp else 0.dp),
                        )
                        if (isInBookshelf && !isSelectionMode) {
                            InBookshelfBadge()
                        }
                    }
                    val chapterOrMeta = item.latestChapter?.takeIf { it.isNotBlank() }
                        ?: item.author?.takeIf { it.isNotBlank() }
                    if (chapterOrMeta != null) {
                        Text(
                            text = chapterOrMeta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val footer = listOfNotNull(
                        item.author?.takeIf { it.isNotBlank() && it != chapterOrMeta },
                        item.category?.takeIf { it.isNotBlank() },
                        item.updatedAt?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (footer.isNotEmpty()) {
                        Text(
                            text = footer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (isSelectionMode) {
                SelectionCheckBadge(
                    isSelected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchResultCard(
    item: NovelSearchResultDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isInBookshelf: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        border = if (isSelectionMode && isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovelCoverThumbnail(
                    coverUrl = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(56.dp)
                        .aspectRatio(3f / 4f),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = if (isSelectionMode) 24.dp else 0.dp),
                        )
                        if (isInBookshelf && !isSelectionMode) {
                            InBookshelfBadge()
                        }
                    }
                    Text(
                        text = "${item.platform} · #${item.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isInBookshelf && isSelectionMode) {
                        InBookshelfBadge()
                    }
                }
            }
            if (isSelectionMode) {
                SelectionCheckBadge(
                    isSelected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
internal fun CatalogFilterDropdownChip(
    group: ExploreFilterGroupDto,
    selectedValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedLabel = group.options.firstOrNull { it.value == selectedValue }?.label
        ?: selectedValue.ifBlank { "全部" }
    val isNonDefault = selectedValue.isNotBlank() && selectedValue != group.defaultValue
    FilterChip(
        selected = isNonDefault,
        onClick = onClick,
        label = {
            Text(
                text = "${group.label}: $selectedLabel",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = "选择${group.label}",
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun CatalogFilterGroupRow(
    group: ExploreFilterGroupDto,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = group.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = group.options,
                key = { "${group.key}:${it.value}" },
            ) { option ->
                FilterChip(
                    selected = option.value == selectedValue,
                    onClick = { onSelect(option.value) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
internal fun ExploreErrorCard(
    error: ExploreErrorState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val containerColor = if (error.isChallengeBlocked || error.statusCode == 503) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (error.isChallengeBlocked || error.statusCode == 503) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = if (error.isChallengeBlocked || error.statusCode == 503) {
                        Icons.Rounded.WarningAmber
                    } else {
                        Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = contentColor,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (error.isChallengeBlocked || error.statusCode == 503) {
                            "上游站点暂时限流或触发安全校验 (HTTP ${error.statusCode ?: 503})"
                        } else {
                            "加载失败"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                    )
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    val retryAfter = error.retryAfterSeconds
                    if (retryAfter != null && retryAfter > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "建议 ${retryAfter} 秒后重试",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                    OutlinedButton(onClick = onSecondaryAction) {
                        Text(text = secondaryActionLabel)
                    }
                }
                Button(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val retryAfter = error.retryAfterSeconds
                    Text(
                        text = if (retryAfter != null && retryAfter > 0) {
                            "立即重试 (${retryAfter}s)"
                        } else {
                            "重试"
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchCooldownBanner(
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "搜索频率受限，请在 ${remainingSeconds} 秒后再试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
internal fun PaginationFooter(
    isLoadingMore: Boolean,
    hasNextPage: Boolean,
    currentPage: Int,
    totalPages: Int?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在加载下一页…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            hasNextPage -> {
                OutlinedButton(onClick = onLoadMore) {
                    val suffix = totalPages?.let { " ($currentPage/$it)" }.orEmpty()
                    Text("加载更多$suffix")
                }
            }

            else -> {
                val pageInfo = totalPages?.let { "第 $currentPage/$it 页 · " }.orEmpty()
                Text(
                    text = "${pageInfo}已加载全部内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatNovelStatusLabel(status: String?): String? {
    return when (status?.trim()?.lowercase()) {
        "completed" -> "已完结"
        "ongoing" -> "连载中"
        else -> null
    }
}
