package com.example.voicenotes.data.repository

import android.content.Context
import android.util.Log
import com.example.voicenotes.BuildConfig
import com.example.voicenotes.data.local.MeetingDao
import com.example.voicenotes.data.local.TranscriptDao
import com.example.voicenotes.data.model.MeetingEntity
import com.example.voicenotes.data.model.SummaryResponse
import com.example.voicenotes.data.model.TranscriptEntity
import com.example.voicenotes.data.remote.ChatCompletionRequest
import com.example.voicenotes.data.remote.Message
import com.example.voicenotes.data.remote.OpenAIApi
import com.example.voicenotes.data.remote.TranscriptionResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeetingRepository @Inject constructor(
    private val meetingDao: MeetingDao,
    private val transcriptDao: TranscriptDao,
    private val openAIApi: OpenAIApi
) {

    suspend fun createMeeting(): Int {
        val meeting = MeetingEntity(startTime = System.currentTimeMillis())
        return meetingDao.insertMeeting(meeting).toInt()
    }

    fun getAllMeetings(): Flow<List<MeetingEntity>> = meetingDao.getAllMeetings()

    fun getMeeting(meetingId: Int): Flow<MeetingEntity> = meetingDao.getMeeting(meetingId)

    fun getTranscripts(meetingId: Int): Flow<List<TranscriptEntity>> =
        transcriptDao.getTranscriptsForMeeting(meetingId)

    suspend fun saveTranscriptChunk(meetingId: Int, chunkOrder: Int, text: String) {
        transcriptDao.insertTranscript(
            TranscriptEntity(
                meetingId = meetingId,
                order = chunkOrder,
                text = text,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearAllData(context: Context) {
        meetingDao.deleteAllMeetings() // transcripts deleted via CASCADE

        val dir = File(context.filesDir, "recordings")
        if (dir.exists()) {
            dir.listFiles()?.forEach { f ->
                runCatching { f.delete() }
            }
        }
    }

    suspend fun transcribeAudioChunk(meetingId: Int, audioFilePath: String, chunkOrder: Int) {
        val file = File(audioFilePath)
        require(file.exists()) { "Audio file not found: $audioFilePath" }

        val mediaType = "audio/mp4".toMediaType() // m4a container is mp4
        val fileBody = file.asRequestBody(mediaType)
        val filePart = MultipartBody.Part.createFormData("file", file.name, fileBody)

        val modelBody = "whisper-1".toRequestBody("text/plain".toMediaType())

        val auth = "Bearer ${BuildConfig.OPENAI_API_KEY}"
        val response: TranscriptionResponse = openAIApi.transcribeAudio(
            authHeader = auth,
            file = filePart,
            model = modelBody
        )

        saveTranscriptChunk(meetingId, chunkOrder, response.text)
    }

    suspend fun generateSummary(meetingId: Int) {
        val transcriptList = transcriptDao.getTranscriptsForMeeting(meetingId).first()
        val fullTranscriptText = transcriptList.joinToString(separator = "\n") { it.text }.trim()

        require(fullTranscriptText.isNotBlank()) { "Transcript is empty; cannot generate summary." }

        val userPrompt = """
            You are an assistant that summarizes meeting transcripts.
            Return ONLY valid JSON (no markdown, no extra text) with keys:
            title (string),
            summary (string),
            action_items (array of strings),
            key_points (array of strings).

            Transcript:
            $fullTranscriptText
        """.trimIndent()

        val request = ChatCompletionRequest(
            model = "gpt-3.5-turbo",
            messages = listOf(Message(role = "user", content = userPrompt)),
            maxTokens = 900,
            temperature = 0.4,
            stream = false
        )

        val auth = "Bearer ${BuildConfig.OPENAI_API_KEY}"
        val response = openAIApi.generateSummary(authHeader = auth, request = request)

        val assistantContent = response.choices.firstOrNull()?.message?.content
            ?: error("No summary content returned by model")

        Log.d("MeetingRepository", "RAW summary JSON: $assistantContent")


        val summaryData = try {
            Gson().fromJson(assistantContent, SummaryResponse::class.java)
        } catch (e: Exception) {
            error("Summary JSON parse failed: ${e.message}\nRaw:\n$assistantContent")
        }

        val currentMeeting = meetingDao.getMeeting(meetingId).first()
        val updated = currentMeeting.copy(
            title = summaryData.title,
            summary = summaryData.summary,
            actionItems = summaryData.actionItems.joinToString("\n"),
            keyPoints = summaryData.keyPoints.joinToString("\n")
        )
        meetingDao.updateMeeting(updated)
    }
}
