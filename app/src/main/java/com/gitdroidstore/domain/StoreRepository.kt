package com.gitdroidstore.domain

import android.content.Context
import com.gitdroidstore.data.AppDatabase
import com.gitdroidstore.data.GitHubClient
import com.gitdroidstore.data.SettingsStore
import com.gitdroidstore.install.PackageInstallerGateway
import com.gitdroidstore.model.StoreApp
import com.gitdroidstore.security.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StoreRepository(
    private val context: Context,
    private val github: GitHubClient,
    private val db: AppDatabase,
    private val settings: SettingsStore
) {
    private val verifier = ApkVerifier(context)
    val installer = PackageInstallerGateway(context)

    suspend fun cachedApps() = withContext(Dispatchers.IO) { db.loadApps(settings.githubUser) }

    suspend fun refresh(): List<StoreApp> = withContext(Dispatchers.IO) {
        require(settings.githubUser.isNotBlank()) { "Configura un usuario de GitHub" }
        db.log("INFO", "Buscando repositorios de ${settings.githubUser}")
        val discovered = github.discover(settings.githubUser, settings.githubToken).map { app ->
            val installed = app.packageName?.let(verifier::installed)
            app.copy(installedVersionCode = installed?.first, installedCertificateSha256 = installed?.second)
        }
        db.replaceApps(settings.githubUser, discovered)
        db.log("SUCCESS", "Encontradas ${discovered.size} aplicaciones")
        discovered
    }

    suspend fun prepareAndInstall(app: StoreApp): Int = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val target = File(dir, app.repo.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk")
        db.log("INFO", "Descargando ${app.displayName}")
        github.download(app.apkUrl, settings.githubToken, target)
        try {
            val identity = verifier.inspect(target)
            require(app.packageName == null || identity.packageName == app.packageName) { "El packageName no coincide con version.json" }
            require(app.versionCode == null || identity.versionCode == app.versionCode) { "El versionCode no coincide con version.json" }
            val installed = verifier.installed(identity.packageName)
            verifier.verify(identity, app.expectedSha256, app.expectedCertificateSha256, installed)
            db.log("SUCCESS", "APK verificado: ${identity.fileSha256}, ${identity.sizeBytes} bytes")
            installer.install(target, identity.packageName)
        } catch (e: Exception) {
            target.delete(); db.log("ERROR", "${app.displayName}: ${e.message}"); throw e
        }
    }

    suspend fun logs() = withContext(Dispatchers.IO) { db.logs() }
}
