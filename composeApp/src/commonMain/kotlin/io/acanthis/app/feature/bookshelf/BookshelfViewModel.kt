package io.acanthis.app.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.rebaseMediaUrlWithBase
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.SyncStateSnapshot
import io.acanthis.app.data.SyncStatus
import io.acanthis.app.data.decodeStringListJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BookshelfTab {
    data object All : BookshelfTab
    data object Default : BookshelfTab
    data class Category(val id: String, val name: String) : BookshelfTab
}

data class BookshelfTabItem(
    val tab: BookshelfTab,
    val label: String,
    val count: Int,
)

enum class BookshelfStatusFilter(val label: String) {
    ALL("全部状态"),
    ONGOING("连载中"),
    COMPLETED("已完结"),
}

enum class BookshelfSortOption(val label: String) {
    RECENTLY_READ("最近阅读"),
    RECENTLY_ADDED("最近添加"),
    TITLE("书名"),
}

data class GenreCountItem(
    val name: String,
    val count: Int,
)

data class BookshelfCardItem(
    val platform: String,
    val novelId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val progressLabel: String?,
    val progressPercent: Int?,
    val addedAt: Long,
    val updatedAt: Long,
    val lastReadAt: Long?,
    val categoryIds: List<String>,
    val genres: List<String>,
    val status: String,
)

data class ContinueReadingBanner(
    val platform: String,
    val novelId: String,
    val novelTitle: String,
    val coverUrl: String,
    val volumeTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val progressPercent: Int,
)

data class BookshelfUiState(
    val isConfigured: Boolean = false,
    val apiBaseUrl: String = "",
    val syncState: SyncStateSnapshot = SyncStateSnapshot(),
    val continueReading: ContinueReadingBanner? = null,
    val categories: List<BookshelfCategoryEntity> = emptyList(),
    val tabs: List<BookshelfTabItem> = emptyList(),
    val selectedTab: BookshelfTab = BookshelfTab.All,
    val searchQuery: String = "",
    val selectedGenres: Set<String> = emptySet(),
    val availableGenres: List<GenreCountItem> = emptyList(),
    val statusFilter: BookshelfStatusFilter = BookshelfStatusFilter.ALL,
    val sortOption: BookshelfSortOption = BookshelfSortOption.RECENTLY_READ,
    val totalActiveBooksCount: Int = 0,
    val items: List<BookshelfCardItem> = emptyList(),
) {
    val isRefreshing: Boolean
        get() = syncState.status == SyncStatus.SYNCING

    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() ||
            selectedGenres.isNotEmpty() ||
            statusFilter != BookshelfStatusFilter.ALL
}

private data class BookshelfFilterControls(
    val selectedTab: BookshelfTab = BookshelfTab.All,
    val searchQuery: String = "",
    val selectedGenres: Set<String> = emptySet(),
    val statusFilter: BookshelfStatusFilter = BookshelfStatusFilter.ALL,
    val sortOption: BookshelfSortOption = BookshelfSortOption.RECENTLY_READ,
)

private data class BookshelfRepoSnapshot(
    val categories: List<BookshelfCategoryEntity>,
    val bookshelfItems: List<BookshelfEntity>,
    val progressList: List<ReadingProgressEntity>,
    val latestProgress: ReadingProgressEntity?,
    val syncSnapshot: SyncStateSnapshot,
)

class BookshelfViewModel private constructor(
    private val libraryRepository: LibraryRepository,
    configFlow: Flow<ServerConfig>,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        serverConfigStore: ServerConfigStore,
    ) : this(
        libraryRepository = libraryRepository,
        configFlow = serverConfigStore.configFlow,
        marker = Unit,
    )

    private val controlsFlow = MutableStateFlow(BookshelfFilterControls())

    private val repoSnapshotFlow = combine(
        libraryRepository.observeActiveCategories(),
        libraryRepository.observeActiveBookshelf(),
        libraryRepository.observeAllProgress(),
        libraryRepository.observeContinueReading(),
        libraryRepository.syncState,
    ) { categories, bookshelfItems, progressList, latestProgress, syncSnapshot ->
        BookshelfRepoSnapshot(
            categories = categories,
            bookshelfItems = bookshelfItems,
            progressList = progressList,
            latestProgress = latestProgress,
            syncSnapshot = syncSnapshot,
        )
    }

    val uiState: StateFlow<BookshelfUiState> = combine(
        repoSnapshotFlow,
        configFlow,
        controlsFlow,
    ) { snapshot, serverConfig, controls ->
        val progressByKey = snapshot.progressList.associateBy { it.platform to it.novelId }
        val baseUrl = serverConfig.apiBaseUrl
        val activeCategoryIds = snapshot.categories.mapTo(HashSet()) { it.id }

        val allCards = snapshot.bookshelfItems.map { item ->
            val progress = progressByKey[item.platform to item.novelId]
            val percent = progress?.let(::computeProgressPercent)
            val effectiveCategoryIds = decodeStringListJson(item.categoryIdsJson)
                .filter { it in activeCategoryIds }
            val genres = decodeStringListJson(item.genresJson)
            item.toCardItem(
                apiBaseUrl = baseUrl,
                progress = progress,
                progressPercent = percent,
                effectiveCategoryIds = effectiveCategoryIds,
                genres = genres,
            )
        }

        val defaultCount = allCards.count { it.categoryIds.isEmpty() }
        val tabs = buildList {
            add(BookshelfTabItem(BookshelfTab.All, "全部", allCards.size))
            add(BookshelfTabItem(BookshelfTab.Default, "默认", defaultCount))
            snapshot.categories.forEach { cat ->
                val count = allCards.count { cat.id in it.categoryIds }
                add(BookshelfTabItem(BookshelfTab.Category(cat.id, cat.name), cat.name, count))
            }
        }

        val effectiveTab = when (val tab = controls.selectedTab) {
            is BookshelfTab.Category -> {
                val matched = snapshot.categories.firstOrNull { it.id == tab.id }
                if (matched != null) BookshelfTab.Category(matched.id, matched.name) else BookshelfTab.All
            }
            else -> tab
        }

        val shelfScopedCards = when (effectiveTab) {
            BookshelfTab.All -> allCards
            BookshelfTab.Default -> allCards.filter { it.categoryIds.isEmpty() }
            is BookshelfTab.Category -> allCards.filter { effectiveTab.id in it.categoryIds }
        }

        val genreCounts = linkedMapOf<String, Int>()
        shelfScopedCards.forEach { card ->
            card.genres.forEach { genre ->
                genreCounts[genre] = (genreCounts[genre] ?: 0) + 1
            }
        }
        val availableGenres = genreCounts.entries
            .map { GenreCountItem(name = it.key, count = it.value) }
            .sortedWith(compareByDescending<GenreCountItem> { it.count }.thenBy { it.name })

        val trimmedQuery = controls.searchQuery.trim()
        val filteredCards = shelfScopedCards.filter { card ->
            val matchesGenres = controls.selectedGenres.isEmpty() ||
                controls.selectedGenres.all { selected -> selected in card.genres }
            val matchesStatus = when (controls.statusFilter) {
                BookshelfStatusFilter.ALL -> true
                BookshelfStatusFilter.ONGOING -> card.status.equals("ongoing", ignoreCase = true)
                BookshelfStatusFilter.COMPLETED -> card.status.equals("completed", ignoreCase = true)
            }
            val matchesQuery = trimmedQuery.isEmpty() ||
                card.title.contains(trimmedQuery, ignoreCase = true) ||
                card.author.contains(trimmedQuery, ignoreCase = true)
            matchesGenres && matchesStatus && matchesQuery
        }

        val sortedCards = when (controls.sortOption) {
            BookshelfSortOption.RECENTLY_READ -> filteredCards.sortedWith(
                compareByDescending<BookshelfCardItem> { it.lastReadAt ?: 0L }
                    .thenByDescending { it.addedAt },
            )
            BookshelfSortOption.RECENTLY_ADDED -> filteredCards.sortedByDescending { it.addedAt }
            BookshelfSortOption.TITLE -> filteredCards.sortedBy { it.title.lowercase() }
        }

        val banner = snapshot.latestProgress?.let { progress ->
            ContinueReadingBanner(
                platform = progress.platform,
                novelId = progress.novelId,
                novelTitle = progress.novelTitle.ifBlank { "未命名小说" },
                coverUrl = rebaseMediaUrlWithBase(progress.coverUrl, baseUrl),
                volumeTitle = progress.volumeTitle,
                chapterId = progress.chapterId,
                chapterTitle = progress.chapterTitle.ifBlank { "继续阅读" },
                progressPercent = computeProgressPercent(progress),
            )
        }

        BookshelfUiState(
            isConfigured = serverConfig.isConfigured,
            apiBaseUrl = baseUrl,
            syncState = snapshot.syncSnapshot,
            continueReading = banner,
            categories = snapshot.categories,
            tabs = tabs,
            selectedTab = effectiveTab,
            searchQuery = controls.searchQuery,
            selectedGenres = controls.selectedGenres,
            availableGenres = availableGenres,
            statusFilter = controls.statusFilter,
            sortOption = controls.sortOption,
            totalActiveBooksCount = allCards.size,
            items = sortedCards,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BookshelfUiState(),
    )

    fun syncNow() {
        viewModelScope.launch {
            libraryRepository.syncNow()
        }
    }

    fun selectTab(tab: BookshelfTab) {
        controlsFlow.update { it.copy(selectedTab = tab) }
    }

    fun onSearchQueryChange(query: String) {
        controlsFlow.update { it.copy(searchQuery = query) }
    }

    fun toggleGenreFilter(genre: String) {
        val clean = genre.trim()
        if (clean.isEmpty()) return
        controlsFlow.update { current ->
            val next = if (clean in current.selectedGenres) {
                current.selectedGenres - clean
            } else {
                current.selectedGenres + clean
            }
            current.copy(selectedGenres = next)
        }
    }

    fun setSelectedGenres(genres: Set<String>) {
        controlsFlow.update { it.copy(selectedGenres = genres) }
    }

    fun selectStatusFilter(status: BookshelfStatusFilter) {
        controlsFlow.update { it.copy(statusFilter = status) }
    }

    fun selectSortOption(option: BookshelfSortOption) {
        controlsFlow.update { it.copy(sortOption = option) }
    }

    fun resetFilters() {
        controlsFlow.update {
            it.copy(
                searchQuery = "",
                selectedGenres = emptySet(),
                statusFilter = BookshelfStatusFilter.ALL,
            )
        }
    }

    fun applyGenreFilterFromOutside(genre: String) {
        val clean = genre.trim()
        if (clean.isEmpty()) return
        controlsFlow.update {
            it.copy(
                selectedTab = BookshelfTab.All,
                searchQuery = "",
                selectedGenres = setOf(clean),
                statusFilter = BookshelfStatusFilter.ALL,
            )
        }
    }

    fun createCategory(name: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { libraryRepository.createCategory(name) }
                .onFailure { onError(it.message ?: "创建书架失败") }
        }
    }

    fun renameCategory(id: String, name: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { libraryRepository.renameCategory(id, name) }
                .onFailure { onError(it.message ?: "重命名书架失败") }
        }
    }

    fun deleteCategory(id: String) {
        viewModelScope.launch {
            libraryRepository.deleteCategory(id)
        }
    }

    fun reorderCategories(orderedIds: List<String>) {
        viewModelScope.launch {
            libraryRepository.reorderCategories(orderedIds)
        }
    }

    fun setBookshelfCategories(platform: String, novelId: String, categoryIds: List<String>) {
        viewModelScope.launch {
            libraryRepository.setBookshelfCategories(platform, novelId, categoryIds)
        }
    }

    fun removeFromBookshelf(platform: String, novelId: String) {
        viewModelScope.launch {
            libraryRepository.removeFromBookshelf(platform, novelId)
        }
    }

    private fun BookshelfEntity.toCardItem(
        apiBaseUrl: String,
        progress: ReadingProgressEntity?,
        progressPercent: Int?,
        effectiveCategoryIds: List<String>,
        genres: List<String>,
    ): BookshelfCardItem {
        val label = when {
            progressPercent != null -> "$progressPercent%"
            progress != null && progress.chapterTitle.isNotBlank() -> progress.chapterTitle
            else -> null
        }
        return BookshelfCardItem(
            platform = platform,
            novelId = novelId,
            title = title,
            author = author,
            coverUrl = rebaseMediaUrlWithBase(coverUrl, apiBaseUrl),
            progressLabel = label,
            progressPercent = progressPercent,
            addedAt = addedAt,
            updatedAt = updatedAt,
            lastReadAt = progress?.updatedAt,
            categoryIds = effectiveCategoryIds,
            genres = genres,
            status = status,
        )
    }

    private fun computeProgressPercent(progress: ReadingProgressEntity): Int {
        if (progress.totalParagraphs <= 0) {
            return 0
        }
        val current = (progress.paragraphIndex.coerceAtLeast(0) + 1)
            .coerceAtMost(progress.totalParagraphs)
        return ((current * 100) / progress.totalParagraphs).coerceIn(0, 100)
    }

    companion object {
        internal fun createForTest(
            libraryRepository: LibraryRepository,
            configFlow: Flow<ServerConfig>,
        ): BookshelfViewModel = BookshelfViewModel(
            libraryRepository = libraryRepository,
            configFlow = configFlow,
            marker = Unit,
        )
    }
}
