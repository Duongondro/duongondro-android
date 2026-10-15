package app.duongondro.core.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Open sign-up (duongondro-api#16): what a sign-up sends, and how its refusals arrive. */
class SignUpRequestTest {
    private class Recorder(var status: Int = 204, var answer: String = "") : Http {
        var path = ""
        var body: JsonObject? = null

        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Response {
            path = "$method ${url.removePrefix("http://server/")}"
            this.body = body?.let { Api.json.parseToJsonElement(it.decodeToString()).jsonObject }
            return Http.Response(status, answer.toByteArray())
        }
    }

    private val http = Recorder()
    private val api = Api("http://server", null, http)
    private val auth = ByteArray(32) { it.toByte() }
    private val ceremony = """{"sessionId":"0192f3a4-5b6c-7d8e-9f00-112233445566","options":{"publicKey":{}}}"""

    @Test
    fun anOpenMagicLinkSignUpSendsTheFlagAndConsent() {
        api.requestMagicLink("a@example.com", SignUp(consent = true))
        assertEquals("POST api/auth/magic-links", http.path)
        assertEquals(buildJsonObject {
            put("email", "a@example.com")
            put("signUp", true)
            put("consent", true)
        }, http.body)
    }

    @Test
    fun aSignInLinkSendsNoFlag() {
        api.requestMagicLink("a@example.com", null)
        assertEquals(buildJsonObject { put("email", "a@example.com") }, http.body)
    }

    @Test
    fun aSignUpWithAnInvitationKeepsItAlongsideTheFlag() {
        api.requestMagicLink("a@example.com", SignUp(consent = true, SignUpProof.Invite("7K2MQ9XA", auth)))
        assertEquals(buildJsonObject {
            put("email", "a@example.com")
            put("signUp", true)
            put("consent", true)
            put("invite", buildJsonObject {
                put("id", "7K2MQ9XA")
                put("auth", Base64.getEncoder().encodeToString(auth))
            })
        }, http.body)
    }

    @Test
    fun aPasskeySignUpWithAnAdmissionCodeSendsTheFlagTheCodeAndTheProfile() {
        http.status = 200
        http.answer = ceremony
        api.beginPasskeySignUp(SignUp(consent = true, SignUpProof.Admission("H4N8R2CJ6TPW3ZQF")), Profile("Tenzin", "tenzin", "female"))
        assertEquals("POST api/auth/passkeys/sign-up", http.path)
        assertEquals(buildJsonObject {
            put("signUp", true)
            put("consent", true)
            put("admissionCode", "H4N8R2CJ6TPW3ZQF")
            put("username", "tenzin")
            put("displayName", "Tenzin")
            put("gender", "female")
        }, http.body)
    }

    @Test
    fun anOpenPasskeySignUpSendsNoCode() {
        http.status = 200
        http.answer = ceremony
        api.beginPasskeySignUp(SignUp(consent = true), Profile(displayName = "Tenzin"))
        assertEquals(buildJsonObject {
            put("signUp", true)
            put("consent", true)
            put("displayName", "Tenzin")
        }, http.body)
    }

    @Test
    fun withoutConsentTheFlagSaysSoAndTheServerRefuses() {
        http.status = 400
        http.answer = """{"error":"consent is required to create an account"}"""
        try {
            api.requestMagicLink("a@example.com", SignUp(consent = false))
            fail()
        } catch (e: ApiError.Status) {
            assertEquals(400, e.code)
        }
        assertEquals(false, http.body!!["consent"].toString().toBooleanStrict())
    }

    @Test
    fun aClosedServerIsForbidden() {
        http.status = 403
        http.answer = """{"error":"an invitation or an admission code is needed to create an account"}"""
        for (call in listOf<() -> Unit>(
            { api.requestMagicLink("a@example.com", SignUp(consent = true)) },
            { api.beginPasskeySignUp(SignUp(consent = true), Profile()) },
            { api.redeemMagicLinkCode("a@example.com", "ABCD1234") },
        )) {
            try { call(); fail() } catch (_: ApiError.Forbidden) {}
        }
    }

    @Test
    fun theNetworksSignUpLimitIsToldApartFromOtherLimits() {
        http.status = 429
        http.answer = """{"error":"too many new accounts from this network; try again later, or use an invitation"}"""
        try {
            api.beginPasskeySignUp(SignUp(consent = true), Profile())
            fail()
        } catch (e: ApiError.TooManyRequests) {
            assertTrue(e.signUps)
        }
        http.answer = """{"error":"too many sign-up mails requested from this network; try again later, or use an invitation"}"""
        try {
            api.requestMagicLink("a@example.com", SignUp(consent = true))
            fail()
        } catch (e: ApiError.TooManyRequests) {
            assertTrue(e.signUps)
        }
        for (other in listOf("too many sign-in links to this address; try again in a few minutes", "too many requests; try again later", "")) {
            http.answer = if (other.isEmpty()) "" else """{"error":"$other"}"""
            try {
                api.requestMagicLink("a@example.com", SignUp(consent = true))
                fail()
            } catch (e: ApiError.TooManyRequests) {
                assertFalse(other, e.signUps)
            }
        }
    }
}
