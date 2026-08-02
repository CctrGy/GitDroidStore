package com.gitdroidstore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gitdroidstore.model.LogEntry
import com.gitdroidstore.model.StoreApp

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "gitdroid.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE apps(owner TEXT NOT NULL, repo TEXT NOT NULL, json TEXT NOT NULL, checked_at INTEGER NOT NULL, PRIMARY KEY(owner, repo))""")
        db.execSQL("""CREATE TABLE logs(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceApps(owner: String, apps: List<StoreApp>) = writableDatabase.inTransaction {
        delete("apps", "owner=?", arrayOf(owner))
        apps.forEach { app ->
            insertOrThrow("apps", null, ContentValues().apply {
                put("owner", app.owner); put("repo", app.repo); put("json", app.toJson().toString()); put("checked_at", app.lastCheckedAt)
            })
        }
    }

    fun loadApps(owner: String): List<StoreApp> = readableDatabase.query("apps", arrayOf("json"), "owner=?", arrayOf(owner), null, null, "repo COLLATE NOCASE").use { c ->
        buildList { while (c.moveToNext()) add(appFromJson(org.json.JSONObject(c.getString(0)))) }
    }

    fun log(level: String, message: String) {
        writableDatabase.insert("logs", null, ContentValues().apply {
            put("timestamp", System.currentTimeMillis()); put("level", level); put("message", message.take(2_000))
        })
    }

    fun logs(): List<LogEntry> = readableDatabase.query("logs", null, null, null, null, null, "timestamp DESC", "250").use { c ->
        buildList { while (c.moveToNext()) add(LogEntry(c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("timestamp")), c.getString(c.getColumnIndexOrThrow("level")), c.getString(c.getColumnIndexOrThrow("message")))) }
    }
}

private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction(); try { block(); setTransactionSuccessful() } finally { endTransaction() }
}

private fun StoreApp.toJson() = org.json.JSONObject().apply {
    put("owner", owner); put("repo", repo); put("displayName", displayName); put("description", description)
    put("defaultBranch", defaultBranch); put("apkUrl", apkUrl); put("iconUrl", iconUrl); put("versionName", versionName)
    put("versionCode", versionCode); put("expectedSha256", expectedSha256); put("expectedCertificateSha256", expectedCertificateSha256)
    put("packageName", packageName); put("remoteSha", remoteSha); put("downloadedSha256", downloadedSha256)
    put("installedVersionCode", installedVersionCode); put("installedCertificateSha256", installedCertificateSha256); put("lastCheckedAt", lastCheckedAt)
}

private fun appFromJson(j: org.json.JSONObject) = StoreApp(
    j.getString("owner"), j.getString("repo"), j.getString("displayName"), j.optString("description"), j.getString("defaultBranch"),
    j.getString("apkUrl"), j.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" }, j.optString("versionName").takeIf { it.isNotBlank() && it != "null" },
    j.optLongOrNull("versionCode"), j.optString("expectedSha256").takeIf { it.isNotBlank() && it != "null" },
    j.optString("expectedCertificateSha256").takeIf { it.isNotBlank() && it != "null" }, j.optString("packageName").takeIf { it.isNotBlank() && it != "null" },
    j.getString("remoteSha"), j.optString("downloadedSha256").takeIf { it.isNotBlank() && it != "null" }, j.optLongOrNull("installedVersionCode"),
    j.optString("installedCertificateSha256").takeIf { it.isNotBlank() && it != "null" }, j.optLong("lastCheckedAt")
)
private fun org.json.JSONObject.optLongOrNull(name: String) = if (has(name) && !isNull(name)) getLong(name) else null
