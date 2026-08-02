package com.gitdroidstore.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gitdroidstore.model.ApkIdentity
import java.io.File
import java.security.MessageDigest

class ApkVerifier(private val context: Context) {
    fun inspect(file: File): ApkIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("El archivo no es un APK Android válido")
        val certs = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures)
            ?: error("El APK no contiene una firma válida")
        require(certs.isNotEmpty()) { "El APK no contiene certificados" }
        return ApkIdentity(info.packageName, info.versionName, info.longVersionCode,
            sha256(certs.first().toByteArray()), sha256(file), file.length())
    }

    fun installed(packageName: String): Pair<Long, String>? {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(packageName, flags)
            val certs = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures)
                ?: return null
            val certificate = certs.firstOrNull() ?: return null
            info.longVersionCode to sha256(certificate.toByteArray())
        } catch (_: PackageManager.NameNotFoundException) { null }
    }

    fun verify(identity: ApkIdentity, expectedHash: String?, expectedCert: String?, installed: Pair<Long, String>?) {
        if (expectedHash != null) require(identity.fileSha256.equals(expectedHash, true)) { "El SHA-256 no coincide con version.json" }
        if (expectedCert != null) require(identity.certificateSha256.equals(expectedCert, true)) { "El certificado no coincide con version.json" }
        if (installed != null) {
            require(identity.certificateSha256.equals(installed.second, true)) { "La firma no coincide con la aplicación instalada" }
            require(identity.versionCode >= installed.first) { "Se bloqueó un downgrade (${identity.versionCode} < ${installed.first})" }
        } else require(expectedCert != null) {
            "Primera instalación bloqueada: version.json debe declarar certificateSha256"
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
