package com.example.voicenotes.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val startTime: Long,          // timestamp when recording started
    val title: String? = null,    // Meeting title
    val summary: String? = null,  // Full summary text
    val actionItems: String? = null, // Action items from summary
    val keyPoints: String? = null    // Key points from summary
)
