package com.example.floating_lyrics

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // "localhost" works because Termux and your App are on the SAME phone.
    private const val BASE_URL = "http://127.0.0.1:8000/" 

    val api: RomajiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RomajiApiService::class.java)
    }
}