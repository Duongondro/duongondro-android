package app.duongondro.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrfCheckTest {
    private fun first(options: String) = Json.parseToJsonElement(options).jsonObject["extensions"]!!.jsonObject["prf"]!!
        .jsonObject["eval"]!!.jsonObject["first"]!!.jsonPrimitive.content

    @Test
    fun theInputIsTheLabelOfCryptoMd() {
        // "duongondro/v1/prf", base64url without padding: the prfInput vector's 17 bytes.
        assertEquals("ZHVvbmdvbmRyby92MS9wcmY", first(PrfCheck.assertionOptions(ByteArray(32))))
        assertEquals("ZHVvbmdvbmRyby92MS9wcmY", first(PrfCheck.creationOptions(ByteArray(32), ByteArray(16), "PRF check")))
        val rp = Json.parseToJsonElement(PrfCheck.assertionOptions(ByteArray(32))).jsonObject["rpId"]!!.jsonPrimitive.content
        assertEquals("duongondro.app", rp)
    }

    @Test
    fun readsTheResultAsPlatformsSendIt() {
        val out = ByteArray(32) { it.toByte() }
        val b64 = java.util.Base64.getUrlEncoder().encodeToString(out) // padded, as some platforms send it
        val r = PrfCheck.read("""{"id":"abc","rawId":"abc","type":"public-key","response":{},
            "clientExtensionResults":{"credProps":{"rk":true},"prf":{"enabled":true,"results":{"first":"$b64"}}}}""")
        assertEquals("abc", r.credentialId)
        assertEquals(true, r.enabled)
        assertArrayEquals(out, r.first)
        assertEquals(listOf("credProps", "prf"), r.extensions)
        assertEquals(12, PrfCheck.fingerprint(out).length)
    }

    @Test
    fun noPrfIsNotAnError() {
        val r = PrfCheck.read("""{"id":"abc","response":{},"clientExtensionResults":{}}""")
        assertNull(r.enabled)
        assertNull(r.first)
        assertTrue(r.extensions.isEmpty())
        assertNull(PrfCheck.read("""{"id":"abc","response":{}}""").first)
    }
}
