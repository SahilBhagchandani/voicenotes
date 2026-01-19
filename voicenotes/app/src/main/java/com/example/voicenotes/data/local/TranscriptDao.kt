package com.example.voicenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.voicenotes.data.model.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insertTranscript(transcript: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE meetingId = :meetingId ORDER BY `order` ASC")
    fun getTranscriptsForMeeting(meetingId: Int): Flow<List<TranscriptEntity>>
}
