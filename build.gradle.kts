import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
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

        cloudstream("com.lagradost:cloudstream3:pre-release")

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
    val extractStdlib = tasks.register("extractStdlibForDex", Copy::class.java) { extract ->
        extract.dependsOn(bundleStdlib)
        // Resolve the configuration at execution time and unzip each artifact.
        extract.from(provider { bundleStdlib.files.map { zipTree(it) } }) { spec ->
            spec.include("kotlin/**")
            spec.include("kotlinx/**")
            spec.include("META-INF/*.kotlin_module")
            spec.exclude("**/*.kotlin_builtins")
        }
        extract.into(extractedStdlibDir)
    }

    afterEvaluate {
        tasks.named("compileDex", com.lagradost.cloudstream3.gradle.tasks.CompileDexTask::class.java) { task ->
            task.input.from(extractedStdlibDir)
            task.dependsOn(extractStdlib)
        }
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
