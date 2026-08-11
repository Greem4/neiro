package ru.greemlab.neiro.data.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.push.PushApi
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Синглтон-провайдер клиентов сервиса Neiro.
 *
 * Один Retrofit на три интерфейса: [YClientsApi] (прокси к YClients),
 * [NeiroApi] (вход, сессия, FCM) и [PushApi] (догон событий) — все живут на
 * одном хосте, за одним `device_token`.
 */
object YClientsClient {

    private const val ACCEPT_HEADER = "application/json"

    /** Путь входа: единственный запрос, который авторизуется ключом приложения. */
    private const val LOGIN_PATH = "/v1/auth/login"

    @Volatile
    private var yclientsApi: YClientsApi? = null

    @Volatile
    private var neiroApi: NeiroApi? = null

    @Volatile
    private var pushApi: PushApi? = null

    @Volatile
    private var retrofit: Retrofit? = null

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
        return yclientsApi ?: synchronized(this) {
            yclientsApi ?: getRetrofit(context.applicationContext)
                .create(YClientsApi::class.java)
                .also { yclientsApi = it }
        }
    }

    fun getNeiroApi(context: Context): NeiroApi {
        return neiroApi ?: synchronized(this) {
            neiroApi ?: getRetrofit(context.applicationContext)
                .create(NeiroApi::class.java)
                .also { neiroApi = it }
        }
    }

    fun getPushApi(context: Context): PushApi {
        return pushApi ?: synchronized(this) {
            pushApi ?: getRetrofit(context.applicationContext)
                .create(PushApi::class.java)
                .also { pushApi = it }
        }
    }

    private fun getRetrofit(context: Context): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: createRetrofit(context).also { retrofit = it }
        }
    }

    private fun createRetrofit(context: Context): Retrofit {
        val storage = getTokenStorage(context)

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            // Заданный вызовом заголовок сильнее: так отзывается устройство
            // токеном прошлой сессии, не задев текущую (см. NeiroApi.logout).
            val explicitAuth = originalRequest.header("Authorization") != null
            val authHeader = buildAuthHeader(storage, originalRequest.url.encodedPath)

            val newRequest = originalRequest.newBuilder()
                .header("Accept", ACCEPT_HEADER)
                .header("Content-Type", "application/json")
                .apply {
                    if (!explicitAuth && authHeader.isNotEmpty()) {
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
            .baseUrl(baseUrl())
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Публичный адрес сервиса; пути в API начинаются с `v1/`.
     *
     * Завершающий слеш обязателен: без него Retrofit срезает последний сегмент
     * базового URL при сборке относительного пути.
     */
    private fun baseUrl(): String = BuildConfig.NEIRO_PUSH_API_BASE_URL.trimEnd('/') + "/"

    /**
     * Retry с экспоненциальным backoff на transient-сбои: 408/429/5xx и [IOException].
     * `callTimeout` (60 с) ограничивает суммарное время вместе с ретраями.
     */
    private class RetryInterceptor : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // POST — не идемпотентен (вход, ack, обновление токена): повторный
            // запрос после таймаута может задвоить операцию на сервере.
            val attempts = if (request.method == "POST") 1 else MAX_ATTEMPTS

            var lastException: IOException? = null
            var response: Response? = null

            repeat(attempts) { attempt ->
                response?.close()
                response = try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    lastException = e
                    null
                }

                val current = response
                if (current != null && current.code !in RETRYABLE_CODES) {
                    return current
                }
                if (attempt < attempts - 1) {
                    val delayMs = current?.let(::retryAfterMillis) ?: (RETRY_BASE_DELAY_MS shl attempt)
                    Thread.sleep(delayMs)
                }
            }

            return response ?: throw (lastException ?: IOException("Request failed"))
        }

        /** `Retry-After` — секунды или HTTP-дата; сервер лучше нас знает, когда повторять. */
        private fun retryAfterMillis(response: Response): Long? {
            val header = response.header("Retry-After") ?: return null
            val seconds = header.toLongOrNull() ?: return null
            return (seconds * 1000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }

        private companion object {
            const val MAX_ATTEMPTS = 3
            const val RETRY_BASE_DELAY_MS = 500L
            const val MAX_RETRY_AFTER_MS = 30_000L
            val RETRYABLE_CODES = setOf(408, 429) + (500..599)
        }
    }

    /**
     * Три ключа сервиса — три роли (API.md § Аутентификация). Ключ приложения
     * уходит ровно на вход: он лежит в APK, и подписывать им запросы с данными
     * означало бы отдать их каждому, кто вскрыл APK.
     */
    private fun buildAuthHeader(storage: TokenStorage, path: String): String {
        if (path.endsWith(LOGIN_PATH)) return "Bearer ${BuildConfig.NEIRO_PUSH_API_KEY}"
        val deviceToken = storage.deviceToken ?: return ""
        return "Bearer $deviceToken"
    }

    fun clearInstance() {
        synchronized(this) {
            yclientsApi = null
            neiroApi = null
            retrofit = null
        }
    }
}
