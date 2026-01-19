package com.example.voicenotes.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenotes.data.model.MeetingEntity
import com.example.voicenotes.data.repository.MeetingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MeetingRepository
) : ViewModel() {

    // Flow of meetings from the repository
    val meetings: StateFlow<List<MeetingEntity>> = repository.getAllMeetings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun createNewMeeting(): Int {
        return repository.createMeeting()
    }
    fun clearAllMeetings(context: Context) {
        viewModelScope.launch {
            repository.clearAllData(context)
        }
    }

}
