package com.example.voicenotes.data.local

import androidx.room.*
import com.example.voicenotes.data.model.MeetingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Insert
    suspend fun insertMeeting(meeting: MeetingEntity): Long

    @Update
    suspend fun updateMeeting(meeting: MeetingEntity)

    @Query("SELECT * FROM meetings ORDER BY startTime DESC")
    fun getAllMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :meetingId")
    fun getMeeting(meetingId: Int): Flow<MeetingEntity>

    @Query("DELETE FROM meetings")
    suspend fun deleteAllMeetings()

    @Query("DELETE FROM meetings WHERE id = :meetingId")
    suspend fun deleteMeetingById(meetingId: Int)
}
