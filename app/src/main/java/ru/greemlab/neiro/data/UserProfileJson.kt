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
 */
object UserProfileJson {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(DayOfWeek::class.java, DayOfWeekAdapter)
        .create()

    fun toJson(profile: UserProfile): String = gson.toJson(profile)

    fun fromJsonRaw(json: String?): UserProfile {
        if (json.isNullOrBlank() || json == "{}") return UserProfile()
        return try {
            gson.fromJson(json, UserProfile::class.java) ?: UserProfile()
        } catch (_: Exception) {
            UserProfile()
        }
    }

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
        val raw = when {
            json.isJsonPrimitive -> json.asString
            else -> json.toString()
        }
        return runCatching { DayOfWeek.valueOf(raw.trim().uppercase()) }
            .getOrElse {
                // Поддержка старых числовых значений (1 = MONDAY … 7 = SUNDAY)
                DayOfWeek.of(raw.trim().toInt().coerceIn(1, 7))
            }
    }
}
