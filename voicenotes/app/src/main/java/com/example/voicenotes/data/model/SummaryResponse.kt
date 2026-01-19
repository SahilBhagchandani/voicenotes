package com.example.voicenotes.data.model

import com.google.gson.annotations.SerializedName

// Data class to parse the structured summary returned by the LLM (expected in JSON format)
data class SummaryResponse(
    val title: String,
    val summary: String,
    @SerializedName("action_items") val actionItems: List<String>,
    @SerializedName("key_points") val keyPoints: List<String>
)
