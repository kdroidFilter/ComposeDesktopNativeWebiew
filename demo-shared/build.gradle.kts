plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget()
    jvm()

    val isMacHost = System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true
    if (isMacHost) {
        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "demoShared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)

            implementation(project(":webview-compose"))
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
        }

        if (isMacHost) {
            iosMain.dependencies { }
        }
    }
}

android {
    namespace = "io.github.kdroidfilter.webview.demo.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    // Lint is unstable with this KMP + AGP setup in CI.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.matching { it.name.startsWith("lint") }.configureEach {
    enabled = false
}
