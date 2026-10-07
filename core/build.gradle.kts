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
    // End-to-end encryption (docs/crypto.md in duongondro-api) with BouncyCastle's
    // lightweight API: pure Java, the same classes on the JVM and on Android 28,
    // whose platform lacks Ed25519 until API 33. Tink would need two artifacts
    // (tink for this JVM module, tink-android for the app) and hides nonces.
    implementation(libs.bouncycastle.prov)
    // Statement payloads and sealed sessions are parsed from JSON.
    implementation(libs.kotlinx.serialization.json)
    // The account and sync coordinators suspend around the store; the API client blocks.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

// LiveServerTest runs only against a DEV server named here (`make serve` in duongondro-api).
tasks.test {
    providers.environmentVariable("DUONGONDRO_API_URL").orNull?.let { environment("DUONGONDRO_API_URL", it) }
}
