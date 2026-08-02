package com.gitdroidstore.model

data class StoreApp(
    val owner: String,
    val repo: String,
    val displayName: String,
    val description: String,
    val defaultBranch: String,
    val apkUrl: String,
    val iconUrl: String?,
    val versionName: String?,
    val versionCode: Long?,
    val expectedSha256: String?,
    val expectedCertificateSha256: String?,
    val packageName: String?,
    val remoteSha: String,
    val downloadedSha256: String? = null,
    val installedVersionCode: Long? = null,
    val installedCertificateSha256: String? = null,
    val lastCheckedAt: Long = System.currentTimeMillis()
) {
    val isInstalled get() = installedVersionCode != null
    val hasUpdate get() = versionCode?.let { available ->
        installedVersionCode?.let { installed -> available > installed }
    } == true
}

data class VersionMetadata(
    val versionName: String? = null,
    val versionCode: Long? = null,
    val sha256: String? = null,
    val certificateSha256: String? = null,
    val packageName: String? = null
)

data class ApkIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val certificateSha256: String,
    val fileSha256: String,
    val sizeBytes: Long
)

data class LogEntry(val id: Long, val timestamp: Long, val level: String, val message: String)
