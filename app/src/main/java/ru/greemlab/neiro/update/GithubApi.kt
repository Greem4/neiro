package ru.greemlab.neiro.update

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * GitHub Releases — единственный источник правды о новых версиях.
 *
 * Токен не нужен: репозиторий публичный. Без токена GitHub даёт 60 запросов в
 * час на IP; приложение тратит один в сутки плюс редкие ручные проверки.
 * Заголовки (`Accept`, `X-GitHub-Api-Version`, `User-Agent`) ставит
 * интерцептор в [UpdateClient], чтобы не повторять их на каждом методе.
 */
interface GithubApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<GithubRelease>
}
