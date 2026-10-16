package app.duongondro.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PrfTest {
    private fun first(options: String) = Json.parseToJsonElement(options).jsonObject["extensions"]!!.jsonObject["prf"]!!
        .jsonObject["eval"]!!.jsonObject["first"]!!.jsonPrimitive.content

    @Test fun theInputIsTheLabelOfCryptoMd() {
        // "duongondro/v1/prf", base64url without padding: the prfInput vector's 17 bytes.
        assertEquals("ZHVvbmdvbmRyby92MS9wcmY", Prf.input)
        assertEquals(Prf.input, first(Prf.assertionOptions(ByteArray(32))))
    }

    @Test fun theServersOptionsKeepTheirExtensions() {
        val options = Prf.withEval("""{"challenge":"abc","rpId":"duongondro.app","extensions":{"credProps":true}}""")
        val root = Json.parseToJsonElement(options).jsonObject
        assertEquals("abc", root["challenge"]!!.jsonPrimitive.content)
        assertEquals("true", root["extensions"]!!.jsonObject["credProps"]!!.jsonPrimitive.content)
        assertEquals(Prf.input, first(options))
        assertEquals(Prf.input, first(Prf.withEval("""{"challenge":"abc"}""")))
    }

    @Test fun allowCredentialsOnlyWhenAsked() {
        assertNull(Json.parseToJsonElement(Prf.assertionOptions(ByteArray(32))).jsonObject["allowCredentials"])
        val allowed = Json.parseToJsonElement(Prf.assertionOptions(ByteArray(32), listOf(byteArrayOf(1, 2, 3)))).jsonObject["allowCredentials"]!!.jsonArray
        assertEquals("AQID", allowed[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun readsAndStripsTheOutput() {
        val out = ByteArray(32) { it.toByte() }
        val padded = Base64.getUrlEncoder().encodeToString(out)
        val response = Json.parseToJsonElement("""{"id":"AQID","rawId":"AQID","type":"public-key","response":{},
            "clientExtensionResults":{"credProps":{"rk":true},"prf":{"enabled":true,"results":{"first":"$padded"}}}}""")
        val r = Prf.read(response)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), r.credentialId)
        assertEquals(true, r.enabled)
        assertArrayEquals(out, r.output)
        val sent = Prf.stripped(response).toString()
        assertFalse("the PRF output never reaches the server", sent.contains("results") || sent.contains(padded.trimEnd('=')))
        assertTrue(sent.contains("\"enabled\":true") && sent.contains("credProps"))
        assertEquals("AQID", Prf.idText(r.credentialId))
    }

    @Test fun noPrfIsNotAnError() {
        val response = Json.parseToJsonElement("""{"id":"AQID","response":{},"clientExtensionResults":{}}""")
        val r = Prf.read(response)!!
        assertNull(r.enabled)
        assertNull(r.output)
        assertEquals(response, Prf.stripped(response))
        assertNull(Prf.read(Json.parseToJsonElement("""{"response":{}}""")))
    }
}

class PasskeyNamesTest {
    @Test fun namesThePasswordManager() {
        assertEquals("Google Password Manager", PasskeyNames.of("EA9B8D66-4D01-1D21-3CE4-B6B48CB575D4"))
        assertNull(PasskeyNames.of("00000000-0000-0000-0000-000000000001"))
        assertNull(PasskeyNames.of(null))
    }
}
