package ru.greemlab.neiro.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import ru.greemlab.neiro.domain.models.UserProfile
import java.lang.reflect.Type
import java.time.DayOfWeek

/**
 * Сериализация профиля с обратной совместимостью между версиями приложения.
 *
 * Принципиально не бросает исключения наружу: при битых данных возвращает
 * пустой [UserProfile] — это безопаснее, чем краш на старте.
 */
object UserProfileJson {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(DayOfWeek::class.java, DayOfWeekAdapter)
        .create()

    fun toJson(profile: UserProfile): String = gson.toJson(profile)

    /** Парсит JSON «как есть», без миграции. Не бросает исключений. */
    fun fromJsonRaw(json: String?): UserProfile {
        if (json.isNullOrBlank() || json == "{}") return UserProfile()
        return runCatching { gson.fromJson(json, UserProfile::class.java) }
            .getOrNull()
            ?: UserProfile()
    }

    /** Парсит JSON и применяет миграцию устаревших форматов. */
    fun fromJson(json: String?): UserProfile = fromJsonRaw(json).normalizeLegacy()
}

/**
 * Профили, созданные до поля [UserProfile.isRegistered], считаем зарегистрированными,
 * если заполнены основные данные.
 */
fun UserProfile.normalizeLegacy(): UserProfile {
    if (isRegistered) return this
    val hasIdentity = name.isNotBlank() && activityType.isNotBlank()
    val hasSettings = pricePerSession > 0.0 || workingDays.isNotEmpty() || monthlyTaxAmount > 0.0
    return if (hasIdentity || hasSettings) copy(isRegistered = true) else this
}

/**
 * Адаптер для [DayOfWeek] с поддержкой исторических форматов:
 * строкового (`MONDAY`) и числового (`1` … `7`).
 *
 * При любом непарсимом значении возвращает [DayOfWeek.MONDAY] — это сохраняет работоспособность
 * приложения и предсказуемо: пустой день недели лучше, чем краш сериализации.
 */
private object DayOfWeekAdapter : JsonSerializer<DayOfWeek>, JsonDeserializer<DayOfWeek> {
    override fun serialize(
        src: DayOfWeek,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonPrimitive(src.name)

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): DayOfWeek {
        val raw = if (json.isJsonPrimitive) json.asString else json.toString()
        val trimmed = raw.trim()
        // 1. Современный формат: строковая константа enum.
        runCatching { return DayOfWeek.valueOf(trimmed.uppercase()) }
        // 2. Исторический формат: число от 1 (Пн) до 7 (Вс).
        val asNumber = trimmed.toIntOrNull()
        if (asNumber != null) {
            return DayOfWeek.of(asNumber.coerceIn(1, 7))
        }
        // 3. Битое значение — не падаем, возвращаем безопасный дефолт.
        return DayOfWeek.MONDAY
    }
}
