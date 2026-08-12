plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// WebAPI の契約 (リクエスト / レスポンスの形と検証) だけを持つモジュール。
// JVM のサーバーと Kotlin/Native の CLI の両方から使うので、
// ktor などのフレームワークには依存させない。
kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())

    // ターゲットは cli モジュールと揃える。
    // macosX64 (Intel Mac) は Kotlin/Native 側で非推奨のため入れていない。
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            // DTO が @Serializable なので、利用側にも見える api で公開する
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
