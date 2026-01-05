import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import gobley.gradle.GobleyHost
import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.jvm
import gobley.gradle.cargo.tasks.CargoBuildTask
import gobley.gradle.cargo.tasks.FindDynamicLibrariesTask
import gobley.gradle.cargo.tasks.RustUpTargetAddTask
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

fun rustLibraryName(triple: String): String = when {
    triple.contains("windows") -> "composewebview_wry.dll"
    triple.contains("darwin") || triple.contains("apple") -> "libcomposewebview_wry.dylib"
    else -> "libcomposewebview_wry.so"
}

fun Project.prebuiltRustLibrary(triple: String): File =
    layout.projectDirectory.dir("target/$triple/release").file(rustLibraryName(triple)).asFile

data class NativeResource(val triple: String, val resourcePrefix: String)

val nativeResources = listOf(
    NativeResource("aarch64-apple-darwin", "darwin-aarch64"),
    NativeResource("x86_64-apple-darwin", "darwin-x86-64"),
    NativeResource("x86_64-unknown-linux-gnu", "linux-x86-64"),
    NativeResource("aarch64-unknown-linux-gnu", "linux-aarch64"),
    NativeResource("x86_64-pc-windows-msvc", "win32-x86-64"),
    NativeResource("aarch64-pc-windows-msvc", "win32-aarch64"),
)

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinAtomicfu)
    alias(libs.plugins.gobleyCargo)
    alias(libs.plugins.gobleyRust)
    alias(libs.plugins.gobleyUniffi)
    alias(libs.plugins.mavenPublish)
}

cargo {
    jvmVariant.set(Variant.Release)
    builds.jvm {
        // In CI with pre-built natives, embed if the library file exists
        // Otherwise, only embed for current host platform
        val triple = rustTarget.rustTriple
        val libName = rustLibraryName(triple)
        val prebuiltLib = layout.projectDirectory.dir("target/$triple/release").file(libName)
        embedRustLibrary = prebuiltLib.asFile.exists() || (GobleyHost.current.rustTarget == rustTarget)
    }
}

rust {
    val userHome = System.getProperty("user.home")
    val cargoBin = file("$userHome/.cargo/bin")
    val rustupToolchainBin = file(
        "$userHome/.rustup/toolchains/stable-${GobleyHost.current.rustTarget.rustTriple}/bin",
    )
    when {
        cargoBin.resolve("rustc").exists() -> toolchainDirectory.set(cargoBin)
        rustupToolchainBin.resolve("rustc").exists() -> toolchainDirectory.set(rustupToolchainBin)
    }
}

uniffi {
    generateFromLibrary {
        build.set(GobleyHost.current.rustTarget)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.jna)
    implementation(libs.skiko.awt)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named<ProcessResources>("processResources") {
    nativeResources.forEach { (triple, resourcePrefix) ->
        val prebuiltLib = project.prebuiltRustLibrary(triple)
        if (prebuiltLib.exists()) {
            from(prebuiltLib) {
                into(resourcePrefix)
            }
        }
    }
}

tasks.withType<CargoBuildTask>().configureEach {
    onlyIf {
        val rustTarget = target.orNull ?: return@onlyIf true
        val triple = rustTarget.rustTriple
        val prebuiltLib = project.prebuiltRustLibrary(triple)
        val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
        !prebuiltLib.exists() || isHostTarget
    }
}

tasks.withType<FindDynamicLibrariesTask>().configureEach {
    val rustTarget = rustTarget.orNull ?: return@configureEach
    val triple = rustTarget.rustTriple
    val prebuiltLib = project.prebuiltRustLibrary(triple)
    val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
    if (prebuiltLib.exists() && !isHostTarget) {
        searchPaths.set(listOf(prebuiltLib.parentFile))
    }
}

tasks.withType<RustUpTargetAddTask>().configureEach {
    onlyIf {
        val rustTarget = rustTarget.orNull ?: return@onlyIf true
        val triple = rustTarget.rustTriple
        val prebuiltLib = project.prebuiltRustLibrary(triple)
        val isHostTarget = GobleyHost.current.rustTarget.rustTriple == triple
        !prebuiltLib.exists() || isHostTarget
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
    coordinates(artifactId = "wrywebview")
    pom {
        name.set("WryWebView")
        description.set("Native WebView bindings for JVM using Wry (Rust)")
    }
}

// Publish native runtime JARs as additional artifacts
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            if (name == "maven") {
                // Add native runtime JARs for each platform
                val nativeJars = layout.buildDirectory.dir("libs").get().asFile.listFiles()
                    ?.filter { it.name.startsWith("wrywebview-") && it.name.endsWith(".jar") && !it.name.contains("sources") && !it.name.contains("javadoc") }
                    ?: emptyList()

                nativeJars.forEach { jar ->
                    val classifier = jar.name.removePrefix("wrywebview-").removeSuffix(".jar")
                    artifact(jar) {
                        this.classifier = classifier
                    }
                }
            }
        }
    }
}
