plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin, no Android: practices, day keys, sessions, rounds, streaks.
// Tested on the JVM with `./gradlew :core:test`, against the shared cases.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
