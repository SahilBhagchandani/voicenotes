package com.example.voicenotes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenotes.data.model.MeetingEntity
import com.example.voicenotes.data.model.TranscriptEntity
import com.example.voicenotes.data.repository.MeetingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val repository: MeetingRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    private val meetingId: Int = savedStateHandle["meetingId"] ?: -1

    // Flow of the MeetingEntity
    val meeting: StateFlow<MeetingEntity?> = repository.getMeeting(meetingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Flow of transcripts list
    val transcripts: StateFlow<List<TranscriptEntity>> = repository.getTranscripts(meetingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Summary generation UI state (Idle/Loading/Error)
    private val _summaryState = MutableStateFlow<SummaryState>(SummaryState.Idle)
    val summaryState: StateFlow<SummaryState> = _summaryState.asStateFlow()

    fun generateSummary() {
        _summaryState.value = SummaryState.Loading
        viewModelScope.launch {
            try {
                repository.generateSummary(meetingId)
                _summaryState.value = SummaryState.Success
            } catch (e: Exception) {
                _summaryState.value = SummaryState.Error("Summary generation failed: ${e.message}")
            }
        }
    }

    // Represent UI state for summary section
    sealed class SummaryState {
        object Idle : SummaryState()
        object Loading : SummaryState()
        object Success : SummaryState()  // summary available
        data class Error(val message: String?) : SummaryState()
    }
}
