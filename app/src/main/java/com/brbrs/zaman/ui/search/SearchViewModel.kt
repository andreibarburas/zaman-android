package com.brbrs.zaman.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Battery/sync-window fix: an extra, server-fetched result set for whatever's outside the
     *  routine sync window — see repository.searchEventsOnServer's doc comment. Cleared whenever
     *  the query changes, so a stale widen-search result never lingers after the user edits. */
    private val _widenedResults = MutableStateFlow<List<EventEntity>>(emptyList())
    private var widenJob: kotlinx.coroutines.Job? = null

    private val localResults: StateFlow<List<EventEntity>> = _query
        .debounce(200)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repository.searchEvents(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** What Search actually shows: local Room results, plus (once it's found something) a
     *  server-side widen search for anything older/further out than the routine sync window
     *  bothers caching — see onLocalResultsSettled. */
    val results: StateFlow<List<EventEntity>> = kotlinx.coroutines.flow.combine(localResults, _widenedResults) { local, widened ->
        if (widened.isEmpty()) local else (local + widened).distinctBy { it.href }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _query.debounce(200).collect { q ->
                widenJob?.cancel()
                _widenedResults.value = emptyList()
                if (q.isBlank()) return@collect
                widenJob = launch {
                    // Give the local (Room, instant) search a moment to actually settle before
                    // deciding it's "empty" — widening is a real network round-trip per calendar,
                    // not something to fire on every keystroke. One shot per settled query, not a
                    // continuous collect, so a brief empty-then-populated local emission never
                    // fires an unnecessary network search.
                    kotlinx.coroutines.delay(250)
                    if (localResults.value.isEmpty()) {
                        _widenedResults.value = runCatching { repository.searchEventsOnServer(q.trim()) }.getOrDefault(emptyList())
                    }
                }
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }
}
