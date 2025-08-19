plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.droidrun.portal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.droidrun.portal"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
        implementation("androidx.activity:activity-ktx:1.9.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}

// Gradle task to install and then launch the app on a connected device via ADB
tasks.register("installAndRunDebug") {
    dependsOn("installDebug")
    doLast {
        val serial = (findProperty("deviceSerial") as String?)
            ?: (findProperty("android.injected.device.serial") as String?)
        val component = "com.droidrun.portal/.MainActivity"
        val serviceComponent = "com.droidrun.portal/com.droidrun.portal.DroidrunAccessibilityService"

        val adbBase: List<String> = if (serial != null) listOf("adb", "-s", serial) else listOf("adb")

        fun runAdb(vararg args: String) {
            exec { commandLine(adbBase + args) }
        }

        // Disable Accessibility to apply clean state, then enable our service
        runAdb("shell", "settings", "put", "secure", "accessibility_enabled", "0")
        Thread.sleep(500)
        runAdb("shell", "settings", "put", "secure", "enabled_accessibility_services", serviceComponent)
        runAdb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        // Launch app after enabling service
        runAdb("shell", "am", "start", "-n", component)
    }
}