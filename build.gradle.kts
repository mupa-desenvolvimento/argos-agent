import java.util.Properties
import kotlin.text.Charsets.UTF_8

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val versionPropsFile = file("version.properties")
fun readVersionProps(): Properties {
    val p = Properties()
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { p.load(it) }
    }
    return p
}

fun writeVersionProps(versionName: String, versionCode: Int) {
    versionPropsFile.writeText(
        buildString {
            append("VERSION_NAME=").append(versionName.trim()).append('\n')
            append("VERSION_CODE=").append(versionCode).append('\n')
        },
        UTF_8,
    )
}

val requestedTasks = gradle.startParameter.taskNames
val shouldAutoBumpVersion = requestedTasks.any { t ->
    val x = t.trim()
    x.contains("${project.path}:assemble", ignoreCase = true) ||
        x.contains("${project.path}:bundle", ignoreCase = true) ||
        x.contains("${project.path}:install", ignoreCase = true)
}

val baseProps = readVersionProps()
val baseVersionName = baseProps.getProperty("VERSION_NAME")?.trim()?.ifBlank { "1.0.9" } ?: "1.0.9"
val baseVersionCode = baseProps.getProperty("VERSION_CODE")?.trim()?.toIntOrNull() ?: 10
val resolvedVersionCode = if (shouldAutoBumpVersion) (baseVersionCode + 1) else baseVersionCode
val resolvedVersionName = baseVersionName
if (shouldAutoBumpVersion) {
    writeVersionProps(resolvedVersionName, resolvedVersionCode)
}

android {
    namespace = "com.mupa.agent.argos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mupa.agent.argos"
        minSdk = 24
        targetSdk = 34

        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { localProps.load(it) }
        }

        fun getConfig(name: String): String? {
            return providers.gradleProperty(name).orNull
                ?: localProps.getProperty(name)
                ?: System.getenv(name)
        }

        fun extractJsonValue(text: String, key: String): String {
            val r = Regex("\"${key}\"\\s*:\\s*\"([^\"]+)\"")
            return r.find(text)?.groupValues?.getOrNull(1).orEmpty()
        }

        fun extractNested(text: String, keyPath: List<String>): String {
            var cursor = text
            for (k in keyPath.dropLast(1)) {
                val start = cursor.indexOf("\"$k\"")
                if (start < 0) return ""
                cursor = cursor.substring(start)
            }
            return extractJsonValue(cursor, keyPath.last())
        }

        val firebaseDbUrl = (getConfig("FIREBASE_DATABASE_URL") ?: "https://argos-3b428-default-rtdb.firebaseio.com").trim()
        val firebaseProjectId = (getConfig("FIREBASE_PROJECT_ID") ?: "argos-3b428").trim()
        val firebaseAppId = (getConfig("FIREBASE_APP_ID") ?: "1:827552493755:android:1c0f9b26e7600ffba2b99e").trim()
        val firebaseApiKey = (getConfig("FIREBASE_API_KEY") ?: "AIzaSyBRNCqzGbKoBivdGpD-3uHS_Uzm5f9ga40").trim()

        fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

        buildConfigField("String", "FIREBASE_DATABASE_URL", "\"${firebaseDbUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProjectId.escapeForBuildConfig()}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseAppId.escapeForBuildConfig()}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseApiKey.escapeForBuildConfig()}\"")

        val supabaseUrl = (getConfig("SUPABASE_URL") ?: "https://puabavrrosduefyyosmn.supabase.co").trim().trimEnd('/')
        val supabaseAnonKey = (getConfig("SUPABASE_ANON_KEY")
            ?: getConfig("SUPABASE_TOKEN")
            ?: "sb_publishable_JRyiJ9BZ5kmRF9EB7WToPA_gsPMNGUa").trim()
        val supabaseCompanyKey = (getConfig("SUPABASE_COMPANY_KEY") ?: getConfig("SUPABASE_TOKEN") ?: supabaseAnonKey).trim()

        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "SUPABASE_COMPANY_KEY", "\"${supabaseCompanyKey.escapeForBuildConfig()}\"")

        // ARGOS web base URL (hosts /api/groups/enroll). Override via getConfig("ARGOS_WEB_URL")
        // in local.properties / env. Change the default below to your deployed domain.
        val argosWebUrl = (getConfig("ARGOS_WEB_URL") ?: "https://argos.mupa.app").trim().trimEnd('/')
        buildConfigField("String", "ARGOS_WEB_URL", "\"${argosWebUrl.escapeForBuildConfig()}\"")

        // Default (non-legacy) build: resolve the device serial the normal way
        // (Build.getSerial()/HardwareIdResolver chain). Overridden to true in the "legacy"
        // flavor — see flavorDimensions below.
        buildConfigField("Boolean", "USE_MAC_AS_SERIAL", "false")
        buildConfigField("Boolean", "MEFERI_DEVICE", "false")
    }

    flavorDimensions += "serial"
    productFlavors {
        create("standard") {
            dimension = "serial"
        }
        create("legacy") {
            dimension = "serial"
            // Same minSdk as the standard flavor (24, already covers Android 9 and below) —
            // this flavor exists purely to flip USE_MAC_AS_SERIAL, not to lower minSdk.
            // Build.getSerial()/READ_PHONE_STATE-based serial resolution is unreliable on
            // some older/cheap Android 9 collectors; their WiFi MAC address is a more
            // consistent identifier on those specific devices. MAC-address randomization
            // restrictions (Android 10+) make this approach unreliable on newer Android, so
            // this stays a separate opt-in flavor instead of replacing the default behavior.
            buildConfigField("Boolean", "USE_MAC_AS_SERIAL", "true")
            versionNameSuffix = "-legacy"
        }
        create("mc45") {
            dimension = "serial"
            minSdk = 34
            versionNameSuffix = "-mc45"
            buildConfigField("Boolean", "MEFERI_DEVICE", "true")
        }
        create("samsung_a36") {
            dimension = "serial"
            minSdk = 28
            versionNameSuffix = "-a36"
        }
    }

    signingConfigs {
        create("release") {
            fun requireProp(name: String): String {
                return (providers.gradleProperty(name).orNull
                    ?: rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { input ->
                        Properties().apply { load(input) }.getProperty(name)
                    }
                    ?: System.getenv(name)
                    ?: "").trim().ifBlank {
                    throw GradleException(
                        "Release signing not configured. Missing $name. " +
                            "Set it in local.properties or as Gradle property/environment variable.",
                    )
                }
            }

            val storeFilePath = (providers.gradleProperty("RELEASE_STORE_FILE").orNull
                ?: rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { input ->
                    Properties().apply { load(input) }.getProperty("RELEASE_STORE_FILE")
                }
                ?: System.getenv("RELEASE_STORE_FILE")
                ?: "").trim()
            if (storeFilePath.isBlank()) return@create

            storeFile = file(storeFilePath)
            if (!storeFile!!.exists()) {
                throw GradleException("Release keystore not found at: $storeFilePath")
            }
            storePassword = requireProp("RELEASE_STORE_PASSWORD")
            keyAlias = requireProp("RELEASE_KEY_ALIAS")
            keyPassword = requireProp("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseStoreFilePath = (providers.gradleProperty("RELEASE_STORE_FILE").orNull
                ?: rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { input ->
                    Properties().apply { load(input) }.getProperty("RELEASE_STORE_FILE")
                }
                ?: System.getenv("RELEASE_STORE_FILE")
                ?: "").trim()
            if (releaseStoreFilePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        viewBinding = false
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // TLS 1.3 on old devices (Android 9 X96 boxes only ship TLS 1.2, and the
    // ARGOS web edge — Lovable/Cloudflare — requires TLS 1.3 minimum).
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-svg:2.6.0")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    // QR / barcode scanning (ZXing — works offline, no Google Play Services required)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    implementation("dev.chrisbanes.haze:haze-jetpack-compose:0.7.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-storage")

    // Meferi MC45 OEM SDK — used to pre-authorize MediaProjection + grant permissions
    compileOnly(files("libs/meferi_pda_sdk_26042717.jar"))
}
