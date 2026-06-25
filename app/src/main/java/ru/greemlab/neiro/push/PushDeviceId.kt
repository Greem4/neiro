package ru.greemlab.neiro.push

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

object PushDeviceId {

    private const val PREFS = "neiro_push_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

        val generated = androidId ?: UUID.randomUUID().toString()
        val deviceId = "neiro-${Build.MODEL}-$generated".take(120)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }
}
