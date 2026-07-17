package ru.greemlab.neiro.push

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.greemlab.neiro.BuildConfig
import java.util.concurrent.TimeUnit

object PushClient {

    @Volatile
    private var api: PushApi? = null

    fun getApi(): PushApi? {
        if (!PushConfig.isServerConfigured) return null
        return api ?: synchronized(this) {
            api ?: createApi().also { api = it }
        }
    }

    fun authHeader(): String = "Bearer ${BuildConfig.NEIRO_PUSH_API_KEY}"

    private fun createApi(): PushApi {
        val baseUrl = BuildConfig.NEIRO_PUSH_API_BASE_URL.trimEnd('/') + "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // Верхняя граница на весь вызов, включая retry/redirect (E8).
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PushApi::class.java)
    }

    fun clear() {
        synchronized(this) {
            api = null
        }
    }
}
