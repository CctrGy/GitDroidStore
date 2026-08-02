package com.gitdroidstore

import android.app.Application
import com.gitdroidstore.data.AppDatabase
import com.gitdroidstore.data.GitHubClient
import com.gitdroidstore.data.SettingsStore
import com.gitdroidstore.domain.StoreRepository
import com.gitdroidstore.update.UpdateScheduler

class GitDroidApplication : Application() {
    val settings by lazy { SettingsStore(this) }
    val database by lazy { AppDatabase(this) }
    val repository by lazy { StoreRepository(this, GitHubClient(), database, settings) }
    override fun onCreate() {
        super.onCreate()
        UpdateScheduler.schedule(this, settings.autoCheck)
    }
}
