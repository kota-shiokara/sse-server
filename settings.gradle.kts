plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sse-server"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("shared")
include("server")

// Docker でサーバーイメージを焼くときは CLI を構成に含めない。
// CLI は Kotlin/Native なので、含めるとビルド用コンテナに不要な
// ネイティブツールチェーンの解決が発生する。
if (providers.gradleProperty("sse.serverOnly").orNull != "true") {
    include("cli")
}
