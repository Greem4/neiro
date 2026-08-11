package ru.greemlab.neiro.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Когда о версии говорить, а когда молчать. Три случая, которые легко
 * перепутать: та же версия дважды, пропущенная версия и следующая после
 * пропущенной — телефон, звенящий каждые сутки об одном и том же выпуске,
 * выключают целиком.
 */
class UpdateNotificationPolicyTest {

    @Test
    fun `о новой версии говорим`() {
        assertTrue(UpdateNotifier.shouldNotify(versionCode = 200, notifiedVersionCode = 0, skippedVersionCode = 0))
    }

    @Test
    fun `о той же версии второй раз молчим`() {
        assertFalse(UpdateNotifier.shouldNotify(versionCode = 200, notifiedVersionCode = 200, skippedVersionCode = 0))
    }

    @Test
    fun `пропущенная версия молчит`() {
        assertFalse(UpdateNotifier.shouldNotify(versionCode = 200, notifiedVersionCode = 200, skippedVersionCode = 200))
    }

    @Test
    fun `следующая после пропущенной пробивается сама`() {
        assertTrue(UpdateNotifier.shouldNotify(versionCode = 201, notifiedVersionCode = 200, skippedVersionCode = 200))
    }

    @Test
    fun `версия старее пропущенной не всплывает`() {
        // Такое бывает после отката релиза: в GitHub снова оказалась старая
        // версия, но пользователь про неё уже сказал «не хочу».
        assertFalse(UpdateNotifier.shouldNotify(versionCode = 199, notifiedVersionCode = 0, skippedVersionCode = 200))
    }
}
