package com.gitdroidstore.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.gitdroidstore.GitDroidApplication

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GitDroidApplication
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.parcelable<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                app.database.log("INFO", "Esperando confirmación del sistema")
            }
            PackageInstaller.STATUS_SUCCESS -> app.database.log("SUCCESS", "Instalación completada")
            else -> app.database.log("ERROR", "Instalación fallida ($status): $message")
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
