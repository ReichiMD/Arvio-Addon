import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "ReichiMD/Arvio-Addon")
    }

    android {
        namespace = "com.reichi.arvioaddon"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Compile against ARVIO's R8-obfuscated cloudstream3 classes (extracted from the
        // sideload APK via dex2jar). ARVIO's R8 renames kotlin.coroutines.Continuation→j7.d
        // and kotlin.jvm.functions.Function1→x7.l. Plugins compiled against the public
        // unobfuscated stub have mismatched suspend-method descriptors → virtual dispatch
        // always calls the parent instead of the override. Compiling against the obfuscated
        // JAR makes the Kotlin compiler read @kotlin.Metadata with obfuscated JVM signatures
        // and generate matching overrides.
        //
        // The JAR is gitignored (10MB). To obtain it:
        //   1. Download ARVIO-v1.9.983-sideload-release.apk
        //   2. Extract all classes*.dex
        //   3. d2j-dex2jar.sh classes*.dex → classes*.jar
        //   4. Combine: keep com/lagradost/cloudstream3/** + obfuscated packages (j7, x7, etc.)
        //   5. Place at libs/arvio-cloudstream3-v1.9.983.jar
        // CI does this automatically (see .github/workflows/build.yml).
        val arvioJar = rootProject.file("libs/arvio-cloudstream3-v1.9.983.jar")
        if (arvioJar.exists()) {
            cloudstream(files(arvioJar))
        } else {
            // Fallback for environments without the obfuscated JAR (produces unobfuscated DEX
            // that won't work in ARVIO but allows compilation to succeed)
            cloudstream("com.lagradost:cloudstream3:pre-release")
        }

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.21.2")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    }

    // ARVIO's R8-shrunk release APK strips kotlin-stdlib classes (FilesKt, EnumEntriesKt,
    // Channels, SetsKt, ...) from its parent classloader, so .cs3 plugins compiled against
    // the stdlib crash with NoClassDefFoundError at plugin.load(). The cloudstream3 gradle
    // plugin only compiles the plugin's own classes into the .cs3 DEX (compileDex.input comes
    // from compileDebugKotlin.destinationDirectory), assuming the host app provides the full
    // stdlib — which the real cloudstream3 app does, but ARVIO does not.
    //
    // Fix: bundle kotlin-stdlib (+ coroutines, which the provider uses) into the .cs3 DEX by
    // extracting their .class files into a directory and adding it to the compileDex task
    // input. The DexClassLoader then finds stdlib classes within the plugin's own DEX,
    // independent of the parent classloader.
    val bundleStdlib by configurations.creating
    dependencies.add(bundleStdlib.name, "org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    dependencies.add(bundleStdlib.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    val extractedStdlibDir = layout.buildDirectory.dir("intermediates/stdlib-classes/${project.name}")

    afterEvaluate {
        val compileDexTask = tasks.getByName("compileDex") as CompileDexTask
        // Extract kotlin-stdlib + coroutines .class files and feed them to the DEX compiler so
        // they are compiled INTO the .cs3 (ARVIO's R8-shrunk parent classloader lacks them).
        val extractStdlib = tasks.create("extractStdlibForDex")
        extractStdlib.doLast {
            val outDir = extractedStdlibDir.get().asFile
            outDir.deleteRecursively()
            bundleStdlib.files.forEach { jar ->
                java.util.zip.ZipFile(jar).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val ze = entries.nextElement()
                        if (ze.isDirectory) continue
                        val name = ze.name
                        if (!(name.startsWith("kotlin/") || name.startsWith("kotlinx/") || name.endsWith(".kotlin_module"))) continue
                        if (name.endsWith(".kotlin_builtins")) continue
                        val target = java.io.File(outDir, name)
                        target.parentFile.mkdirs()
                        zf.getInputStream(ze).use { input -> java.io.FileOutputStream(target).use { out -> input.copyTo(out) } }
                    }
                }
            }
        }
        extractStdlib.dependsOn(bundleStdlib)
        compileDexTask.input.from(extractedStdlibDir)
        compileDexTask.dependsOn(extractStdlib)

        // After the .cs3 is built, patch the DEX to replace kotlin type descriptors with
        // ARVIO's R8-obfuscated equivalents (kotlin.coroutines.Continuation→j7.d, etc.).
        // This makes suspend-function override method signatures match ARVIO's runtime so
        // virtual dispatch calls our overrides instead of the parent.
        val makeTask = tasks.findByName("make") ?: tasks.findByName("makeCloudstreamPlugin")
        makeTask?.doLast {
            val cs3File = layout.buildDirectory.file("${project.name}.cs3").get().asFile
            if (cs3File.exists()) {
                val patchScript = rootProject.file("scripts/patch_dex_obfuscation.py")
                if (patchScript.exists()) {
                    logger.lifecycle("Patching DEX obfuscation in ${cs3File.name}...")
                    project.exec {
                        commandLine("python3", patchScript.absolutePath, cs3File.absolutePath)
                    }
                }
            }
        }
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
