package com.example.voicenotes.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.voicenotes.data.repository.MeetingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SummaryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: MeetingRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val meetingId = inputData.getInt(KEY_MEETING_ID, -1)
        if (meetingId == -1) return Result.failure()

        return try {
            Log.d(TAG, "Generating summary for meetingId=$meetingId")
            repository.generateSummary(meetingId)
            Log.d(TAG, "Summary generated OK for meetingId=$meetingId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Summary generation failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SummaryWorker"
        const val KEY_MEETING_ID = "meeting_id"
    }
}
