package com.cropguard.app

object Config {
    // URL chinh cua CropGuard
    const val BASE_URL = "https://cropnlu.duckdns.org"

    // API endpoint kiem tra phien ban knowledge base
    const val VERSION_URL = "https://cropnlu.duckdns.org/api/knowledge/version"

    // SharedPreferences key luu so entries lan mo truoc
    const val PREF_NAME = "cropguard_prefs"
    const val PREF_LAST_COUNT = "last_known_count"

    // Domain duoc phep load trong WebView
    val ALLOWED_HOSTS = setOf(
        "cropnlu.duckdns.org"
    )

    // Domain ngoai le mo bang browser that (OAuth Google login, v.v.)
    val EXTERNAL_HOSTS = setOf(
        "accounts.google.com",
        "github.com"
    )
}
