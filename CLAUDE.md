# duongondro-android

Kotlin + Jetpack Compose + Material 3 app for Duongöndro, minSdk 28. The design lives in `Duongondro/duongondro-design` (read its `README.md` and `CLAUDE.md` first, then `docs/16-implementation-plan.md`); phase 6 is tracked in duongondro-design#6.

- **Build and test locally on the Mac, not on GitHub CI.** `make core-test`, then `make build`; `make install` puts the debug APK on the running emulator. Use the one emulator already set up (`Medium_Phone`); never create more AVDs or download system images.
- **The Makefile uses Android Studio's bundled JDK** (`~/Applications/Android Studio.app`); the Gradle wrapper pins Gradle, `gradle/libs.versions.toml` pins everything else.
- **Logic lives in `core/`**, a pure Kotlin JVM module tested with `./gradlew :core:test`, mirroring `DuongondroCore` on iOS. `core/src/test/resources/streak-cases.json` (and later `vectors.json`) are copies of `duongondro-api/testdata/`; refresh them from there, never edit them here.
- **Day keys** are `java.time.LocalDate` built from an instant in an explicit `ZoneId` (`civilDate`), never a formatter with `YYYY`.
- **Theme tokens only** (`ui/theme/`): colours and the small shape scale (4 dp small components, 6 dp buttons, cards and the FAB), no literals in composables. Light and dark designed together.
- **Never log from Today.** Counts are logged only on a practice's screen, through `PendingLog` (5-second undo window, written only when it closes, no source recorded).
- **Release builds refuse a dirty tree**; debug builds show `<hash>-dirty` in Settings.
- **Practice names:** Tibetan/Sanskrit first (Dorje Sempa, Chenrezig, Amitabha), English as the second line.
- Commits end with the attribution trailers the session asks for.
- **Accounts against the local server:** debug builds talk to `http://10.0.2.2:8080` (`make serve` in duongondro-api; magic-link codes are printed to its log; another URL with `-Pduongondro.apiUrl=…`). Passkeys cannot be exercised there: the DEV server's relying party is `localhost`, and Credential Manager needs `duongondro.app` with its assetlinks.json and the APK's `android:apk-key-hash:` origin in the server's `RP_ORIGINS`. Use "Not now" with an email on the emulator; try passkeys on a phone against production. `DUONGONDRO_API_URL=http://127.0.0.1:8080 make core-test` also runs `LiveServerTest` against it.
- **Release signing:** `make apk` builds the sideloadable release APK. The key never enters the repository: `~/.config/duongondro/android-release.properties` (or `-Pduongondro.signing=<file>`) names `storeFile` and `keyAlias`, and the passwords come from `DUONGONDRO_STORE_PASSWORD` / `DUONGONDRO_KEY_PASSWORD`. Without them the APK is unsigned. Its certificate's SHA-256 is what production's `ANDROID_CERT_SHA256` and the `android:apk-key-hash:` passkey origin are made from; losing the key means no updates for anyone who installed the app.
