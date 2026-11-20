package io.acanthis.app.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExploreNovelItemDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.ExploreSectionMoreTargetDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.stripTrailingV1Prefix
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.ExploreRepository
import io.acanthis.app.data.SearchCooldownException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExploreMode {
    HOME,
    RANKINGS,
    CATALOG,
    SEARCH,
}

data class ExploreErrorState(
    val message: String,
    val statusCode: Int? = null,
    val retryAfterSeconds: Int? = null,
    val isChallengeBlocked: Boolean = false,
)

data class ExploreUiState(
    val isServerConfigured: Boolean = false,
    val sources: List<NovelSourceDescriptor> = emptyList(),
    val selectedPlatform: String = DEFAULT_PLATFORM,
    val mode: ExploreMode = ExploreMode.HOME,
    val activeBrowseMode: ExploreMode = ExploreMode.HOME,
    val schema: ExploreSchemaDto? = null,
    val isLoadingSchema: Boolean = false,
    val schemaError: ExploreErrorState? = null,
    // HOME
    val selectedChannel: String = DEFAULT_CHANNEL,
    val homeData: ExploreHomeDataDto? = null,
    val isLoadingHome: Boolean = false,
    val homeError: ExploreErrorState? = null,
    // RANKINGS
    val selectedRank: String = "",
    val fullOnly: Boolean = false,
    val rankingItems: List<ExploreNovelItemDto> = emptyList(),
    val rankingPage: Int = 1,
    val rankingTotalPages: Int? = null,
    val rankingHasNextPage: Boolean = false,
    val isLoadingRankings: Boolean = false,
    val isLoadingMoreRankings: Boolean = false,
    val rankingsError: ExploreErrorState? = null,
    // CATALOG
    val selectedFilters: Map<String, String> = emptyMap(),
    val catalogItems: List<ExploreNovelItemDto> = emptyList(),
    val catalogPage: Int = 1,
    val catalogTotalPages: Int? = null,
    val catalogHasNextPage: Boolean = false,
    val isLoadingCatalog: Boolean = false,
    val isLoadingMoreCatalog: Boolean = false,
    val catalogError: ExploreErrorState? = null,
    // SEARCH
    val searchQuery: String = "",
    val submittedQuery: String = "",
    val searchResults: List<NovelSearchResultDto> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: ExploreErrorState? = null,
    val searchCooldownSeconds: Int = 0,
) {
    val availableBrowseModes: List<ExploreMode>
        get() {
            val currentSchema = schema ?: return listOf(
                ExploreMode.HOME,
                ExploreMode.RANKINGS,
                ExploreMode.CATALOG,
            )
            return buildList {
                if (currentSchema.supportsHome) add(ExploreMode.HOME)
                if (currentSchema.rankings.isNotEmpty()) add(ExploreMode.RANKINGS)
                if (currentSchema.catalogFilters.isNotEmpty()) add(ExploreMode.CATALOG)
            }.ifEmpty { listOf(ExploreMode.HOME) }
        }

    companion object {
        const val DEFAULT_PLATFORM = "linovelib"
        const val DEFAULT_CHANNEL = "home"
    }
}

class ExploreViewModel(
    private val exploreRepository: ExploreRepository,
    private val serverConfigStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private var bootstrapJob: Job? = null
    private var homeJob: Job? = null
    private var rankingsJob: Job? = null
    private var catalogJob: Job? = null
    private var searchJob: Job? = null
    private var searchCooldownJob: Job? = null
    private var retryCountdownJob: Job? = null

    init {
        observeServerConfig()
    }

    private fun observeServerConfig() {
        viewModelScope.launch {
            serverConfigStore.configFlow
                .map { config ->
                    stripTrailingV1Prefix(config.apiBaseUrl) to config.authToken.trim()
                }
                .distinctUntilChanged()
                .collect { (normalizedBaseUrl, _) ->
                    if (normalizedBaseUrl.isEmpty()) {
                        cancelAllRequests()
                        _uiState.value = ExploreUiState(isServerConfigured = false)
                    } else {
                        _uiState.update { it.copy(isServerConfigured = true) }
                        bootstrapSourcesAndSchema(forceRefresh = false)
                    }
                }
        }
    }

    fun retryBootstrap() {
        bootstrapSourcesAndSchema(forceRefresh = true)
    }

    fun selectPlatform(platformId: String) {
        val trimmed = platformId.trim()
        if (trimmed.isEmpty() || trimmed == _uiState.value.selectedPlatform) {
            return
        }
        cancelContentRequests()
        _uiState.update { state ->
            state.copy(
                selectedPlatform = trimmed,
                schema = null,
                schemaError = null,
                selectedChannel = ExploreUiState.DEFAULT_CHANNEL,
                homeData = null,
                homeError = null,
                selectedRank = "",
                fullOnly = false,
                rankingItems = emptyList(),
                rankingPage = 1,
                rankingTotalPages = null,
                rankingHasNextPage = false,
                rankingsError = null,
                selectedFilters = emptyMap(),
                catalogItems = emptyList(),
                catalogPage = 1,
                catalogTotalPages = null,
                catalogHasNextPage = false,
                catalogError = null,
                searchResults = emptyList(),
                searchError = null,
            )
        }
        loadSchemaForPlatform(trimmed, forceRefresh = false)
        if (_uiState.value.mode == ExploreMode.SEARCH && _uiState.value.submittedQuery.isNotBlank()) {
            executeSearch(_uiState.value.submittedQuery)
        }
    }

    fun selectBrowseTab(tab: ExploreMode) {
        if (tab == ExploreMode.SEARCH) return
        val previous = _uiState.value
        val wasSearching = previous.mode == ExploreMode.SEARCH
        if (wasSearching) {
            searchJob?.cancel()
        }
        _uiState.update { state ->
            state.copy(
                mode = tab,
                activeBrowseMode = tab,
                searchQuery = if (wasSearching) "" else state.searchQuery,
                submittedQuery = if (wasSearching) "" else state.submittedQuery,
                searchResults = if (wasSearching) emptyList() else state.searchResults,
                searchError = if (wasSearching) null else state.searchError,
                isSearching = if (wasSearching) false else state.isSearching,
            )
        }
        ensureBrowseTabLoaded(tab, forceReload = false)
    }

    fun selectChannel(channelId: String) {
        val clean = channelId.trim()
        if (clean.isEmpty()) return
        if (clean == _uiState.value.selectedChannel && _uiState.value.homeData != null && _uiState.value.homeError == null) {
            return
        }
        _uiState.update {
            it.copy(
                mode = ExploreMode.HOME,
                activeBrowseMode = ExploreMode.HOME,
                selectedChannel = clean,
            )
        }
        loadHome(channel = clean)
    }

    fun refreshCurrentTab() {
        val state = _uiState.value
        if (!state.isServerConfigured) return
        if (state.schema == null) {
            bootstrapSourcesAndSchema(forceRefresh = true)
            return
        }
        when (state.mode) {
            ExploreMode.HOME -> loadHome(channel = state.selectedChannel)
            ExploreMode.RANKINGS -> loadRankings(
                rank = state.selectedRank,
                fullOnly = state.fullOnly,
                page = 1,
                append = false,
            )
            ExploreMode.CATALOG -> loadCatalog(
                filters = state.selectedFilters,
                page = 1,
                append = false,
            )
            ExploreMode.SEARCH -> {
                if (state.submittedQuery.isNotBlank()) {
                    executeSearch(state.submittedQuery)
                }
            }
        }
    }

    fun openMoreTarget(target: ExploreSectionMoreTargetDto) {
        val schema = _uiState.value.schema
        when (target.type.trim().lowercase()) {
            "channel" -> {
                val requestedChannel = target.params["channel"]?.trim().orEmpty()
                val resolvedChannel = when {
                    requestedChannel.isNotEmpty() &&
                        (schema == null || schema.channels.any { it.id == requestedChannel }) -> requestedChannel
                    schema != null -> resolveDefaultHomeChannel(schema)
                    else -> ExploreUiState.DEFAULT_CHANNEL
                }
                _uiState.update {
                    it.copy(
                        mode = ExploreMode.HOME,
                        activeBrowseMode = ExploreMode.HOME,
                        selectedChannel = resolvedChannel,
                    )
                }
                loadHome(channel = resolvedChannel)
            }

            "ranking" -> {
                val targetFull = target.params["full"]?.trim()?.lowercase() in setOf("true", "1")
                val targetRank = target.params["rank"]?.trim().orEmpty()
                val (resolvedRank, resolvedFull) = if (schema != null) {
                    resolveRankingSelection(schema, targetRank, targetFull)
                } else {
                    targetRank to targetFull
                }
                _uiState.update {
                    it.copy(
                        mode = ExploreMode.RANKINGS,
                        activeBrowseMode = ExploreMode.RANKINGS,
                        selectedRank = resolvedRank,
                        fullOnly = resolvedFull,
                        rankingItems = emptyList(),
                        rankingPage = 1,
                        rankingTotalPages = null,
                        rankingHasNextPage = false,
                        rankingsError = null,
                    )
                }
                loadRankings(rank = resolvedRank, fullOnly = resolvedFull, page = 1, append = false)
            }

            "catalog" -> {
                val nextFilters = buildCatalogFiltersFromParams(schema, target.params)
                _uiState.update {
                    it.copy(
                        mode = ExploreMode.CATALOG,
                        activeBrowseMode = ExploreMode.CATALOG,
                        selectedFilters = nextFilters,
                        catalogItems = emptyList(),
                        catalogPage = 1,
                        catalogTotalPages = null,
                        catalogHasNextPage = false,
                        catalogError = null,
                    )
                }
                loadCatalog(filters = nextFilters, page = 1, append = false)
            }
        }
    }

    fun selectRank(rankId: String) {
        val schema = _uiState.value.schema ?: return
        val option = schema.rankings.firstOrNull { it.id == rankId } ?: return
        val clampedFull = when {
            _uiState.value.fullOnly && !option.supportsFull && option.supportsAll -> false
            !_uiState.value.fullOnly && !option.supportsAll && option.supportsFull -> true
            else -> _uiState.value.fullOnly
        }
        if (rankId == _uiState.value.selectedRank &&
            clampedFull == _uiState.value.fullOnly &&
            _uiState.value.rankingItems.isNotEmpty() &&
            _uiState.value.rankingsError == null
        ) {
            return
        }
        _uiState.update {
            it.copy(
                selectedRank = rankId,
                fullOnly = clampedFull,
                rankingItems = emptyList(),
                rankingPage = 1,
                rankingTotalPages = null,
                rankingHasNextPage = false,
                rankingsError = null,
            )
        }
        loadRankings(rank = rankId, fullOnly = clampedFull, page = 1, append = false)
    }

    fun setRankFullOnly(fullOnly: Boolean) {
        val schema = _uiState.value.schema ?: return
        val (resolvedRank, resolvedFull) = resolveRankingSelection(
            schema = schema,
            requestedRank = _uiState.value.selectedRank,
            requestedFull = fullOnly,
        )
        if (resolvedRank == _uiState.value.selectedRank &&
            resolvedFull == _uiState.value.fullOnly &&
            _uiState.value.rankingItems.isNotEmpty() &&
            _uiState.value.rankingsError == null
        ) {
            return
        }
        _uiState.update {
            it.copy(
                selectedRank = resolvedRank,
                fullOnly = resolvedFull,
                rankingItems = emptyList(),
                rankingPage = 1,
                rankingTotalPages = null,
                rankingHasNextPage = false,
                rankingsError = null,
            )
        }
        loadRankings(rank = resolvedRank, fullOnly = resolvedFull, page = 1, append = false)
    }

    fun loadNextRankingsPage() {
        val state = _uiState.value
        if (state.isLoadingRankings || state.isLoadingMoreRankings || !state.rankingHasNextPage) {
            return
        }
        if (state.selectedRank.isBlank()) return
        loadRankings(
            rank = state.selectedRank,
            fullOnly = state.fullOnly,
            page = state.rankingPage + 1,
            append = true,
        )
    }

    fun selectCatalogFilter(key: String, value: String) {
        val current = _uiState.value.selectedFilters
        if (current[key] == value && _uiState.value.catalogItems.isNotEmpty() && _uiState.value.catalogError == null) {
            return
        }
        val nextFilters = current + (key to value)
        _uiState.update {
            it.copy(
                selectedFilters = nextFilters,
                catalogItems = emptyList(),
                catalogPage = 1,
                catalogTotalPages = null,
                catalogHasNextPage = false,
                catalogError = null,
            )
        }
        loadCatalog(filters = nextFilters, page = 1, append = false)
    }

    fun resetCatalogFilters() {
        val schema = _uiState.value.schema ?: return
        val defaults = defaultCatalogFilters(schema)
        _uiState.update {
            it.copy(
                selectedFilters = defaults,
                catalogItems = emptyList(),
                catalogPage = 1,
                catalogTotalPages = null,
                catalogHasNextPage = false,
                catalogError = null,
            )
        }
        loadCatalog(filters = defaults, page = 1, append = false)
    }

    fun loadNextCatalogPage() {
        val state = _uiState.value
        if (state.isLoadingCatalog || state.isLoadingMoreCatalog || !state.catalogHasNextPage) {
            return
        }
        loadCatalog(
            filters = state.selectedFilters,
            page = state.catalogPage + 1,
            append = true,
        )
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank() && _uiState.value.mode == ExploreMode.SEARCH) {
            clearSearch()
        }
    }

    fun submitSearch() {
        val trimmed = _uiState.value.searchQuery.trim()
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        executeSearch(trimmed)
    }

    fun clearSearch() {
        searchJob?.cancel()
        val targetBrowseMode = _uiState.value.activeBrowseMode
        _uiState.update {
            it.copy(
                mode = targetBrowseMode,
                searchQuery = "",
                submittedQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                searchError = null,
            )
        }
        ensureBrowseTabLoaded(targetBrowseMode, forceReload = false)
    }

    private fun bootstrapSourcesAndSchema(forceRefresh: Boolean) {
        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingSchema = true,
                    schemaError = null,
                )
            }
            val sources = try {
                exploreRepository.getSources(forceRefresh = forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }

            val currentSelected = _uiState.value.selectedPlatform
            val resolvedPlatform = when {
                sources.any { it.id == currentSelected && "explore" in it.capabilities } -> currentSelected
                else -> sources.firstOrNull { "explore" in it.capabilities }?.id
                    ?: sources.firstOrNull()?.id
                    ?: ExploreUiState.DEFAULT_PLATFORM
            }

            _uiState.update {
                it.copy(
                    sources = sources,
                    selectedPlatform = resolvedPlatform,
                )
            }

            loadSchemaSuspend(resolvedPlatform, forceRefresh = forceRefresh)
        }
    }

    private fun loadSchemaForPlatform(platform: String, forceRefresh: Boolean) {
        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch {
            loadSchemaSuspend(platform = platform, forceRefresh = forceRefresh)
        }
    }

    private suspend fun loadSchemaSuspend(platform: String, forceRefresh: Boolean) {
        _uiState.update {
            it.copy(
                isLoadingSchema = true,
                schemaError = null,
            )
        }
        try {
            val schema = exploreRepository.getExploreSchema(
                platform = platform,
                forceRefresh = forceRefresh,
            )
            val defaultChannel = resolveDefaultHomeChannel(schema)
            val (defaultRank, defaultFull) = resolveRankingSelection(
                schema = schema,
                requestedRank = _uiState.value.selectedRank,
                requestedFull = _uiState.value.fullOnly,
            )
            val defaultFilters = defaultCatalogFilters(schema)

            val availableModes = buildList {
                if (schema.supportsHome) add(ExploreMode.HOME)
                if (schema.rankings.isNotEmpty()) add(ExploreMode.RANKINGS)
                if (schema.catalogFilters.isNotEmpty()) add(ExploreMode.CATALOG)
            }.ifEmpty { listOf(ExploreMode.HOME) }

            val currentBrowseMode = _uiState.value.activeBrowseMode
            val resolvedBrowseMode = if (currentBrowseMode in availableModes) {
                currentBrowseMode
            } else {
                availableModes.first()
            }
            val resolvedMode = if (_uiState.value.mode == ExploreMode.SEARCH) {
                ExploreMode.SEARCH
            } else {
                resolvedBrowseMode
            }

            _uiState.update { state ->
                state.copy(
                    schema = schema,
                    isLoadingSchema = false,
                    schemaError = null,
                    activeBrowseMode = resolvedBrowseMode,
                    mode = resolvedMode,
                    selectedChannel = defaultChannel,
                    selectedRank = defaultRank,
                    fullOnly = defaultFull,
                    selectedFilters = defaultFilters,
                )
            }

            if (resolvedMode != ExploreMode.SEARCH) {
                ensureBrowseTabLoaded(resolvedBrowseMode, forceReload = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val errorState = mapError(e, "获取发现页配置失败")
            _uiState.update {
                it.copy(
                    isLoadingSchema = false,
                    schemaError = errorState,
                )
            }
            startErrorRetryCountdownIfNeeded(errorState) { remaining ->
                _uiState.update { state ->
                    state.copy(
                        schemaError = state.schemaError?.copy(retryAfterSeconds = remaining),
                    )
                }
            }
        }
    }

    private fun ensureBrowseTabLoaded(tab: ExploreMode, forceReload: Boolean) {
        val state = _uiState.value
        if (!state.isServerConfigured || state.schema == null) return
        when (tab) {
            ExploreMode.HOME -> {
                if (forceReload || (state.homeData == null && !state.isLoadingHome)) {
                    loadHome(channel = state.selectedChannel)
                }
            }

            ExploreMode.RANKINGS -> {
                if (state.selectedRank.isNotBlank() &&
                    (forceReload || (state.rankingItems.isEmpty() && !state.isLoadingRankings))
                ) {
                    loadRankings(
                        rank = state.selectedRank,
                        fullOnly = state.fullOnly,
                        page = 1,
                        append = false,
                    )
                }
            }

            ExploreMode.CATALOG -> {
                if (forceReload || (state.catalogItems.isEmpty() && !state.isLoadingCatalog)) {
                    loadCatalog(
                        filters = state.selectedFilters,
                        page = 1,
                        append = false,
                    )
                }
            }

            ExploreMode.SEARCH -> Unit
        }
    }

    private fun loadHome(channel: String) {
        val platform = _uiState.value.selectedPlatform
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingHome = true,
                    homeError = null,
                )
            }
            try {
                val data = exploreRepository.getExploreHome(platform = platform, channel = channel)
                _uiState.update {
                    it.copy(
                        homeData = data,
                        isLoadingHome = false,
                        homeError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errorState = mapError(e, "加载发现首页失败")
                _uiState.update {
                    it.copy(
                        isLoadingHome = false,
                        homeError = errorState,
                    )
                }
                startErrorRetryCountdownIfNeeded(errorState) { remaining ->
                    _uiState.update { state ->
                        state.copy(
                            homeError = state.homeError?.copy(retryAfterSeconds = remaining),
                        )
                    }
                }
            }
        }
    }

    private fun loadRankings(
        rank: String,
        fullOnly: Boolean,
        page: Int,
        append: Boolean,
    ) {
        if (rank.isBlank()) return
        val platform = _uiState.value.selectedPlatform
        rankingsJob?.cancel()
        rankingsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingRankings = !append,
                    isLoadingMoreRankings = append,
                    rankingsError = null,
                )
            }
            try {
                val result = exploreRepository.getRankings(
                    platform = platform,
                    rank = rank,
                    full = fullOnly,
                    page = page,
                )
                _uiState.update { state ->
                    val merged = if (append) {
                        val seenIds = state.rankingItems.mapTo(HashSet()) { it.id }
                        state.rankingItems + result.items.filter { seenIds.add(it.id) }
                    } else {
                        result.items
                    }
                    state.copy(
                        rankingItems = merged,
                        rankingPage = result.page,
                        rankingTotalPages = result.totalPages,
                        rankingHasNextPage = result.hasNextPage,
                        isLoadingRankings = false,
                        isLoadingMoreRankings = false,
                        rankingsError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errorState = mapError(e, "加载排行榜失败")
                _uiState.update {
                    it.copy(
                        isLoadingRankings = false,
                        isLoadingMoreRankings = false,
                        rankingsError = errorState,
                    )
                }
                startErrorRetryCountdownIfNeeded(errorState) { remaining ->
                    _uiState.update { state ->
                        state.copy(
                            rankingsError = state.rankingsError?.copy(retryAfterSeconds = remaining),
                        )
                    }
                }
            }
        }
    }

    private fun loadCatalog(
        filters: Map<String, String>,
        page: Int,
        append: Boolean,
    ) {
        val platform = _uiState.value.selectedPlatform
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingCatalog = !append,
                    isLoadingMoreCatalog = append,
                    catalogError = null,
                )
            }
            try {
                val result = exploreRepository.getCatalog(
                    platform = platform,
                    filters = filters,
                    page = page,
                )
                _uiState.update { state ->
                    val merged = if (append) {
                        val seenIds = state.catalogItems.mapTo(HashSet()) { it.id }
                        state.catalogItems + result.items.filter { seenIds.add(it.id) }
                    } else {
                        result.items
                    }
                    state.copy(
                        catalogItems = merged,
                        catalogPage = result.page,
                        catalogTotalPages = result.totalPages,
                        catalogHasNextPage = result.hasNextPage,
                        isLoadingCatalog = false,
                        isLoadingMoreCatalog = false,
                        catalogError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errorState = mapError(e, "加载分类书库失败")
                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        isLoadingMoreCatalog = false,
                        catalogError = errorState,
                    )
                }
                startErrorRetryCountdownIfNeeded(errorState) { remaining ->
                    _uiState.update { state ->
                        state.copy(
                            catalogError = state.catalogError?.copy(retryAfterSeconds = remaining),
                        )
                    }
                }
            }
        }
    }

    private fun executeSearch(keyword: String) {
        val platform = _uiState.value.selectedPlatform
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    mode = ExploreMode.SEARCH,
                    submittedQuery = keyword,
                    isSearching = true,
                    searchError = null,
                )
            }
            try {
                val results = exploreRepository.searchNovels(
                    platform = platform,
                    keyword = keyword,
                )
                _uiState.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                        searchError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchCooldownException) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchCooldownSeconds = e.remainingSeconds,
                        searchError = null,
                    )
                }
                startSearchCooldownCountdown(e.remainingSeconds)
            } catch (e: Throwable) {
                val errorState = mapError(e, "搜索小说失败")
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchError = errorState,
                    )
                }
                startErrorRetryCountdownIfNeeded(errorState) { remaining ->
                    _uiState.update { state ->
                        state.copy(
                            searchError = state.searchError?.copy(retryAfterSeconds = remaining),
                        )
                    }
                }
            }
        }
    }

    private fun startSearchCooldownCountdown(initialSeconds: Int) {
        searchCooldownJob?.cancel()
        if (initialSeconds <= 0) {
            _uiState.update { it.copy(searchCooldownSeconds = 0) }
            return
        }
        searchCooldownJob = viewModelScope.launch {
            var remaining = initialSeconds
            while (remaining > 0) {
                _uiState.update { it.copy(searchCooldownSeconds = remaining) }
                delay(1_000L)
                remaining--
            }
            _uiState.update { it.copy(searchCooldownSeconds = 0) }
        }
    }

    private fun startErrorRetryCountdownIfNeeded(
        error: ExploreErrorState,
        onTick: (Int?) -> Unit,
    ) {
        val initial = error.retryAfterSeconds ?: return
        if (initial <= 0) return
        retryCountdownJob?.cancel()
        retryCountdownJob = viewModelScope.launch {
            var remaining = initial
            while (remaining > 0) {
                delay(1_000L)
                remaining--
                onTick(remaining.takeIf { it > 0 })
            }
        }
    }

    private fun cancelContentRequests() {
        homeJob?.cancel()
        rankingsJob?.cancel()
        catalogJob?.cancel()
        searchJob?.cancel()
        retryCountdownJob?.cancel()
    }

    private fun cancelAllRequests() {
        bootstrapJob?.cancel()
        cancelContentRequests()
        searchCooldownJob?.cancel()
    }

    private fun mapError(error: Throwable, fallback: String): ExploreErrorState {
        return when (error) {
            is ApiException -> {
                val challengeBlocked = error.statusCode == 503 ||
                    error.apiCode == 20001 ||
                    error.apiCode == 20002
                ExploreErrorState(
                    message = error.message.ifBlank { fallback },
                    statusCode = error.statusCode,
                    retryAfterSeconds = error.retryAfterSeconds,
                    isChallengeBlocked = challengeBlocked,
                )
            }

            else -> ExploreErrorState(
                message = error.message?.takeIf { it.isNotBlank() } ?: fallback,
            )
        }
    }

    private fun resolveDefaultHomeChannel(schema: ExploreSchemaDto): String {
        if (schema.channels.any { it.id == ExploreUiState.DEFAULT_CHANNEL }) {
            return ExploreUiState.DEFAULT_CHANNEL
        }
        return schema.channels.firstOrNull { !it.requiresChallenge }?.id
            ?: schema.channels.firstOrNull()?.id
            ?: ExploreUiState.DEFAULT_CHANNEL
    }

    private fun resolveRankingSelection(
        schema: ExploreSchemaDto,
        requestedRank: String,
        requestedFull: Boolean,
    ): Pair<String, Boolean> {
        val rankings = schema.rankings
        if (rankings.isEmpty()) {
            return requestedRank to requestedFull
        }

        var resolvedFull = requestedFull
        var candidates = rankings.filter { if (resolvedFull) it.supportsFull else it.supportsAll }
        if (candidates.isEmpty()) {
            resolvedFull = !resolvedFull
            candidates = rankings.filter { if (resolvedFull) it.supportsFull else it.supportsAll }
        }
        if (candidates.isEmpty()) {
            return rankings.first().id to requestedFull
        }

        val matched = candidates.firstOrNull { option ->
            option.id == requestedRank && (resolvedFull || option.id != "allvisit")
        }
        return (matched?.id ?: candidates.first().id) to resolvedFull
    }

    private fun defaultCatalogFilters(schema: ExploreSchemaDto): Map<String, String> {
        return buildMap {
            schema.catalogFilters.forEach { group ->
                val defaultVal = group.defaultValue.ifBlank {
                    group.options.firstOrNull()?.value.orEmpty()
                }
                if (defaultVal.isNotEmpty()) {
                    put(group.key, defaultVal)
                }
            }
        }
    }

    private fun buildCatalogFiltersFromParams(
        schema: ExploreSchemaDto?,
        params: Map<String, String>,
    ): Map<String, String> {
        if (schema == null) {
            return params.filterKeys { it != "page" }
                .mapValues { it.value.trim() }
                .filterValues { it.isNotEmpty() }
        }
        return buildMap {
            schema.catalogFilters.forEach { group ->
                val raw = params[group.key]?.trim().orEmpty()
                val value = if (raw.isNotEmpty() && group.options.any { it.value == raw }) {
                    raw
                } else {
                    group.defaultValue.ifBlank { group.options.firstOrNull()?.value.orEmpty() }
                }
                if (value.isNotEmpty()) {
                    put(group.key, value)
                }
            }
        }
    }
}
