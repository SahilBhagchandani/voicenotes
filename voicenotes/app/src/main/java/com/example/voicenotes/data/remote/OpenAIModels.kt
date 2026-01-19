package com.example.voicenotes.data.remote

import com.google.gson.annotations.SerializedName


data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int = 1024,
    val temperature: Double = 0.7,
    val stream: Boolean = false
)

// A chat message in the conversation
data class Message(val role: String, val content: String)

// Response from chat completion
data class ChatCompletionResponse(val choices: List<Choice>)

data class Choice(val message: Message)

// Response from Whisper transcription
data class TranscriptionResponse(val text: String)
