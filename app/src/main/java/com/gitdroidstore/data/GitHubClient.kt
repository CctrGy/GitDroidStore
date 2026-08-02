package com.gitdroidstore.data

import com.gitdroidstore.StoreConfig
import com.gitdroidstore.model.StoreApp
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class GitHubClient {
    /** Downloads one public static file. Repository discovery is performed by GitHub Actions. */
    fun discover(catalogOwner: String, @Suppress("UNUSED_PARAMETER") token: String): List<StoreApp> {
        require(catalogOwner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"))) {
            "Usuario de GitHub no válido"
        }
        val catalog = JSONObject(get(StoreConfig.catalogUrl(catalogOwner), ""))
        require(catalog.optInt("schemaVersion") == 1) { "Versión de catálogo no compatible" }
        val apps = catalog.optJSONArray("apps") ?: error("El catálogo no contiene apps")
        return (0 until apps.length()).map { parseApp(apps.getJSONObject(it)) }
    }

    private fun parseApp(value: JSONObject): StoreApp {
        val owner = value.getString("owner")
        val repo = value.getString("repo")
        require(owner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"))) { "Propietario no válido" }
        require(repo.matches(Regex("[A-Za-z0-9._-]+"))) { "Repositorio no válido" }
        val apkUrl = value.getString("apkUrl")
        require(isReleaseDownloadUrl(apkUrl, owner, repo)) { "Enlace APK no válido para $owner/$repo" }
        val iconUrl = value.optString("iconUrl").ifBlank { null }
        require(iconUrl == null || isRawGitHubUrl(iconUrl, owner, repo)) { "Enlace de icono no válido para $owner/$repo" }
        return StoreApp(
            owner = owner,
            repo = repo,
            displayName = value.optString("displayName").ifBlank { repo }.take(100),
            description = value.optString("description").takeUnless { it == "null" }.orEmpty(),
            defaultBranch = value.optString("defaultBranch", "main"),
            apkUrl = apkUrl,
            iconUrl = iconUrl,
            versionName = value.optString("versionName").ifBlank { null },
            versionCode = value.optLongOrNull("versionCode"),
            expectedSha256 = value.optString("sha256").ifBlank { null }?.normalizeHash(),
            expectedCertificateSha256 = value.optString("certificateSha256").ifBlank { null }?.normalizeHash(),
            packageName = value.optString("packageName").ifBlank { null },
            remoteSha = value.optString("remoteSha").ifBlank { value.getString("apkUrl") }
        )
    }

    fun download(url: String, token: String, output: java.io.File) {
        require(url.startsWith("https://raw.githubusercontent.com/") || url.startsWith("https://github.com/"))
        connection(url, token).useConnection { conn ->
            if (conn.responseCode !in 200..299) throw IOException("GitHub respondió ${conn.responseCode}")
            conn.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun get(url: String, token: String): String = connection(url, token).useConnection { conn ->
        if (conn.responseCode !in 200..299) throw IOException("GitHub respondió ${conn.responseCode}")
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun connection(url: String, token: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "GitDroidStore/1")
        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun isReleaseDownloadUrl(url: String, owner: String, repo: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals("github.com", true) &&
            uri.path.startsWith("/$owner/$repo/releases/download/", true) && uri.path.endsWith("/app.apk", true)
    }.getOrDefault(false)

    private fun isRawGitHubUrl(url: String, owner: String, repo: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals("raw.githubusercontent.com", true) &&
            uri.path.startsWith("/$owner/$repo/", true)
    }.getOrDefault(false)

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun String.normalizeHash() = replace(":", "").lowercase()
}
