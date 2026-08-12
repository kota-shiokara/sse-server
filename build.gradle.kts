// ルートプロジェクトはコードを持たない。
// Kotlin プラグインはここで一度だけ読み込む (apply false)。
// サブプロジェクトごとにバージョン付きで宣言すると
// 「loaded multiple times」の警告が出るため。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

allprojects {
    group = "com.example"
    version = "0.1.0"
}
