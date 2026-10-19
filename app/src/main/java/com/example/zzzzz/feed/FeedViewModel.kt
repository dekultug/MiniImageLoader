package com.example.zzzzz.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zzzzz.mock.FeedApi
import com.example.zzzzz.mock.FeedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val items: List<FeedItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedApi: FeedApi,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    private var nextPage = 0
    private var loadJob: Job? = null

    init {
        loadNextPage()
    }

    fun loadNextPage() {
        val current = _state.value
        if (loadJob?.isActive == true || !current.hasMore) return

        _state.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val response = feedApi.getFeed(page = nextPage, size = PAGE_SIZE)
                nextPage++
                _state.update {
                    it.copy(
                        items = it.items + response.items,
                        isLoading = false,
                        hasMore = response.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
