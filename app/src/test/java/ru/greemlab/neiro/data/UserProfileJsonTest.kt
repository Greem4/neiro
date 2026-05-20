package ru.greemlab.neiro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.DayOfWeek

class UserProfileJsonTest {

    @Test
    fun `fromJson returns default for null and empty`() {
        assertEquals(UserProfile(), UserProfileJson.fromJson(null))
        assertEquals(UserProfile(), UserProfileJson.fromJson(""))
        assertEquals(UserProfile(), UserProfileJson.fromJson("{}"))
    }

    @Test
    fun `round trips full profile`() {
        val original = UserProfile(
            name = "Иван",
            activityType = "Репетитор",
            workingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            pricePerSession = 1500.0,
            monthlyTaxAmount = 5000.0,
            isRegistered = true,
        )
        val json = UserProfileJson.toJson(original)
        val parsed = UserProfileJson.fromJson(json)
        assertEquals(original, parsed)
    }

    @Test
    fun `legacy numeric DayOfWeek is parsed`() {
        val json = """
            {"name":"x","activityType":"y","workingDays":[1,3,5],"pricePerSession":0.0,"monthlyTaxAmount":0.0,"isRegistered":true}
        """.trimIndent()
        val parsed = UserProfileJson.fromJson(json)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            parsed.workingDays,
        )
    }

    @Test
    fun `garbage DayOfWeek does not crash`() {
        val json = """
            {"name":"x","activityType":"y","workingDays":["NOT_A_DAY"],"isRegistered":true}
        """.trimIndent()
        val parsed = UserProfileJson.fromJson(json)
        // Не должно бросить — содержимое неважно, главное не упало.
        assertTrue(parsed.isRegistered)
    }

    @Test
    fun `garbage json returns default`() {
        val parsed = UserProfileJson.fromJson("{ not a valid json")
        assertEquals(UserProfile(), parsed)
    }

    @Test
    fun `normalizeLegacy marks registered if essentials filled`() {
        val legacy = UserProfile(name = "Иван", activityType = "Репетитор", isRegistered = false)
        assertTrue(legacy.normalizeLegacy().isRegistered)
    }

    @Test
    fun `normalizeLegacy keeps unregistered when empty`() {
        val empty = UserProfile()
        assertFalse(empty.normalizeLegacy().isRegistered)
    }

    @Test
    fun `normalizeLegacy marks registered if any setting filled`() {
        val onlyPrice = UserProfile(pricePerSession = 1500.0)
        assertTrue(onlyPrice.normalizeLegacy().isRegistered)

        val onlyDays = UserProfile(workingDays = setOf(DayOfWeek.MONDAY))
        assertTrue(onlyDays.normalizeLegacy().isRegistered)
    }
}
