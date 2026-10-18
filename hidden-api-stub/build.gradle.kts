plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "vegabobo.dsusideloader"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }
    buildTypes {
        create("miniDebug"){}
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}