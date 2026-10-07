package app.duongondro.account

import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import app.duongondro.core.api.parseCredentialJson
import kotlinx.serialization.json.JsonElement

/**
 * Credential Manager: passkeys for the account, and the recovery code as a
 * password in Google Password Manager. The relying party is duongondro.app;
 * the system checks it against the site's assetlinks.json, and the server
 * against the APK's signing-key origin (`android:apk-key-hash:…` in RP_ORIGINS).
 * Every call needs the Activity, which the system sheet attaches to.
 */
class Passkeys(context: Context) {
    private val manager = CredentialManager.create(context.applicationContext)

    /** The authenticator's registration response (WebAuthn JSON), or null when the person backed out. */
    suspend fun create(activity: Context, publicKeyOptions: String): JsonElement? = try {
        val response = manager.createCredential(activity, CreatePublicKeyCredentialRequest(publicKeyOptions)) as CreatePublicKeyCredentialResponse
        parseCredentialJson(response.registrationResponseJson)
    } catch (_: CreateCredentialCancellationException) {
        null
    }

    /** The assertion (WebAuthn JSON) for a discoverable sign-in, or null when there is no passkey or the person backed out. */
    suspend fun get(activity: Context, publicKeyOptions: String): JsonElement? = try {
        val result = manager.getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(publicKeyOptions))))
        (result.credential as? PublicKeyCredential)?.let { parseCredentialJson(it.authenticationResponseJson) }
    } catch (_: GetCredentialCancellationException) {
        null
    } catch (_: NoCredentialException) {
        null
    }

    /** Saves a password (the recovery code) under [id]; false when declined. */
    suspend fun savePassword(activity: Context, id: String, password: String): Boolean = try {
        manager.createCredential(activity, CreatePasswordRequest(id, password))
        true
    } catch (_: CreateCredentialCancellationException) {
        false
    }
}
