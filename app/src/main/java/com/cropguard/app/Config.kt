package com.cropguard.app

object Config {
    // URL chính của CropGuard
    const val BASE_URL = "https://cropnlu.duckdns.org"

    // Domain được phép load trong WebView (chặn điều hướng ra ngoài app)
    val ALLOWED_HOSTS = setOf(
        "cropnlu.duckdns.org"
    )

    // Domain ngoại lệ mở bằng browser thật (OAuth Google login, thanh toán, v.v.)
    val EXTERNAL_HOSTS = setOf(
        "accounts.google.com",
        "github.com"
    )
}
