import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget()
    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "composewebview"
            isStatic = true
        }
        iosTarget.setUpiOSObserver()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutinesAndroid)
        }

        jvmMain.dependencies {
            api(project(":wrywebview"))
            implementation(libs.kotlinx.coroutinesSwing)
        }

        iosMain.dependencies { }
    }
}

android {
    namespace = "io.github.kdroidfilter.webview"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    // Lint is currently unstable with this KMP + AGP setup in CI (UAST disposal crash).
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.matching { it.name.startsWith("lint") }.configureEach {
    enabled = false
}

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.setUpiOSObserver() {
    val path = projectDir.resolve("src/nativeInterop/cinterop/observer")

    binaries.all {
        linkerOpts("-F $path")
        linkerOpts("-ObjC")
    }

    compilations.getByName("main") {
        cinterops.create("observer") {
            compilerOpts("-F $path")
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(sourcesJar = true))
    publishToMavenCentral()
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
    coordinates(artifactId = "composewebview")
    pom {
        name.set("ComposeWebView")
        description.set("Compose Multiplatform WebView library for Desktop, Android and iOS")
    }
}
