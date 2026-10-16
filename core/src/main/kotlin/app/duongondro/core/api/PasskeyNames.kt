package app.duongondro.core.api

/**
 * A passkey's default name in Settings › Passkeys (design: Settings ›
 * Passkeys): the password manager that holds it, from the authenticator's
 * AAGUID, or null when the AAGUID is unknown or absent (the app then says
 * "Passkey"). Product names, so never translated.
 */
object PasskeyNames {
    private val known = mapOf(
        "ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4" to "Google Password Manager",
        "fbfc3007-154e-4ecc-8c0b-6e020557d7bd" to "iCloud Keychain",
        "dd4ec289-e01d-41c9-bb89-70fa845d4bf2" to "iCloud Keychain",
        "53414d53-554e-4700-0000-000000000000" to "Samsung Pass",
        "bada5566-a7aa-401f-bd96-45619a55120d" to "1Password",
        "d548826e-79b4-db40-a3d8-11116f7e8349" to "Bitwarden",
        "531126d6-e717-415c-9320-3d9aa6981239" to "Dashlane",
        "fdb141b2-5d84-443e-8a35-4698c205a502" to "KeePassXC",
        "50726f74-6f6e-5061-7373-50726f746f6e" to "Proton Pass",
        "adce0002-35bc-c60a-648b-0b25f1f05503" to "Chrome on Mac",
        "08987058-cadc-4b81-b6e1-30de50dcbe96" to "Windows Hello",
    )

    fun of(aaguid: String?): String? = aaguid?.lowercase()?.let(known::get)
}
