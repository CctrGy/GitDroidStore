package com.gitdroidstore.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.gitdroidstore.GitDroidApplication
import com.gitdroidstore.MainActivity
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GitDroidApplication
        if (app.settings.githubUser.isBlank()) return Result.success()
        return try {
            val before = app.repository.cachedApps().associateBy { it.repo }
            val after = app.repository.refresh()
            val changed = after.filter { candidate -> before[candidate.repo]?.remoteSha?.let { it != candidate.remoteSha } == true }
            if (changed.isNotEmpty()) notifyUpdates(changed.size)
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun notifyUpdates(count: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1001, NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Actualizaciones disponibles")
            .setContentText("$count APK cambiaron en GitHub").setAutoCancel(true).setContentIntent(open).build())
    }

    companion object { private const val CHANNEL = "updates" }
}

object UpdateScheduler {
    private const val NAME = "github-update-check"
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork(NAME); return }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
