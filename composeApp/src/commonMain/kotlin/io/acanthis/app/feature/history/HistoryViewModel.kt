package io.acanthis.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.rebaseMediaUrlWithBase
import io.acanthis.app.core.platform.currentTimeMillis
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class HistoryItemUi(
    val platform: String,
    val novelId: String,
    val novelTitle: String,
    val coverUrl: String,
    val volumeTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val deviceLabel: String,
    val timeLabel: String,
    val progressPercent: Int,
    val updatedAt: Long,
)

data class HistoryDateGroup(
    val dateKey: String,
    val label: String,
    val items: List<HistoryItemUi>,
)

data class HistoryUiState(
    val searchQuery: String = "",
    val groups: List<HistoryDateGroup> = emptyList(),
    val totalCount: Int = 0,
)

@OptIn(ExperimentalTime::class)
class HistoryViewModel private constructor(
    private val libraryRepository: LibraryRepository,
    configFlow: Flow<ServerConfig>,
    private val timeZone: TimeZone,
    private val clock: () -> Long,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        serverConfigStore: ServerConfigStore,
    ) : this(
        libraryRepository = libraryRepository,
        configFlow = serverConfigStore.configFlow,
        timeZone = TimeZone.currentSystemDefault(),
        clock = ::currentTimeMillis,
        marker = Unit,
    )

    private val searchQueryFlow = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> = combine(
        libraryRepository.observeVisibleHistory(),
        searchQueryFlow,
        configFlow,
    ) { historyList, rawQuery, serverConfig ->
        val query = rawQuery.trim()
        val filtered = if (query.isEmpty()) {
            historyList
        } else {
            historyList.filter { item ->
                item.novelTitle.contains(query, ignoreCase = true) ||
                    item.chapterTitle.contains(query, ignoreCase = true) ||
                    item.volumeTitle.contains(query, ignoreCase = true)
            }
        }

        val today = Instant.fromEpochMilliseconds(clock()).toLocalDateTime(timeZone).date
        val yesterday = today.minus(DatePeriod(days = 1))
        val baseUrl = serverConfig.apiBaseUrl

        val grouped = linkedMapOf<LocalDate, MutableList<HistoryItemUi>>()
        for (entity in filtered) {
            val ldt = Instant.fromEpochMilliseconds(entity.updatedAt).toLocalDateTime(timeZone)
            val date = ldt.date
            val timeLabel = "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}"
            val itemUi = HistoryItemUi(
                platform = entity.platform,
                novelId = entity.novelId,
                novelTitle = entity.novelTitle.ifBlank { "未命名小说" },
                coverUrl = rebaseMediaUrlWithBase(entity.coverUrl, baseUrl),
                volumeTitle = entity.volumeTitle,
                chapterId = entity.chapterId,
                chapterTitle = entity.chapterTitle.ifBlank { "继续阅读" },
                deviceLabel = entity.deviceLabel,
                timeLabel = timeLabel,
                progressPercent = computeProgressPercent(entity),
                updatedAt = entity.updatedAt,
            )
            grouped.getOrPut(date) { mutableListOf() }.add(itemUi)
        }

        val dateGroups = grouped.entries.map { (date, items) ->
            HistoryDateGroup(
                dateKey = formatDateKey(date),
                label = formatGroupLabel(date, today, yesterday),
                items = items,
            )
        }

        HistoryUiState(
            searchQuery = rawQuery,
            groups = dateGroups,
            totalCount = historyList.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(),
    )

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun removeHistoryItem(platform: String, novelId: String) {
        viewModelScope.launch {
            libraryRepository.removeHistoryItem(platform, novelId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            libraryRepository.clearAllHistory()
        }
    }

    private fun computeProgressPercent(progress: ReadingProgressEntity): Int {
        if (progress.totalParagraphs <= 0) {
            return 0
        }
        val current = (progress.paragraphIndex.coerceAtLeast(0) + 1)
            .coerceAtMost(progress.totalParagraphs)
        return ((current * 100) / progress.totalParagraphs).coerceIn(0, 100)
    }

    private fun formatDateKey(date: LocalDate): String {
        val month = date.month.number.toString().padStart(2, '0')
        val day = date.day.toString().padStart(2, '0')
        return "${date.year}-$month-$day"
    }

    private fun formatGroupLabel(
        date: LocalDate,
        today: LocalDate,
        yesterday: LocalDate,
    ): String {
        return when (date) {
            today -> "今天"
            yesterday -> "昨天"
            else -> {
                val month = date.month.number.toString().padStart(2, '0')
                val day = date.day.toString().padStart(2, '0')
                "${date.year}年${month}月${day}日"
            }
        }
    }

    companion object {
        internal fun createForTest(
            libraryRepository: LibraryRepository,
            configFlow: Flow<ServerConfig>,
            timeZone: TimeZone = TimeZone.UTC,
            clock: () -> Long = ::currentTimeMillis,
        ): HistoryViewModel = HistoryViewModel(
            libraryRepository = libraryRepository,
            configFlow = configFlow,
            timeZone = timeZone,
            clock = clock,
            marker = Unit,
        )
    }
}
