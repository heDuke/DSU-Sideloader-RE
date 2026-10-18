import java.util.Properties

fun getReleaseSigningConfig(): File {
    return File(".sign/dsu_sideloader.prop")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.serialization)
}

android {
    val versionCode: Int by rootProject.extra
    val versionName: String by rootProject.extra
    val packageName: String by rootProject.extra

    namespace = packageName
    compileSdk = 35

    defaultConfig {
        this.applicationId = packageName
        this.versionCode = versionCode
        this.versionName = versionName

        minSdk = 29
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val releaseSigningConfig = getReleaseSigningConfig()
        if (releaseSigningConfig.exists()) {
            create("release") {
                /**
                 * .sign/dsu_sideloader.prop
                 *
                 * keystore=some/path/keystore.jks
                 * keystore_pw=keystore_password
                 * alias=alias
                 * alias_pw=alias_password
                 *
                 */
                val props = Properties()
                props.load(releaseSigningConfig.inputStream())

                storeFile = File(props.getProperty("keystore"))
                storePassword = props.getProperty("keystore_pw")
                keyAlias = props.getProperty("alias")
                keyPassword = props.getProperty("alias_pw")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (getReleaseSigningConfig().exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("miniDebug") {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

aboutLibraries {
    // Remove the "generated" timestamp to allow for reproducible builds
    excludeFields = arrayOf("generated")
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.RequiresOptIn")
}

val kotlinStyle by configurations.creating

dependencies {
    implementation(libs.appcompat)
    implementation(libs.datastore)
    implementation(libs.activity.compose)
    implementation(libs.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material)
    implementation(libs.compose.runtime)
    implementation(libs.compose.livedata)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    implementation(libs.compose.ui)
    implementation(libs.core)
    implementation(libs.fragment)
    implementation(libs.preference)
    implementation(libs.lifecycle)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation)
    kapt(libs.hilt.compiler)
    implementation(libs.material)
    implementation(libs.serialization)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.xz)
    implementation(libs.compress)
    implementation(libs.aboutlibraries)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapi)
    compileOnly(project(":hidden-api-stub"))
    testImplementation(libs.junit)
    kotlinStyle(libs.ktlint)
}

// Run the existing style engine in a separate JVM. Its Kotlin parser must not be
// placed on the Gradle/Kotlin plugin classpath (incompatible with Kotlin 2.x).
val lintKotlin by tasks.registering(JavaExec::class) {
    group = "verification"
    classpath = kotlinStyle
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args("src/**/*.kt")
}
tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    classpath = kotlinStyle
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args("-F", "src/**/*.kt")
}
tasks.named("check") { dependsOn(lintKotlin) }
