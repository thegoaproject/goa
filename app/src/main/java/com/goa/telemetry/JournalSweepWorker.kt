package com.goa.telemetry

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic safety net: ingests journals left behind by crashes even if the app isn't reopened from a game. */
class JournalSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        TelemetryIngestion.run(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "goa_journal_sweep"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<JournalSweepWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }
}
