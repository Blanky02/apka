plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.blanky.vinyl"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.blanky.vinyl"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

// Widoczność testów w CI: drukuj każdy test i twardo pilnuj, że testy realnie
// się wykonały. Dzięki temu ZIELONY build sam w sobie dowodzi, że testy
// przeszły i że było ich więcej niż zero (nie wymaga edycji workflowa).
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
    doLast {
        val dir = layout.buildDirectory.dir("test-results/$name").get().asFile
        if (!dir.isDirectory) error("Brak wyników testów w $dir — task $name nie wykonał testów")
        var total = 0
        var bad = 0
        dir.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { f ->
            val head = f.readText().take(4000)
            total += Regex("""tests="(\d+)"""").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            bad += (Regex("""failures="(\d+)"""").find(head)?.groupValues?.get(1)?.toInt() ?: 0) +
                (Regex("""errors="(\d+)"""").find(head)?.groupValues?.get(1)?.toInt() ?: 0)
        }
        println("TEST SUMMARY [$name]: total=$total failures+errors=$bad")
        if (total == 0) error("Zero wykonanych testów w tasku $name")
        if (bad > 0) error("$bad test(ów) z porażką w tasku $name")
    }
}
