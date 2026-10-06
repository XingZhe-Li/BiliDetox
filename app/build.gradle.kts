plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.xingzheli.bilidetox"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.github.xingzheli.bilidetox"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "SEARCH_DIAGNOSTICS", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SEARCH_DIAGNOSTICS", providers.gradleProperty("searchDiagnostics").orElse("false").get())
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.xposed)
    implementation("com.huaban:jieba-analysis:1.0.2")
}
