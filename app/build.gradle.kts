plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun prop(key: String, default: String): String =
    (providers.gradleProperty(key).orNull
        ?: System.getenv(key.uppercase().replace('.', '_'))
        ?: localProperties.getProperty(key, default)).replace("\"", "\\\"")

val appVersionCode = prop("app.version.code", "3").toInt()
val appVersionName = prop("app.version.name", "1.2.0")
val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    ?: localProperties.getProperty("android.keystore.file")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    ?: localProperties.getProperty("android.key.alias")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.robot.solar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robot.solar"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"${prop("api.base.url", "http://10.0.2.2/api")}\"")
        buildConfigField("String", "MQTT_HOST", "\"${prop("mqtt.host", "47.103.157.213")}\"")
        buildConfigField("int", "MQTT_PORT", prop("mqtt.port", "1883"))
        buildConfigField("String", "MQTT_USERNAME", "\"${prop("mqtt.username", "app_user_001")}\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"${prop("mqtt.password", "")}\"")
        buildConfigField("String", "MQTT_DEFAULT_PRODUCT_TYPE", "\"${prop("mqtt.product_type", "crawler")}\"")
        buildConfigField("String", "MQTT_DEFAULT_DEVICE_ID", "\"${prop("mqtt.default_device_id", "crawler_00000001")}\"")
        buildConfigField("String", "MISSION_COMMAND_API_CAPABILITY", "\"mission_command_v2\"")
        buildConfigField("String", "APP_UPDATE_CHANNEL", "\"${prop("app.update.channel", "test")}\"")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("distribution") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_CONTROL_BYPASS", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("distribution")
            }
            buildConfigField("boolean", "DEBUG_CONTROL_BYPASS", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets {
        getByName("main").assets.srcDir(
            rootProject.file("docs/requirements/map_planner/config")
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.paho.mqtt)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

kapt {
    correctErrorTypes = true
}

kotlin {
    jvmToolchain(21)
}
