import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.${name.replace("-", "")}"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val catalogs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    catalogs.findBundle("common").ifPresent { common ->
        compileOnly(common)
        testImplementation(common)
    }
    catalogs.findLibrary("junit").ifPresent { testImplementation(it) }
    catalogs.findLibrary("okhttp-mockwebserver").ifPresent { testImplementation(it) }
}
