plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 単一バイナリの CLI。サーバーには WebAPI 経由でしか触らないので、
// 共有しているのは shared モジュール (契約) だけ。
kotlin {
    // macosX64 (Intel Mac) は Kotlin/Native 側で非推奨なので入れていない。
    // linuxArm64 は Apple Silicon 上の Docker コンテナ内から叩く用。
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "jp.ikanoshiokara.sse.cli.main"
            baseName = "ssectl"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.clikt)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/**
 * ビルドを走らせているホストに対応するターゲット。対応が無ければ null。
 *
 * Gradle の internal API (`org.gradle.internal.os.OperatingSystem`) は使わない。
 * Gradle のバージョンや IDE 同梱の Gradle で消えていると、
 * このファイルの読み込み自体が失敗してしまうため。
 */
val hostTarget: String? = run {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arm = System.getProperty("os.arch").orEmpty().lowercase() in setOf("aarch64", "arm64")
    when {
        os.startsWith("mac") && arm -> "macosArm64"
        os.startsWith("linux") && arm -> "linuxArm64"
        os.startsWith("linux") -> "linuxX64"
        else -> null
    }
}

/**
 * ホスト向けのリリースバイナリを `cli/build/install/ssectl` に置く。
 *
 * `build/bin/<target>/releaseExecutable/ssectl.kexe` のままだと PATH に通しづらいので、
 * 拡張子を落として 1 箇所に集める。
 *
 * 対応ターゲットが無いホスト (Intel Mac / Windows) でも、このタスクを実行した
 * ときだけ失敗するようにしてある。設定フェーズで落とすと、CLI に関係のない
 * `:server:test` なども巻き込んでビルド全体が動かなくなるため。
 */
if (hostTarget != null) {
    // Copy ではなく Sync。改名したときに build/install へ旧名のバイナリが
    // 残り続けると、どちらが最新か分からなくなるため。
    tasks.register<Sync>("installCli") {
        group = "distribution"
        description = "ホスト ($hostTarget) 向けのリリースバイナリを build/install/ssectl に置く"

        dependsOn("linkReleaseExecutable${hostTarget.replaceFirstChar { it.uppercase() }}")

        from(layout.buildDirectory.file("bin/$hostTarget/releaseExecutable/ssectl.kexe"))
        into(layout.buildDirectory.dir("install"))
        rename { "ssectl" }
    }
} else {
    val name = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    tasks.register("installCli") {
        group = "distribution"
        description = "このホスト ($name / $arch) 向けのターゲットは無い"
        doLast {
            throw GradleException(
                """
                このホストで動くバイナリは作れません: $name / $arch
                対応しているのは macosArm64 / linuxX64 / linuxArm64 です。

                Intel Mac の場合、macosX64 は Kotlin/Native 側で非推奨のため
                ターゲットに入れていません。必要なら cli/build.gradle.kts の
                targets に macosX64() を足してください。
                """.trimIndent()
            )
        }
    }
}
