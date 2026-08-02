package com.gitdroidstore.data

import com.gitdroidstore.model.StoreApp
import com.gitdroidstore.model.VersionMetadata
import com.gitdroidstore.StoreConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class GitHubClient {
    fun discover(owner: String, token: String): List<StoreApp> {
        require(owner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"))) { "Usuario de GitHub no válido" }
        val repos = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val batch = JSONArray(get("https://api.github.com/users/$owner/repos?per_page=100&page=$page", token))
            repeat(batch.length()) { repos += batch.getJSONObject(it) }
            if (batch.length() < 100) break
            page++
        }
        return repos.mapNotNull { repo -> inspectRepo(owner, repo, token) }
    }

    private fun inspectRepo(owner: String, repo: JSONObject, token: String): StoreApp? {
        val name = repo.getString("name")
        val branch = repo.optString("default_branch", "main")
        val release = try {
            JSONObject(get("https://api.github.com/repos/$owner/$name/releases/latest", token))
        } catch (e: GitHubHttpException) {
            if (e.statusCode == 404) return null else throw e
        }
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "app.apk" && it.optString("state") == "uploaded" }
            ?: return null
        val apkUrl = apk.optString("browser_download_url")
            .takeIf { isReleaseDownloadUrl(it, owner, name) }
            ?: return null
        val contents = try {
            JSONArray(get("https://api.github.com/repos/$owner/$name/contents/?ref=${encode(branch)}", token))
        } catch (e: GitHubHttpException) {
            if (e.statusCode == 404) JSONArray() else throw e
        }
        val files = (0 until contents.length()).associate { index ->
            val item = contents.getJSONObject(index)
            item.getString("name") to item
        }
        val metadata = files["version.json"]?.optString("download_url")
            ?.takeIf { it.startsWith("https://raw.githubusercontent.com/") }
            ?.let { runCatching { parseVersion(get(it, token)) }.getOrNull() } ?: VersionMetadata()
        val friendlyName = files["appname.txt"]?.optString("download_url")
            ?.takeIf { it.startsWith("https://raw.githubusercontent.com/") }
            ?.let { runCatching { get(it, token).trim().take(100) }.getOrNull() }
            .orEmpty().ifBlank { name }
        val isOfficialStore = owner.equals(StoreConfig.OFFICIAL_GITHUB_OWNER, true) &&
            name.equals(StoreConfig.OFFICIAL_REPOSITORY, true)
        val releaseTag = release.optString("tag_name").trim()
        val releaseDigest = apk.optString("digest")
            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')?.normalizeHash()
        val metadataDigest = metadata.sha256?.normalizeHash()
        if (releaseDigest != null && metadataDigest != null && releaseDigest != metadataDigest) return null
        val remoteIdentity = releaseDigest
            ?: "${release.optLong("id")}:${apk.optLong("id")}:${apk.optString("updated_at")}:${apk.optLong("size")}"
        return StoreApp(
            owner = owner, repo = name, displayName = friendlyName,
            description = repo.optString("description").takeUnless { it == "null" }.orEmpty(),
            defaultBranch = branch, apkUrl = apkUrl,
            iconUrl = files["icon.png"]?.optString("download_url")?.takeIf { it.isNotBlank() },
            versionName = metadata.versionName ?: releaseTag.removePrefix("v").ifBlank { null }, versionCode = metadata.versionCode,
            expectedSha256 = releaseDigest ?: metadataDigest,
            expectedCertificateSha256 = metadata.certificateSha256?.normalizeHash(),
            packageName = metadata.packageName ?: if (isOfficialStore) StoreConfig.APPLICATION_ID else null,
            remoteSha = remoteIdentity
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
        if (conn.responseCode !in 200..299) throw GitHubHttpException(conn.responseCode, "GitHub respondió ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText().orEmpty()}")
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun connection(url: String, token: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        setRequestProperty("User-Agent", "GitDroidStore/1")
        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect() }
    private fun isReleaseDownloadUrl(url: String, owner: String, repo: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals("github.com", true) &&
            uri.path.startsWith("/$owner/$repo/releases/download/", true)
    }.getOrDefault(false)
    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun parseVersion(raw: String): VersionMetadata = JSONObject(raw).let {
        VersionMetadata(it.optString("versionName").ifBlank { null }, if (it.has("versionCode")) it.optLong("versionCode") else null,
            it.optString("sha256").ifBlank { null }, it.optString("certificateSha256").ifBlank { null }, it.optString("packageName").ifBlank { null })
    }
    private fun String.normalizeHash() = replace(":", "").lowercase()
}

private class GitHubHttpException(val statusCode: Int, message: String) : IOException(message)
