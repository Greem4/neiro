package ru.greemlab.neiro.update

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.greemlab.neiro.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Клиент GitHub — по образцу
 * [ru.greemlab.neiro.data.network.YClientsClient]: тот же ленивый синглтон, те
 * же таймауты, тот же лог в debug.
 *
 * Отдельный OkHttp, а не общий с сервисом Neiro, — принципиально: интерцептор
 * YClientsClient подставляет `Authorization: Bearer <device_token>` во все
 * запросы, и на api.github.com этот токен уезжать не должен. Сюда вообще не
 * уходит ни одного секрета: репозиторий публичный, запрос анонимный.
 *
 * [httpClient] нужен ещё и загрузчику APK (этап 6) — качать теми же
 * настройками, но без Retrofit.
 */
object UpdateClient {

    @Volatile
    private var api: GithubApi? = null

    @Volatile
    private var httpClient: OkHttpClient? = null

    fun getApi(): GithubApi {
        return api ?: synchronized(this) {
            api ?: createRetrofit().create(GithubApi::class.java).also { api = it }
        }
    }

    fun getHttpClient(): OkHttpClient {
        return httpClient ?: synchronized(this) {
            httpClient ?: createHttpClient().also { httpClient = it }
        }
    }

    private fun createRetrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(UpdateConfig.GITHUB_API_URL)
        .client(getHttpClient())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private fun createHttpClient(): OkHttpClient {
        val headerInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", UpdateConfig.GITHUB_ACCEPT)
                .header("X-GitHub-Api-Version", UpdateConfig.GITHUB_API_VERSION)
                .header("User-Agent", UpdateConfig.userAgent)
                .build()
            chain.proceed(request)
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // callTimeout не ставим намеренно: этим же клиентом качается APK на
            // 15 МБ, и общий лимит на вызов оборвал бы закачку на медленной сети.
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
        }

        return builder.build()
    }
}
