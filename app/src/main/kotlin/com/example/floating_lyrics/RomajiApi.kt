package com.example.floating_lyrics

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Response

// 1. The Data Model (Matches your Server's JSON)
data class RomajiResult(
    val original: String,
    val romaji: String,
    val method: String
)

// 2. The API Definition
interface RomajiApiService {
    @POST("/convert-batch")
    suspend fun convertLyrics(@Body lines: List<String>): Response<List<RomajiResult>>
}