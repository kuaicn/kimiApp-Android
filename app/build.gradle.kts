import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// 正式签名材料（keystore.properties 不入库）
val signingProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.kimi.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kimi.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingProps.getProperty("storeFile", "keystore/kimi-release.jks"))
            storePassword = signingProps.getProperty("storePassword", "")
            keyAlias = signingProps.getProperty("keyAlias", "kimi")
            keyPassword = signingProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            // 不混淆不压缩资源，仅正式签名
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingProps.getProperty("storePassword", "").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // debug 默认使用自动生成的 debug 签名
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.ulid.creator)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
