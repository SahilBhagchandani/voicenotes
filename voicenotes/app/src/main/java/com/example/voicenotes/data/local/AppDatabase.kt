package com.example.voicenotes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.voicenotes.data.model.MeetingEntity
import com.example.voicenotes.data.model.TranscriptEntity

@Database(
    entities = [MeetingEntity::class, TranscriptEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun meetingDao(): MeetingDao
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
