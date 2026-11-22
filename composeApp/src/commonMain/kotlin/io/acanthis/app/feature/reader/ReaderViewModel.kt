package io.acanthis.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.parser.ReaderBlock
import io.acanthis.app.core.parser.parseChapterBlocks
import io.acanthis.app.core.platform.currentDeviceKind
import io.acanthis.app.core.platform.currentTimeMillis
import io.acanthis.app.core.preferences.ReaderSettings
import io.acanthis.app.core.preferences.ReaderSettingsStore
import io.acanthis.app.core.preferences.ReaderTheme
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.ReaderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ReaderUiState(
    val platform: String,
    val novelId: String,
    val currentChapterId: String,
    val novel: NovelDto? = null,
    val novelTitle: String = "",
    val volumeId: String = "",
    val volumeTitle: String = "",
    val chapterTitle: String = "",
    val flatChapters: List<FlatChapterRef> = emptyList(),
    val currentChapterIndex: Int = -1,
    val previousChapter: FlatChapterRef? = null,
    val nextChapter: FlatChapterRef? = null,
    val cachedChapterIds: Set<String> = emptySet(),
    val blocks: List<ReaderBlock> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val retryAfterSeconds: Int? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val initialScrollIndex: Int? = null,
    val chapterLoadRevision: Long = 0L,
    val currentBlockIndex: Int = 0,
    val scrollPercent: Int = 0,
    val isNextChapterPrefetched: Boolean = false,
)

class ReaderViewModel(
    val platform: String,
    val novelId: String,
    initialChapterId: String,
    private val readerRepository: ReaderRepository,
    private val libraryRepository: LibraryRepository,
    private val readerSettingsStore: ReaderSettingsStore,
    private val serverConfigStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            platform = platform,
            novelId = novelId,
            currentChapterId = initialChapterId,
        ),
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // Dedicated non-cancellable scope so progress flush completes even after ViewModel.onCleared().
    private val exitFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var chapterLoadJob: Job? = null
    private var progressDebounceJob: Job? = null

    private var latestApiBaseUrl: String = ""
    private var latestDeviceLabel: String = ""
    private var latestRawHtml: String? = null
    private var latestRawHtmlChapterId: String? = null
    private var lastKnownProgress: ReadingProgressEntity? = null
    private var lastSavedChapterId: String? = null
    private var pendingProgressSaveForChapter: Boolean = false
    private var latestBlockIndex: Int = 0
    private var latestLastVisibleIndex: Int = 0
    private var chapterLoadRevisionCounter: Long = 0L
    private val triggeredPrefetchChapterIds = mutableSetOf<String>()

    init {
        observeReaderSettings()
        observeServerConfig()
        observeCachedChapters()
        observeNovelMetadata()
        loadChapter(chapterId = initialChapterId, restoreFromSavedProgress = true)
    }

    fun openChapter(chapterId: String) {
        val cleanId = chapterId.trim()
        if (cleanId.isEmpty()) {
            return
        }
        loadChapter(chapterId = cleanId, restoreFromSavedProgress = false)
    }

    fun previousChapter() {
        val prev = _uiState.value.previousChapter ?: return
        openChapter(prev.chapterId)
    }

    fun nextChapter() {
        val next = _uiState.value.nextChapter ?: return
        openChapter(next.chapterId)
    }

    fun retry() {
        loadChapter(
            chapterId = _uiState.value.currentChapterId,
            restoreFromSavedProgress = false,
        )
    }

    fun consumeInitialScroll() {
        _uiState.update { it.copy(initialScrollIndex = null) }
    }

    fun onScrollPositionChanged(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
    ) {
        val state = _uiState.value
        if (state.isLoading || state.blocks.isEmpty()) {
            return
        }

        val maxIndex = (state.blocks.size - 1).coerceAtLeast(0)
        val clampedFirst = firstVisibleIndex.coerceIn(0, maxIndex)
        val clampedLast = lastVisibleIndex.coerceIn(clampedFirst, maxIndex)

        latestBlockIndex = clampedFirst
        latestLastVisibleIndex = clampedLast

        val percent = computeScrollPercent(
            firstVisibleIndex = clampedFirst,
            lastVisibleIndex = clampedLast,
            totalBlocks = state.blocks.size,
        )

        _uiState.update { current ->
            if (current.currentBlockIndex == clampedFirst && current.scrollPercent == percent) {
                current
            } else {
                current.copy(
                    currentBlockIndex = clampedFirst,
                    scrollPercent = percent,
                )
            }
        }

        if (ChapterNavigator.shouldPrefetchNext(clampedLast, state.blocks.size)) {
            triggerNextChapterPrefetch()
        }

        pendingProgressSaveForChapter = true
        scheduleDebouncedProgressSave()
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            readerSettingsStore.update(transform)
        }
    }

    fun cycleTheme() {
        updateSettings { current ->
            val nextTheme = when (current.theme) {
                ReaderTheme.LIGHT -> ReaderTheme.SEPIA
                ReaderTheme.SEPIA -> ReaderTheme.OLED
                ReaderTheme.OLED -> ReaderTheme.LIGHT
            }
            current.copy(theme = nextTheme)
        }
    }

    fun flushProgressOnExit() {
        progressDebounceJob?.cancel()
        progressDebounceJob = null

        val snapshot = buildCurrentProgressEntity()
        exitFlushScope.launch {
            try {
                if (snapshot != null) {
                    libraryRepository.updateReadingProgress(snapshot)
                }
                libraryRepository.flushPendingSync()
            } catch (_: Throwable) {
                // Best-effort exit flush; dirty queue stays persisted for next sync.
            }
        }
    }

    private fun observeReaderSettings() {
        viewModelScope.launch {
            readerSettingsStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    private fun observeServerConfig() {
        viewModelScope.launch {
            serverConfigStore.configFlow.collect { config ->
                val baseUrlChanged = latestApiBaseUrl.isNotEmpty() &&
                    latestApiBaseUrl != config.apiBaseUrl
                latestApiBaseUrl = config.apiBaseUrl
                latestDeviceLabel = config.deviceLabel

                if (baseUrlChanged) {
                    val rawHtml = latestRawHtml
                    val htmlChapterId = latestRawHtmlChapterId
                    if (rawHtml != null && htmlChapterId == _uiState.value.currentChapterId) {
                        val rebasedBlocks = parseChapterBlocks(rawHtml, config.apiBaseUrl)
                        _uiState.update { it.copy(blocks = rebasedBlocks) }
                    }
                }
            }
        }
    }

    private fun observeCachedChapters() {
        viewModelScope.launch {
            readerRepository.observeCachedChapterIds(platform, novelId).collect { cachedIds ->
                _uiState.update { current ->
                    val nextId = current.nextChapter?.chapterId
                    current.copy(
                        cachedChapterIds = cachedIds,
                        isNextChapterPrefetched = nextId != null && nextId in cachedIds,
                    )
                }
            }
        }
    }

    private fun observeNovelMetadata() {
        viewModelScope.launch {
            readerRepository.observeNovelDetail(platform, novelId).collect { detailState ->
                val novel = detailState.novel ?: return@collect
                val flatChapters = ChapterNavigator.flatten(novel)

                _uiState.update { current ->
                    val chapterId = current.currentChapterId
                    val chapterIndex = flatChapters.indexOfFirst { it.chapterId == chapterId }
                    val currentRef = flatChapters.getOrNull(chapterIndex)
                    val prev = ChapterNavigator.previousOf(flatChapters, chapterId)
                    val next = ChapterNavigator.nextOf(flatChapters, chapterId)
                    current.copy(
                        novel = novel,
                        novelTitle = novel.title,
                        flatChapters = flatChapters,
                        currentChapterIndex = chapterIndex,
                        volumeId = currentRef?.volumeId ?: current.volumeId,
                        volumeTitle = currentRef?.volumeTitle ?: current.volumeTitle,
                        chapterTitle = currentRef?.chapterTitle ?: current.chapterTitle,
                        previousChapter = prev,
                        nextChapter = next,
                        isNextChapterPrefetched = next != null && next.chapterId in current.cachedChapterIds,
                    )
                }

                // If user already scrolled past 75% or opened a chapter before metadata resolved volumeId, trigger now.
                val stateAfter = _uiState.value
                if (!stateAfter.isLoading && stateAfter.blocks.isNotEmpty()) {
                    if (ChapterNavigator.shouldPrefetchNext(latestLastVisibleIndex, stateAfter.blocks.size)) {
                        triggerNextChapterPrefetch()
                    }
                    if (stateAfter.volumeId.isNotBlank() &&
                        (pendingProgressSaveForChapter || lastSavedChapterId != stateAfter.currentChapterId)
                    ) {
                        scheduleDebouncedProgressSave()
                    }
                }
            }
        }
    }

    private fun loadChapter(
        chapterId: String,
        restoreFromSavedProgress: Boolean,
    ) {
        chapterLoadJob?.cancel()
        progressDebounceJob?.cancel()
        progressDebounceJob = null
        latestRawHtml = null
        latestRawHtmlChapterId = null
        lastSavedChapterId = null
        pendingProgressSaveForChapter = false
        latestBlockIndex = 0
        latestLastVisibleIndex = 0

        _uiState.update { current ->
            val flat = current.flatChapters
            val chapterIndex = flat.indexOfFirst { it.chapterId == chapterId }
            val currentRef = flat.getOrNull(chapterIndex)
            val prev = ChapterNavigator.previousOf(flat, chapterId)
            val next = ChapterNavigator.nextOf(flat, chapterId)
            current.copy(
                currentChapterId = chapterId,
                currentChapterIndex = chapterIndex,
                volumeId = currentRef?.volumeId.orEmpty(),
                volumeTitle = currentRef?.volumeTitle.orEmpty(),
                chapterTitle = currentRef?.chapterTitle.orEmpty(),
                previousChapter = prev,
                nextChapter = next,
                isNextChapterPrefetched = next != null && next.chapterId in current.cachedChapterIds,
                blocks = emptyList(),
                isLoading = true,
                errorMessage = null,
                retryAfterSeconds = null,
                initialScrollIndex = null,
                currentBlockIndex = 0,
                scrollPercent = 0,
            )
        }

        chapterLoadJob = viewModelScope.launch {
            try {
                val config = serverConfigStore.currentConfig()
                latestApiBaseUrl = config.apiBaseUrl
                latestDeviceLabel = config.deviceLabel

                val savedProgress = runCatching {
                    libraryRepository.observeProgress(platform, novelId).first()
                }.getOrNull()
                if (savedProgress != null) {
                    lastKnownProgress = savedProgress
                }

                val currentState = _uiState.value
                val fallbackTitle = currentState.chapterTitle.ifBlank {
                    savedProgress?.takeIf { it.chapterId == chapterId }?.chapterTitle.orEmpty()
                }

                val rawHtml = readerRepository.getChapterHtml(
                    platform = platform,
                    novelId = novelId,
                    chapterId = chapterId,
                    title = fallbackTitle,
                )
                val blocks = parseChapterBlocks(rawHtml, config.apiBaseUrl)

                latestRawHtml = rawHtml
                latestRawHtmlChapterId = chapterId

                val maxIndex = (blocks.size - 1).coerceAtLeast(0)
                val restoredIndex = if (
                    restoreFromSavedProgress &&
                    savedProgress?.chapterId == chapterId &&
                    blocks.isNotEmpty()
                ) {
                    savedProgress.paragraphIndex.coerceIn(0, maxIndex)
                } else {
                    0
                }

                latestBlockIndex = restoredIndex
                latestLastVisibleIndex = restoredIndex
                val initialPercent = computeScrollPercent(
                    firstVisibleIndex = restoredIndex,
                    lastVisibleIndex = restoredIndex,
                    totalBlocks = blocks.size,
                )

                val revision = ++chapterLoadRevisionCounter
                _uiState.update { state ->
                    state.copy(
                        novelTitle = state.novelTitle.ifBlank { savedProgress?.novelTitle.orEmpty() },
                        volumeId = state.volumeId.ifBlank {
                            savedProgress?.takeIf { it.chapterId == chapterId }?.volumeId.orEmpty()
                        },
                        volumeTitle = state.volumeTitle.ifBlank {
                            savedProgress?.takeIf { it.chapterId == chapterId }?.volumeTitle.orEmpty()
                        },
                        chapterTitle = state.chapterTitle.ifBlank { fallbackTitle },
                        blocks = blocks,
                        isLoading = false,
                        errorMessage = null,
                        retryAfterSeconds = null,
                        initialScrollIndex = restoredIndex,
                        chapterLoadRevision = revision,
                        currentBlockIndex = restoredIndex,
                        scrollPercent = initialPercent,
                    )
                }

                if (blocks.isNotEmpty()) {
                    if (ChapterNavigator.shouldPrefetchNext(restoredIndex, blocks.size)) {
                        triggerNextChapterPrefetch()
                    }
                    if (restoreFromSavedProgress && savedProgress?.chapterId == chapterId) {
                        lastSavedChapterId = chapterId
                    } else {
                        pendingProgressSaveForChapter = true
                        scheduleDebouncedProgressSave()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        errorMessage = e.message.ifBlank { "章节加载失败（HTTP ${e.statusCode}）" },
                        retryAfterSeconds = e.retryAfterSeconds,
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "章节加载失败，请稍后重试",
                        retryAfterSeconds = null,
                    )
                }
            }
        }
    }

    private fun triggerNextChapterPrefetch() {
        val next = _uiState.value.nextChapter ?: return
        if (next.chapterId in _uiState.value.cachedChapterIds) {
            return
        }
        if (!triggeredPrefetchChapterIds.add(next.chapterId)) {
            return
        }
        viewModelScope.launch {
            readerRepository.prefetchNextChapter(
                platform = platform,
                novelId = novelId,
                chapterId = next.chapterId,
                title = next.chapterTitle,
            )
        }
    }

    private fun scheduleDebouncedProgressSave() {
        progressDebounceJob?.cancel()
        progressDebounceJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            val entity = buildCurrentProgressEntity() ?: return@launch
            try {
                libraryRepository.updateReadingProgress(entity)
                lastKnownProgress = entity
                lastSavedChapterId = entity.chapterId
                pendingProgressSaveForChapter = false
            } catch (_: Throwable) {
                // Local save failures should not interrupt reading.
            }
        }
    }

    private fun buildCurrentProgressEntity(): ReadingProgressEntity? {
        val state = _uiState.value
        if (state.isLoading || state.blocks.isEmpty() || state.currentChapterId.isBlank()) {
            return null
        }

        val currentRef = state.flatChapters.getOrNull(state.currentChapterIndex)
        val saved = lastKnownProgress
        val maxIndex = (state.blocks.size - 1).coerceAtLeast(0)
        val blockIndex = latestBlockIndex.coerceIn(0, maxIndex)

        val resolvedVolumeId = currentRef?.volumeId?.takeIf { it.isNotBlank() }
            ?: state.volumeId.ifBlank {
                saved?.takeIf { it.chapterId == state.currentChapterId }?.volumeId.orEmpty()
            }
        // Backend sync schema requires volumeId: z.string().min(1); never write invalid progress.
        if (resolvedVolumeId.isBlank()) {
            return null
        }

        val resolvedNovelTitle = state.novelTitle.ifBlank { saved?.novelTitle.orEmpty() }
        val resolvedCoverUrl = state.novel?.coverUrl.orEmpty().ifBlank { saved?.coverUrl.orEmpty() }
        val resolvedVolumeTitle = currentRef?.volumeTitle?.takeIf { it.isNotBlank() }
            ?: state.volumeTitle.ifBlank {
                saved?.takeIf { it.chapterId == state.currentChapterId }?.volumeTitle.orEmpty()
            }
        val resolvedChapterTitle = currentRef?.chapterTitle?.takeIf { it.isNotBlank() }
            ?: state.chapterTitle.ifBlank {
                saved?.takeIf { it.chapterId == state.currentChapterId }?.chapterTitle.orEmpty()
            }

        return ReadingProgressEntity(
            platform = platform,
            novelId = novelId,
            novelTitle = resolvedNovelTitle,
            coverUrl = resolvedCoverUrl,
            volumeId = resolvedVolumeId,
            volumeTitle = resolvedVolumeTitle,
            chapterId = state.currentChapterId,
            chapterTitle = resolvedChapterTitle,
            paragraphIndex = blockIndex,
            totalParagraphs = state.blocks.size,
            deviceLabel = latestDeviceLabel,
            deviceKind = currentDeviceKind(),
            updatedAt = currentTimeMillis(),
        )
    }

    private fun computeScrollPercent(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        totalBlocks: Int,
    ): Int {
        if (totalBlocks <= 0) {
            return 0
        }
        if (totalBlocks == 1 || lastVisibleIndex >= totalBlocks - 1) {
            return 100
        }
        val ratio = firstVisibleIndex.toFloat() / (totalBlocks - 1).toFloat()
        return (ratio * 100f).roundToInt().coerceIn(0, 100)
    }

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 600L
    }
}
