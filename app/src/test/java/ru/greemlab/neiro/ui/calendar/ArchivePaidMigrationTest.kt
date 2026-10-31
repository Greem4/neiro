package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ArchivePaidMigrationTest {

    private val oldDate = LocalDate.of(2026, 8, 20)

    private fun student(
        name: String,
        status: AttendanceStatus,
        time: String = "10:00-10:50",
        comment: String = "",
    ) = SessionFormat.serializeStudentExtended(name, status, time, comment = comment)

    @Test
    fun `arrived in archive becomes paid when YClients says paid and rest matches`() {
        val synced = listOf(student("Иванов", AttendanceStatus.PAID))
        val archived = listOf(student("Иванов", AttendanceStatus.ARRIVED))

        val migrated = ArchivePaidMigration.migrateDay(synced, archived)

        assertEquals(listOf(student("Иванов", AttendanceStatus.PAID)), migrated)
        assertFalse(ArchiveSyncCompare.differs(synced, migrated!!))
    }

    @Test
    fun `other field differs - record is left as is`() {
        val synced = listOf(student("Иванов", AttendanceStatus.PAID, comment = "новый"))
        val archived = listOf(student("Иванов", AttendanceStatus.ARRIVED, comment = "мой"))

        assertNull(ArchivePaidMigration.migrateDay(synced, archived))
    }

    @Test
    fun `different time - no twin, nothing changes`() {
        val synced = listOf(student("Иванов", AttendanceStatus.PAID, time = "11:00-11:50"))
        val archived = listOf(student("Иванов", AttendanceStatus.ARRIVED))

        assertNull(ArchivePaidMigration.migrateDay(synced, archived))
    }

    @Test
    fun `only arrived is promoted - cancelled stays cancelled`() {
        val synced = listOf(student("Иванов", AttendanceStatus.PAID))
        val archived = listOf(student("Иванов", AttendanceStatus.CANCELLED))

        assertNull(ArchivePaidMigration.migrateDay(synced, archived))
    }

    @Test
    fun `promotes matching records and keeps the rest of the day intact`() {
        val synced = listOf(
            student("Иванов", AttendanceStatus.PAID),
            student("Петров", AttendanceStatus.PAID, time = "11:00-11:50", comment = "другой"),
        )
        val archived = listOf(
            student("Иванов", AttendanceStatus.ARRIVED),
            "",
            student("Петров", AttendanceStatus.ARRIVED, time = "11:00-11:50"),
        )

        val migrated = ArchivePaidMigration.migrateDay(synced, archived)

        assertEquals(
            listOf(
                student("Иванов", AttendanceStatus.PAID),
                "",
                student("Петров", AttendanceStatus.ARRIVED, time = "11:00-11:50"),
            ),
            migrated,
        )
    }

    @Test
    fun `intensive children are promoted by name`() {
        val synced = listOf(
            SessionFormat.serializeIntensive(
                price = "",
                name = "Интенсив",
                status = AttendanceStatus.PAID,
                time = "12:00-13:00",
                children = listOf(
                    Session.IntensiveChild("Аня", AttendanceStatus.PAID),
                    Session.IntensiveChild("Боря", AttendanceStatus.CANCELLED),
                ),
            ),
        )
        val archived = listOf(
            SessionFormat.serializeIntensive(
                price = "",
                name = "Интенсив",
                status = AttendanceStatus.ARRIVED,
                time = "12:00-13:00",
                children = listOf(
                    Session.IntensiveChild("Аня", AttendanceStatus.ARRIVED),
                    Session.IntensiveChild("Боря", AttendanceStatus.CANCELLED),
                ),
            ),
        )

        val migrated = ArchivePaidMigration.migrateDay(synced, archived)

        assertTrue(migrated != null)
        assertFalse(ArchiveSyncCompare.differs(synced, migrated!!))
    }

    @Test
    fun `not counted flag survives promotion`() {
        val synced = listOf(
            SessionFormat.serializeStudentExtended(
                "Кац", AttendanceStatus.PAID, "10:00-10:50", notCounted = true,
            ),
        )
        val archived = listOf(
            SessionFormat.serializeStudentExtended(
                "Кац", AttendanceStatus.ARRIVED, "10:00-10:50", notCounted = true,
            ),
        )

        val migrated = ArchivePaidMigration.migrateDay(synced, archived)!!

        assertTrue(migrated.single().startsWith(SessionFormat.NOT_COUNTED_PREFIX))
    }

    @Test
    fun `days from 0_2_5 release on are not touched`() {
        val newDate = ArchivePaidMigration.CUTOFF
        val synced = mapOf(
            oldDate to listOf(student("Иванов", AttendanceStatus.PAID)),
            newDate to listOf(student("Иванов", AttendanceStatus.PAID)),
        )
        val archived = mapOf(
            oldDate to listOf(student("Иванов", AttendanceStatus.ARRIVED)),
            newDate to listOf(student("Иванов", AttendanceStatus.ARRIVED)),
        )

        val changes = ArchivePaidMigration.migrate(synced, archived)

        assertEquals(setOf(oldDate), changes.keys)
    }

    @Test
    fun `day without YClients data is skipped`() {
        val archived = mapOf(oldDate to listOf(student("Иванов", AttendanceStatus.ARRIVED)))

        assertTrue(ArchivePaidMigration.migrate(emptyMap(), archived).isEmpty())
    }

    @Test
    fun `migration is idempotent`() {
        val synced = mapOf(oldDate to listOf(student("Иванов", AttendanceStatus.PAID)))
        val archived = mapOf(oldDate to listOf(student("Иванов", AttendanceStatus.ARRIVED)))

        val once = archived + ArchivePaidMigration.migrate(synced, archived)

        assertTrue(ArchivePaidMigration.migrate(synced, once).isEmpty())
    }
}
