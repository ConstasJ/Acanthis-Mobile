package io.acanthis.app.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.CachedGenreDto
import io.acanthis.app.core.network.ExploreHomeDataDto
import io.acanthis.app.core.network.ExploreNovelItemDto
import io.acanthis.app.core.network.ExploreSchemaDto
import io.acanthis.app.core.network.ExploreSectionMoreTargetDto
import io.acanthis.app.core.network.NovelSearchResultDto
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.network.stripTrailingV1Prefix
import io.acanthis.app.core.network.toExploreNovelItem
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.ExploreRepository
import io.acanthis.app.data.SearchCooldownException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    CACHED,
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
    // CACHED
    val cachedItems: List<ExploreNovelItemDto> = emptyList(),
    val cachedTotal: Int = 0,
    val cachedPage: Int = 1,
    val cachedPageSize: Int = ExploreRepository.DEFAULT_CACHED_PAGE_SIZE,
    val cachedHasNextPage: Boolean = false,
    val isLoadingCached: Boolean = false,
    val isLoadingMoreCached: Boolean = false,
    val cachedError: ExploreErrorState? = null,
    val cachedGenres: List<CachedGenreDto> = emptyList(),
    val isLoadingCachedGenres: Boolean = false,
    val selectedCachedGenres: Set<String> = emptySet(),
    val selectedCachedStatus: String? = null,
    val cachedSearchQuery: String = "",
    // SEARCH
    val searchQuery: String = "",
    val submittedQuery: String = "",
    val searchResults: List<NovelSearchResultDto> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: ExploreErrorState? = null,
    val searchCooldownSeconds: Int = 0,
) {
    val supportsExplore: Boolean
        get() {
            val currentSource = sources.firstOrNull { it.id == selectedPlatform } ?: return true
            return "explore" in currentSource.capabilities
        }

    val cachedTotalPages: Int
        get() = if (cachedTotal <= 0 || cachedPageSize <= 0) {
            1
        } else {
            (cachedTotal + cachedPageSize - 1) / cachedPageSize
        }

    val availableBrowseModes: List<ExploreMode>
        get() {
            if (!supportsExplore) {
                return listOf(ExploreMode.CACHED)
            }
            val currentSchema = schema ?: return listOf(
                ExploreMode.HOME,
                ExploreMode.RANKINGS,
                ExploreMode.CATALOG,
                ExploreMode.CACHED,
            )
            return buildList {
                if (currentSchema.supportsHome) add(ExploreMode.HOME)
                if (currentSchema.rankings.isNotEmpty()) add(ExploreMode.RANKINGS)
                if (currentSchema.catalogFilters.isNotEmpty()) add(ExploreMode.CATALOG)
                add(ExploreMode.CACHED)
            }
        }

    companion object {
        const val DEFAULT_PLATFORM = "linovelib"
        const val DEFAULT_CHANNEL = "home"
    }
}

class ExploreViewModel private constructor(
    private val exploreRepository: ExploreRepository,
    private val configFlow: Flow<ServerConfig>,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ViewModel() {

    constructor(
        exploreRepository: ExploreRepository,
        serverConfigStore: ServerConfigStore,
    ) : this(
        exploreRepository = exploreRepository,
        configFlow = serverConfigStore.configFlow,
        marker = Unit,
    )

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private var bootstrapJob: Job? = null
    private var homeJob: Job? = null
    private var rankingsJob: Job? = null
    private var catalogJob: Job? = null
    private var cachedJob: Job? = null
    private var cachedGenresJob: Job? = null
    private var cachedSearchDebounceJob: Job? = null
    private var searchJob: Job? = null
    private var searchCooldownJob: Job? = null
    private var retryCountdownJob: Job? = null

    init {
        observeServerConfig()
    }

    private fun observeServerConfig() {
        viewModelScope.launch {
            configFlow
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
        val targetSource = _uiState.value.sources.firstOrNull { it.id == trimmed }
        val targetSupportsExplore = targetSource == null || "explore" in targetSource.capabilities
        _uiState.update { state ->
            val nextBrowseMode = if (targetSupportsExplore) state.activeBrowseMode else ExploreMode.CACHED
            val nextMode = if (state.mode == ExploreMode.SEARCH) ExploreMode.SEARCH else nextBrowseMode
            state.copy(
                selectedPlatform = trimmed,
                mode = nextMode,
                activeBrowseMode = nextBrowseMode,
                schema = null,
                isLoadingSchema = false,
                schemaError = null,
                selectedChannel = ExploreUiState.DEFAULT_CHANNEL,
                homeData = null,
                isLoadingHome = false,
                homeError = null,
                selectedRank = "",
                fullOnly = false,
                rankingItems = emptyList(),
                rankingPage = 1,
                rankingTotalPages = null,
                rankingHasNextPage = false,
                isLoadingRankings = false,
                isLoadingMoreRankings = false,
                rankingsError = null,
                selectedFilters = emptyMap(),
                catalogItems = emptyList(),
                catalogPage = 1,
                catalogTotalPages = null,
                catalogHasNextPage = false,
                isLoadingCatalog = false,
                isLoadingMoreCatalog = false,
                catalogError = null,
                cachedItems = emptyList(),
                cachedTotal = 0,
                cachedPage = 1,
                cachedHasNextPage = false,
                isLoadingCached = false,
                isLoadingMoreCached = false,
                cachedError = null,
                cachedGenres = emptyList(),
                isLoadingCachedGenres = false,
                selectedCachedGenres = emptySet(),
                selectedCachedStatus = null,
                cachedSearchQuery = "",
                searchResults = emptyList(),
                searchError = null,
            )
        }
        if (!targetSupportsExplore) {
            bootstrapJob?.cancel()
            if (_uiState.value.mode != ExploreMode.SEARCH) {
                loadCached(page = 1, append = false)
            }
        } else {
            loadSchemaForPlatform(trimmed, forceRefresh = false)
            if (_uiState.value.mode == ExploreMode.CACHED) {
                loadCached(page = 1, append = false)
            }
        }
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
        if (state.mode == ExploreMode.CACHED) {
            loadCached(page = 1, append = false)
            return
        }
        if (state.mode == ExploreMode.SEARCH) {
            if (state.submittedQuery.isNotBlank()) {
                executeSearch(state.submittedQuery)
            }
            return
        }
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
            ExploreMode.CACHED, ExploreMode.SEARCH -> Unit
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

    fun loadNextCachedPage() {
        val state = _uiState.value
        if (state.isLoadingCached || state.isLoadingMoreCached || !state.cachedHasNextPage) {
            return
        }
        loadCached(
            page = state.cachedPage + 1,
            append = true,
        )
    }

    fun toggleCachedGenre(genre: String) {
        val clean = genre.trim()
        if (clean.isEmpty()) return
        _uiState.update { state ->
            val next = if (clean in state.selectedCachedGenres) {
                state.selectedCachedGenres - clean
            } else {
                state.selectedCachedGenres + clean
            }
            state.copy(
                selectedCachedGenres = next,
                cachedItems = emptyList(),
                cachedPage = 1,
                cachedHasNextPage = false,
                cachedError = null,
            )
        }
        loadCached(page = 1, append = false)
    }

    fun selectCachedStatus(status: String?) {
        val clean = status?.trim()?.takeIf { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
        if (clean == _uiState.value.selectedCachedStatus) return
        _uiState.update { state ->
            state.copy(
                selectedCachedStatus = clean,
                cachedItems = emptyList(),
                cachedPage = 1,
                cachedHasNextPage = false,
                cachedError = null,
            )
        }
        loadCached(page = 1, append = false)
    }

    fun onCachedSearchQueryChange(query: String) {
        _uiState.update { it.copy(cachedSearchQuery = query) }
        cachedSearchDebounceJob?.cancel()
        cachedSearchDebounceJob = viewModelScope.launch {
            delay(300L)
            _uiState.update {
                it.copy(
                    cachedItems = emptyList(),
                    cachedPage = 1,
                    cachedHasNextPage = false,
                    cachedError = null,
                )
            }
            loadCached(page = 1, append = false)
        }
    }

    fun resetCachedFilters() {
        cachedSearchDebounceJob?.cancel()
        _uiState.update {
            it.copy(
                selectedCachedGenres = emptySet(),
                selectedCachedStatus = null,
                cachedSearchQuery = "",
                cachedItems = emptyList(),
                cachedPage = 1,
                cachedHasNextPage = false,
                cachedError = null,
            )
        }
        loadCached(page = 1, append = false)
    }

    fun openCachedWithGenre(genre: String) {
        val clean = genre.trim()
        if (clean.isEmpty()) return
        cachedSearchDebounceJob?.cancel()
        searchJob?.cancel()
        _uiState.update { state ->
            state.copy(
                mode = ExploreMode.CACHED,
                activeBrowseMode = ExploreMode.CACHED,
                selectedCachedGenres = setOf(clean),
                selectedCachedStatus = null,
                cachedSearchQuery = "",
                cachedItems = emptyList(),
                cachedPage = 1,
                cachedHasNextPage = false,
                cachedError = null,
            )
        }
        if (_uiState.value.isServerConfigured) {
            loadCachedGenres(forceRefresh = false)
            loadCached(page = 1, append = false)
        }
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
                sources.any { it.id == currentSelected } -> currentSelected
                else -> sources.firstOrNull { "explore" in it.capabilities }?.id
                    ?: sources.firstOrNull()?.id
                    ?: ExploreUiState.DEFAULT_PLATFORM
            }

            val resolvedSource = sources.firstOrNull { it.id == resolvedPlatform }
            val platformSupportsExplore = resolvedSource == null || "explore" in resolvedSource.capabilities

            if (!platformSupportsExplore) {
                _uiState.update { state ->
                    val nextMode = if (state.mode == ExploreMode.SEARCH) ExploreMode.SEARCH else ExploreMode.CACHED
                    state.copy(
                        sources = sources,
                        selectedPlatform = resolvedPlatform,
                        schema = null,
                        isLoadingSchema = false,
                        schemaError = null,
                        activeBrowseMode = ExploreMode.CACHED,
                        mode = nextMode,
                    )
                }
                if (_uiState.value.mode != ExploreMode.SEARCH) {
                    loadCached(page = 1, append = false)
                }
                return@launch
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
                add(ExploreMode.CACHED)
            }

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
                ensureBrowseTabLoaded(
                    tab = resolvedBrowseMode,
                    forceReload = resolvedBrowseMode != ExploreMode.CACHED,
                )
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
        if (!state.isServerConfigured) return
        if (tab == ExploreMode.CACHED) {
            loadCachedGenres(forceRefresh = forceReload)
            if (forceReload || (state.cachedItems.isEmpty() && !state.isLoadingCached)) {
                loadCached(page = 1, append = false)
            }
            return
        }
        if (state.schema == null) return
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

            ExploreMode.CACHED, ExploreMode.SEARCH -> Unit
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

    private fun loadCachedGenres(forceRefresh: Boolean) {
        val state = _uiState.value
        if (!state.isServerConfigured) return
        if (!forceRefresh && (state.cachedGenres.isNotEmpty() || state.isLoadingCachedGenres)) {
            return
        }
        val platform = state.selectedPlatform
        cachedGenresJob?.cancel()
        cachedGenresJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCachedGenres = true) }
            try {
                val genres = exploreRepository.getCachedGenres(platform = platform)
                _uiState.update {
                    it.copy(
                        cachedGenres = genres,
                        isLoadingCachedGenres = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _uiState.update { it.copy(isLoadingCachedGenres = false) }
            }
        }
    }

    private fun loadCached(
        page: Int,
        append: Boolean,
    ) {
        val stateSnapshot = _uiState.value
        val platform = stateSnapshot.selectedPlatform
        val pageSize = stateSnapshot.cachedPageSize.coerceAtLeast(1)
        val genres = stateSnapshot.selectedCachedGenres.toList()
        val status = stateSnapshot.selectedCachedStatus
        val query = stateSnapshot.cachedSearchQuery.trim().takeIf { it.isNotEmpty() }
        if (!append && stateSnapshot.cachedGenres.isEmpty() && !stateSnapshot.isLoadingCachedGenres) {
            loadCachedGenres(forceRefresh = false)
        }
        cachedJob?.cancel()
        cachedJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingCached = !append,
                    isLoadingMoreCached = append,
                    cachedError = null,
                )
            }
            try {
                val response = exploreRepository.getCachedNovels(
                    platform = platform,
                    page = page,
                    pageSize = pageSize,
                    genres = genres,
                    status = status,
                    query = query,
                )
                val mappedItems = response.items.map { it.toExploreNovelItem(fallbackPlatform = platform) }
                _uiState.update { state ->
                    val merged = if (append) {
                        val seenIds = state.cachedItems.mapTo(HashSet()) { it.id }
                        state.cachedItems + mappedItems.filter { seenIds.add(it.id) }
                    } else {
                        val seenIds = HashSet<String>()
                        mappedItems.filter { seenIds.add(it.id) }
                    }
                    val effectivePageSize = response.pageSize.coerceAtLeast(1)
                    val effectivePage = response.page.coerceAtLeast(1)
                    val hasNext = response.items.isNotEmpty() &&
                        merged.size < response.total &&
                        (effectivePage * effectivePageSize) < response.total
                    state.copy(
                        cachedItems = merged,
                        cachedTotal = response.total,
                        cachedPage = effectivePage,
                        cachedPageSize = effectivePageSize,
                        cachedHasNextPage = hasNext,
                        isLoadingCached = false,
                        isLoadingMoreCached = false,
                        cachedError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errorState = mapError(e, "加载服务器缓存书库失败")
                _uiState.update {
                    it.copy(
                        isLoadingCached = false,
                        isLoadingMoreCached = false,
                        cachedError = errorState,
                    )
                }
                startErrorRetryCountdownIfNeeded(errorState) { remaining ->
                    _uiState.update { state ->
                        state.copy(
                            cachedError = state.cachedError?.copy(retryAfterSeconds = remaining),
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
        cachedJob?.cancel()
        cachedGenresJob?.cancel()
        cachedSearchDebounceJob?.cancel()
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

    companion object {
        internal fun createForTest(
            exploreRepository: ExploreRepository,
            configFlow: Flow<ServerConfig>,
        ): ExploreViewModel = ExploreViewModel(
            exploreRepository = exploreRepository,
            configFlow = configFlow,
            marker = Unit,
        )
    }
}
