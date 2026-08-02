package com.gitdroidstore

object StoreConfig {
    const val OFFICIAL_GITHUB_OWNER = "CctrGy"
    const val OFFICIAL_REPOSITORY = "GitDroidStore"
    const val OFFICIAL_REPOSITORY_URL = "https://github.com/CctrGy/GitDroidStore"
    const val APPLICATION_ID = "com.gitdroidstore"

    fun catalogUrl(owner: String): String =
        "https://raw.githubusercontent.com/$owner/$OFFICIAL_REPOSITORY/main/catalog.json"
}
