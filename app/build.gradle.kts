import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Build identity (design: Settings and build identity): the commit this build
// comes from and whether the tree had uncommitted changes, read through
// providers.exec so the configuration cache tracks them.
val gitRevision: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val gitStatus: Provider<String> = providers.exec {
    commandLine("sh", "-c", "git status --porcelain || echo '?? git cannot read this tree'")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

android {
    namespace = "app.duongondro"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.duongondro"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_REVISION", "\"${gitRevision.get()}\"")
        buildConfigField("boolean", "GIT_DIRTY", gitStatus.get().isNotEmpty().toString())
    }

    // The release key lives outside the repository. A properties file names the
    // keystore and alias (default ~/.config/duongondro/android-release.properties,
    // or -Pduongondro.signing=<file>); the passwords come from the environment
    // (DUONGONDRO_STORE_PASSWORD, DUONGONDRO_KEY_PASSWORD), never from a file
    // here. Without them the release APK is built unsigned.
    val signingFile = providers.gradleProperty("duongondro.signing")
        .orElse("${System.getProperty("user.home")}/.config/duongondro/android-release.properties")
        .map { file(it) }.get()
    val storePassword = providers.environmentVariable("DUONGONDRO_STORE_PASSWORD").orNull
    if (signingFile.isFile && storePassword != null) {
        val props = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            this.storePassword = storePassword
            keyAlias = props.getProperty("keyAlias")
            keyPassword = providers.environmentVariable("DUONGONDRO_KEY_PASSWORD").orElse(storePassword).get()
        }
    }

    buildTypes {
        debug {
            // This Mac's development server (`make serve` in duongondro-api), as the
            // emulator reaches it; another with -Pduongondro.apiUrl=http://… or in
            // gradle.properties. Cleartext is allowed in debug builds only
            // (src/debug/res/xml/network_security_config.xml).
            val apiUrl = providers.gradleProperty("duongondro.apiUrl").orElse("http://10.0.2.2:8080").get()
            buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.duongondro.app\"")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Release builds never come from a dirty tree: the commit shown in Settings
// must exist on GitHub.
val checkCleanTree = tasks.register("checkCleanTree") {
    val status = gitStatus
    val revision = gitRevision
    doLast {
        if (revision.get() == "unknown") throw GradleException("refusing a release build: git cannot read this tree")
        if (status.get().isNotEmpty()) throw GradleException("refusing a release build from a dirty tree; commit first:\n${status.get()}")
    }
}
tasks.matching { it.name.matches(Regex("(pre|bundle|assemble)Release.*")) }.configureEach { dependsOn(checkCleanTree) }

/** Contributors.json from git history at build time; the About screen works offline. */
abstract class ContributorsTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    init { outputs.upToDateWhen { false } } // history changes with every commit anywhere

    @TaskAction fun run() {
        val out = outputDir.get().asFile.apply { mkdirs() }.resolve("contributors.json")
        out.outputStream().use { stream ->
            exec.exec {
                commandLine("sh", script.get().asFile.path)
                standardOutput = stream
            }
        }
    }
}

val contributors = tasks.register<ContributorsTask>("contributors") {
    script.set(rootProject.layout.projectDirectory.file("Scripts/contributors.sh"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(contributors, ContributorsTask::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    // Passkeys and the recovery code in Google Password Manager; play-services-auth
    // provides them below Android 14.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
