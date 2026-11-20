package io.acanthis.app.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.acanthis.app.core.network.ExploreSectionDto
import io.acanthis.app.core.network.ExploreSectionMoreTargetDto
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onOpenNovel: (platform: String, novelId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ExploreViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxSize()) {
        if (!uiState.isServerConfigured) {
            UnconfiguredServerCard(
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }

        // Top search bar
        ExploreSearchBar(
            query = uiState.searchQuery,
            onQueryChange = viewModel::onSearchQueryChange,
            onSubmit = {
                keyboardController?.hide()
                viewModel.submitSearch()
            },
            onClear = {
                keyboardController?.hide()
                viewModel.clearSearch()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // Multi-source selector (visible when multiple sources exist)
        val exploreSources = uiState.sources.filter { "explore" in it.capabilities || it.id == uiState.selectedPlatform }
        if (exploreSources.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                items(exploreSources, key = { it.id }) { source ->
                    FilterChip(
                        selected = source.id == uiState.selectedPlatform,
                        onClick = { viewModel.selectPlatform(source.id) },
                        label = { Text(source.name) },
                    )
                }
            }
        }

        // Browse tabs (Home / Rankings / Catalog)
        val browseModes = uiState.availableBrowseModes
        val selectedTabIndex = browseModes.indexOf(uiState.activeBrowseMode).coerceAtLeast(0)
        SecondaryTabRow(selectedTabIndex = selectedTabIndex) {
            browseModes.forEach { tab ->
                val isSelected = uiState.mode != ExploreMode.SEARCH && uiState.activeBrowseMode == tab
                Tab(
                    selected = isSelected,
                    onClick = { viewModel.selectBrowseTab(tab) },
                    text = {
                        Text(
                            text = when (tab) {
                                ExploreMode.HOME -> "首页"
                                ExploreMode.RANKINGS -> "榜单"
                                ExploreMode.CATALOG -> "分类"
                                ExploreMode.SEARCH -> "搜索"
                            },
                        )
                    },
                )
            }
        }

        // Cooldown banner (global so user always sees remaining seconds)
        if (uiState.searchCooldownSeconds > 0) {
            SearchCooldownBanner(
                remainingSeconds = uiState.searchCooldownSeconds,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Content area
        when {
            uiState.mode == ExploreMode.SEARCH -> {
                SearchResultsContent(
                    uiState = uiState,
                    onOpenNovel = onOpenNovel,
                    onRetry = viewModel::submitSearch,
                )
            }

            uiState.isLoadingSchema && uiState.schema == null -> {
                CenteredLoadingState(message = "正在获取书源发现配置…")
            }

            uiState.schemaError != null && uiState.schema == null -> {
                val schemaError = uiState.schemaError
                if (schemaError != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        ExploreErrorCard(
                            error = schemaError,
                            onRetry = viewModel::retryBootstrap,
                        )
                    }
                }
            }

            else -> {
                when (uiState.mode) {
                    ExploreMode.HOME -> ExploreHomeContent(
                        uiState = uiState,
                        onSelectChannel = viewModel::selectChannel,
                        onRefresh = viewModel::refreshCurrentTab,
                        onOpenMore = viewModel::openMoreTarget,
                        onOpenNovel = onOpenNovel,
                    )

                    ExploreMode.RANKINGS -> ExploreRankingsContent(
                        uiState = uiState,
                        onSelectRank = viewModel::selectRank,
                        onSetFullOnly = viewModel::setRankFullOnly,
                        onRefresh = viewModel::refreshCurrentTab,
                        onLoadMore = viewModel::loadNextRankingsPage,
                        onOpenNovel = onOpenNovel,
                    )

                    ExploreMode.CATALOG -> ExploreCatalogContent(
                        uiState = uiState,
                        onSelectFilter = viewModel::selectCatalogFilter,
                        onResetFilters = viewModel::resetCatalogFilters,
                        onRefresh = viewModel::refreshCurrentTab,
                        onLoadMore = viewModel::loadNextCatalogPage,
                        onOpenNovel = onOpenNovel,
                    )

                    ExploreMode.SEARCH -> Unit
                }
            }
        }
    }
}

@Composable
private fun UnconfiguredServerCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "尚未配置 Acanthis 服务器",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "配置服务器地址后即可浏览推荐频道、排行榜、分类书库并搜索轻小说。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("前往设置")
            }
        }
    }
}

@Composable
private fun ExploreSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("搜索书名或关键词…") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "搜索",
            )
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清空搜索",
                        )
                    }
                }
                TextButton(
                    enabled = query.isNotBlank(),
                    onClick = onSubmit,
                ) {
                    Text("搜索")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreHomeContent(
    uiState: ExploreUiState,
    onSelectChannel: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenMore: (ExploreSectionMoreTargetDto) -> Unit,
    onOpenNovel: (String, String) -> Unit,
) {
    val channels = uiState.schema?.channels.orEmpty()

    PullToRefreshBox(
        isRefreshing = uiState.isLoadingHome && uiState.homeData != null,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (channels.isNotEmpty()) {
                item(key = "home-channels") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(channels, key = { it.id }) { channel ->
                            FilterChip(
                                selected = channel.id == uiState.selectedChannel,
                                onClick = { onSelectChannel(channel.id) },
                                label = { Text(channel.label) },
                            )
                        }
                    }
                }
            }

            if (uiState.isLoadingHome && uiState.homeData == null) {
                item(key = "home-loading") {
                    CenteredLoadingState(
                        message = "正在加载推荐内容…",
                        modifier = Modifier.fillParentMaxHeight(0.6f),
                    )
                }
            } else if (uiState.homeError != null && uiState.homeData == null) {
                item(key = "home-error") {
                    ExploreErrorCard(
                        error = uiState.homeError,
                        onRetry = onRefresh,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                uiState.homeError?.let { error ->
                    item(key = "home-inline-error") {
                        ExploreErrorCard(
                            error = error,
                            onRetry = onRefresh,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                val sections = uiState.homeData?.sections.orEmpty()
                if (sections.isEmpty() && !uiState.isLoadingHome) {
                    item(key = "home-empty") {
                        EmptyExploreState(
                            message = "当前频道暂无推荐内容",
                            onRefresh = onRefresh,
                        )
                    }
                } else {
                    sections.forEach { section ->
                        item(key = "section-header-${section.id}") {
                            SectionHeaderRow(
                                section = section,
                                onOpenMore = onOpenMore,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        when (section.layout.lowercase()) {
                            "featured" -> {
                                item(key = "section-featured-${section.id}") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        items(
                                            items = section.items,
                                            key = { "${section.id}:${it.id}" },
                                        ) { novel ->
                                            ExploreFeaturedNovelCard(
                                                item = novel,
                                                onClick = { onOpenNovel(novel.platform, novel.id) },
                                            )
                                        }
                                    }
                                }
                            }

                            "rank" -> {
                                itemsIndexed(
                                    items = section.items,
                                    key = { idx, novel -> "${section.id}:rank:$idx:${novel.id}" },
                                ) { idx, novel ->
                                    ExploreRankNovelCard(
                                        item = novel,
                                        displayRank = novel.rank ?: (idx + 1),
                                        onClick = { onOpenNovel(novel.platform, novel.id) },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }

                            "updates" -> {
                                itemsIndexed(
                                    items = section.items,
                                    key = { idx, novel -> "${section.id}:upd:$idx:${novel.id}" },
                                ) { _, novel ->
                                    ExploreUpdateNovelRow(
                                        item = novel,
                                        onClick = { onOpenNovel(novel.platform, novel.id) },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }

                            else -> {
                                val rows = section.items.chunked(3)
                                itemsIndexed(
                                    items = rows,
                                    key = { rowIndex, _ -> "${section.id}:grid:$rowIndex" },
                                ) { _, rowItems ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowItems.forEach { novel ->
                                            ExploreGridNovelCard(
                                                item = novel,
                                                onClick = { onOpenNovel(novel.platform, novel.id) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
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
}

@Composable
private fun SectionHeaderRow(
    section: ExploreSectionDto,
    onOpenMore: (ExploreSectionMoreTargetDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        section.more?.let { target ->
            TextButton(onClick = { onOpenMore(target) }) {
                Text("更多")
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreRankingsContent(
    uiState: ExploreUiState,
    onSelectRank: (String) -> Unit,
    onSetFullOnly: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenNovel: (String, String) -> Unit,
) {
    val rankings = uiState.schema?.rankings.orEmpty()
    val hasAnyAll = rankings.any { it.supportsAll }
    val hasAnyFull = rankings.any { it.supportsFull }
    val listState = rememberLazyListState()

    val shouldTriggerLoadMore by remember(
        uiState.rankingHasNextPage,
        uiState.isLoadingRankings,
        uiState.isLoadingMoreRankings,
        uiState.rankingItems.size,
    ) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            uiState.rankingHasNextPage &&
                !uiState.isLoadingRankings &&
                !uiState.isLoadingMoreRankings &&
                uiState.rankingItems.isNotEmpty() &&
                total > 0 &&
                lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldTriggerLoadMore) {
        if (shouldTriggerLoadMore) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoadingRankings && uiState.rankingItems.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "ranking-controls") {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (rankings.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(rankings, key = { it.id }) { option ->
                                FilterChip(
                                    selected = option.id == uiState.selectedRank,
                                    onClick = { onSelectRank(option.id) },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }

                    if (hasAnyAll && hasAnyFull) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !uiState.fullOnly,
                                onClick = { onSetFullOnly(false) },
                                label = { Text("全部作品") },
                            )
                            FilterChip(
                                selected = uiState.fullOnly,
                                onClick = { onSetFullOnly(true) },
                                label = { Text("仅看完结") },
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            if (uiState.isLoadingRankings && uiState.rankingItems.isEmpty()) {
                item(key = "rankings-loading") {
                    CenteredLoadingState(
                        message = "正在加载榜单…",
                        modifier = Modifier.fillParentMaxHeight(0.55f),
                    )
                }
            } else if (uiState.rankingsError != null && uiState.rankingItems.isEmpty()) {
                item(key = "rankings-error") {
                    ExploreErrorCard(
                        error = uiState.rankingsError,
                        onRetry = onRefresh,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else if (uiState.rankingItems.isEmpty()) {
                item(key = "rankings-empty") {
                    EmptyExploreState(
                        message = "暂无榜单数据",
                        onRefresh = onRefresh,
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.rankingItems,
                    key = { idx, novel -> "ranking:$idx:${novel.id}" },
                ) { idx, novel ->
                    ExploreRankNovelCard(
                        item = novel,
                        displayRank = novel.rank ?: (idx + 1),
                        onClick = { onOpenNovel(novel.platform, novel.id) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                uiState.rankingsError?.let { error ->
                    item(key = "rankings-more-error") {
                        ExploreErrorCard(
                            error = error,
                            onRetry = onLoadMore,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                item(key = "rankings-footer") {
                    PaginationFooter(
                        isLoadingMore = uiState.isLoadingMoreRankings,
                        hasNextPage = uiState.rankingHasNextPage,
                        currentPage = uiState.rankingPage,
                        totalPages = uiState.rankingTotalPages,
                        onLoadMore = onLoadMore,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreCatalogContent(
    uiState: ExploreUiState,
    onSelectFilter: (String, String) -> Unit,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenNovel: (String, String) -> Unit,
) {
    val filterGroups = uiState.schema?.catalogFilters.orEmpty()
    val listState = rememberLazyListState()

    val shouldTriggerLoadMore by remember(
        uiState.catalogHasNextPage,
        uiState.isLoadingCatalog,
        uiState.isLoadingMoreCatalog,
        uiState.catalogItems.size,
    ) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            uiState.catalogHasNextPage &&
                !uiState.isLoadingCatalog &&
                !uiState.isLoadingMoreCatalog &&
                uiState.catalogItems.isNotEmpty() &&
                total > 0 &&
                lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldTriggerLoadMore) {
        if (shouldTriggerLoadMore) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoadingCatalog && uiState.catalogItems.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (filterGroups.isNotEmpty()) {
                item(key = "catalog-filters") {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        filterGroups.forEach { group ->
                            val currentVal = uiState.selectedFilters[group.key] ?: group.defaultValue
                            CatalogFilterGroupRow(
                                group = group,
                                selectedValue = currentVal,
                                onSelect = { value -> onSelectFilter(group.key, value) },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onResetFilters) {
                                Text("重置筛选")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            if (uiState.isLoadingCatalog && uiState.catalogItems.isEmpty()) {
                item(key = "catalog-loading") {
                    CenteredLoadingState(
                        message = "正在加载分类书库…",
                        modifier = Modifier.fillParentMaxHeight(0.5f),
                    )
                }
            } else if (uiState.catalogError != null && uiState.catalogItems.isEmpty()) {
                item(key = "catalog-error") {
                    ExploreErrorCard(
                        error = uiState.catalogError,
                        onRetry = onRefresh,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else if (uiState.catalogItems.isEmpty()) {
                item(key = "catalog-empty") {
                    EmptyExploreState(
                        message = "当前筛选条件下暂无小说",
                        onRefresh = onRefresh,
                    )
                }
            } else {
                val rows = uiState.catalogItems.chunked(3)
                itemsIndexed(
                    items = rows,
                    key = { rowIdx, _ -> "catalog-row:$rowIdx" },
                ) { _, rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { novel ->
                            ExploreGridNovelCard(
                                item = novel,
                                onClick = { onOpenNovel(novel.platform, novel.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                uiState.catalogError?.let { error ->
                    item(key = "catalog-more-error") {
                        ExploreErrorCard(
                            error = error,
                            onRetry = onLoadMore,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                item(key = "catalog-footer") {
                    PaginationFooter(
                        isLoadingMore = uiState.isLoadingMoreCatalog,
                        hasNextPage = uiState.catalogHasNextPage,
                        currentPage = uiState.catalogPage,
                        totalPages = uiState.catalogTotalPages,
                        onLoadMore = onLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: ExploreUiState,
    onOpenNovel: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        uiState.isSearching -> {
            CenteredLoadingState(message = "正在搜索「${uiState.submittedQuery}」…")
        }

        uiState.searchError != null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                ExploreErrorCard(
                    error = uiState.searchError,
                    onRetry = onRetry,
                )
            }
        }

        uiState.searchResults.isEmpty() -> {
            EmptyExploreState(
                message = when {
                    uiState.searchCooldownSeconds > 0 ->
                        "搜索冷却中，请等待 ${uiState.searchCooldownSeconds} 秒后重试"

                    uiState.submittedQuery.isNotBlank() ->
                        "未找到与「${uiState.submittedQuery}」相关的小说"

                    else ->
                        "输入关键词开始搜索"
                },
                onRefresh = if (uiState.submittedQuery.isNotBlank()) onRetry else null,
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "search-header") {
                    Text(
                        text = "「${uiState.submittedQuery}」共找到 ${uiState.searchResults.size} 部作品",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(
                    items = uiState.searchResults,
                    key = { "${it.platform}:${it.id}" },
                ) { result ->
                    SearchResultCard(
                        item = result,
                        onClick = { onOpenNovel(result.platform, result.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyExploreState(
    message: String,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRefresh != null) {
                TextButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("刷新重试")
                }
            }
        }
    }
}
