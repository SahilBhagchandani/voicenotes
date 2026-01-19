package com.example.voicenotes.data.remote

import com.example.voicenotes.data.remote.ChatCompletionRequest
import com.example.voicenotes.data.remote.ChatCompletionResponse
import com.example.voicenotes.data.remote.TranscriptionResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface OpenAIApi {

    // Whisper ASR: Transcribe audio file to text
    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authHeader: String,    // "Bearer API_KEY"
        @Part file: MultipartBody.Part,                // audio file
        @Part("model") model: RequestBody
    ): TranscriptionResponse

    @POST("chat/completions")
    suspend fun generateSummary(
        @Header("Authorization") authHeader: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
