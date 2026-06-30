package ru.greemlab.neiro.push

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

object PushSyncCoordinator {

    private const val WORK_NAME = "push_fcm_sync"

    fun enqueue(context: Context) {
        if (!PushConfig.isServerConfigured) return

        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
