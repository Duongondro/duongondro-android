package app.duongondro.core.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * The PRF spike (design: Keys › Passkeys that unlock the counts): does the
 * password manager hand back a PRF output for a passkey of duongondro.app, at
 * creation or only at sign-in, and the same one every time? The options are
 * made on the phone with a random challenge and never go to the server, so
 * the ceremony proves nothing to anyone; only the extension's result matters.
 * The PRF input is the one docs/crypto.md fixes for Passkey unlock.
 */
object PrfCheck {
    const val RP_ID = "duongondro.app"
    const val PRF_INPUT = "duongondro/v1/prf"

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun prfEval() = buildJsonObject {
        putJsonObject("prf") { putJsonObject("eval") { put("first", b64.encodeToString(PRF_INPUT.toByteArray())) } }
    }

    /** Options for a discoverable sign-in with any passkey of duongondro.app, asking for the PRF output. */
    fun assertionOptions(challenge: ByteArray): String = buildJsonObject {
        put("challenge", b64.encodeToString(challenge))
        put("rpId", RP_ID)
        put("userVerification", "preferred")
        put("timeout", 120_000)
        put("extensions", prfEval())
    }.toString()

    /**
     * Options for a throwaway passkey named [name] (it is never registered with
     * the server, so it signs nobody in), asking for PRF at creation.
     */
    fun creationOptions(challenge: ByteArray, userId: ByteArray, name: String): String = buildJsonObject {
        put("challenge", b64.encodeToString(challenge))
        putJsonObject("rp") { put("id", RP_ID); put("name", "Duongöndro") }
        putJsonObject("user") { put("id", b64.encodeToString(userId)); put("name", name); put("displayName", name) }
        put("pubKeyCredParams", buildJsonArray {
            addJsonObject { put("type", "public-key"); put("alg", -7) }
            addJsonObject { put("type", "public-key"); put("alg", -257) }
        })
        putJsonObject("authenticatorSelection") { put("residentKey", "required"); put("userVerification", "preferred") }
        put("attestation", "none")
        put("timeout", 120_000)
        put("extensions", prfEval())
    }.toString()

    /**
     * What came back: the credential's id, `prf.enabled` (creation only; null
     * when absent), the first PRF output (null when absent), and the names of
     * every client extension result, to see what the platform did return.
     */
    data class Result(val credentialId: String, val enabled: Boolean?, val first: ByteArray?, val extensions: List<String>)

    fun read(responseJson: String): Result {
        val root = Api.json.parseToJsonElement(responseJson).jsonObject
        val ext = root["clientExtensionResults"] as? JsonObject ?: JsonObject(emptyMap())
        val prf = ext["prf"] as? JsonObject
        val first = ((prf?.get("results") as? JsonObject)?.get("first") as? JsonPrimitive)?.contentOrNull
        return Result(
            credentialId = (root["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            enabled = (prf?.get("enabled") as? JsonPrimitive)?.booleanOrNull,
            first = first?.let { decode(it) },
            extensions = ext.keys.sorted(),
        )
    }

    /** base64url with or without padding, as platforms differ. */
    private fun decode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

    /**
     * A short fingerprint of a PRF output, to compare two runs on screen
     * without ever showing the output: the first 6 bytes of its SHA-256, hex.
     */
    fun fingerprint(output: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(output).take(6).joinToString("") { "%02x".format(it) }
}
