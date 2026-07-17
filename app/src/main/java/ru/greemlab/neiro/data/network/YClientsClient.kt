package ru.greemlab.neiro.data.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.greemlab.neiro.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Синглтон-провайдер для YClients API клиента.
 */
object YClientsClient {

    private const val BASE_URL = "https://api.yclients.com/api/v1/"
    private const val ACCEPT_HEADER = "application/vnd.yclients.v2+json"

    @Volatile
    private var instance: YClientsApi? = null

    @Volatile
    private var tokenStorage: TokenStorage? = null

    fun getTokenStorage(context: Context): TokenStorage {
        return tokenStorage ?: synchronized(this) {
            tokenStorage ?: TokenStorage(context.applicationContext).also {
                tokenStorage = it
            }
        }
    }

    fun getApi(context: Context): YClientsApi {
        return instance ?: synchronized(this) {
            instance ?: createApi(context.applicationContext).also {
                instance = it
            }
        }
    }

    private fun createApi(context: Context): YClientsApi {
        val storage = getTokenStorage(context)

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val isAuthRequest = originalRequest.url.encodedPath.endsWith("/auth")
            val authHeader = buildAuthHeader(storage, isAuthRequest)

            val newRequest = originalRequest.newBuilder()
                .header("Accept", ACCEPT_HEADER)
                .header("Content-Type", "application/json")
                .apply {
                    if (authHeader.isNotEmpty()) {
                        header("Authorization", authHeader)
                    }
                }
                .build()

            chain.proceed(newRequest)
        }

        val okHttpClientBuilder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(RetryInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            okHttpClientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                },
            )
        }

        val okHttpClient = okHttpClientBuilder.build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YClientsApi::class.java)
    }

    /**
     * Retry с экспоненциальным backoff на transient-сбои: 408/429/5xx и [IOException].
     * `callTimeout` (60 с) ограничивает суммарное время вместе с ретраями.
     */
    private class RetryInterceptor : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            var lastException: IOException? = null
            var response: Response? = null

            repeat(MAX_ATTEMPTS) { attempt ->
                response?.close()
                response = try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    lastException = e
                    null
                }

                val current = response
                if (current != null && current.code !in RETRYABLE_CODES) {
                    return current
                }
                if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_BASE_DELAY_MS shl attempt)
                }
            }

            return response ?: throw (lastException ?: IOException("Request failed"))
        }

        private companion object {
            const val MAX_ATTEMPTS = 3
            const val RETRY_BASE_DELAY_MS = 500L
            val RETRYABLE_CODES = setOf(408, 429) + (500..599)
        }
    }

    private fun buildAuthHeader(storage: TokenStorage, isAuthRequest: Boolean): String {
        val partnerToken = storage.partnerToken
        val userToken = storage.userToken

        return when {
            partnerToken.isBlank() -> ""
            isAuthRequest || userToken.isNullOrBlank() -> "Bearer $partnerToken"
            else -> "Bearer $partnerToken, User $userToken"
        }
    }

    fun clearInstance() {
        synchronized(this) {
            instance = null
        }
    }
}
