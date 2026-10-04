import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        buildConfigField("String", "GIT_REVISION", "\"${gitRevision.get()}\"")
        buildConfigField("boolean", "GIT_DIRTY", gitStatus.get().isNotEmpty().toString())
    }

    buildTypes {
        release {
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
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
