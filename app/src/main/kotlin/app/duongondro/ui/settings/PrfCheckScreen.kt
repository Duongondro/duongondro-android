package app.duongondro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.duongondro.account.Passkeys
import app.duongondro.core.api.PrfCheck
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.OutlinedAction
import app.duongondro.ui.RowDivider
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * The PRF spike (design: Keys › Passkeys that unlock the counts), reached by a
 * long press on Settings › About › Version, in every build: passkeys of
 * duongondro.app work only in an APK signed with the release key. It runs a
 * passkey ceremony with options made here and a random challenge, sends
 * nothing to the server, stores nothing, and shows only whether a PRF output
 * came back and a short fingerprint of it, never the output. English only: a
 * diagnostic, not part of the app.
 */
@Composable
fun PrfCheckScreen(back: () -> Unit) {
    val activity = LocalContext.current
    val passkeys = remember { Passkeys(activity) }
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> String) {
        busy = true
        scope.launch {
            val line = try {
                block()
            } catch (e: Exception) {
                "failed: ${e::class.simpleName}: ${e.message}"
            }
            lines.add(0, "$label — $line")
            busy = false
        }
    }

    fun describe(result: PrfCheck.Result): String = buildString {
        append("passkey …${result.credentialId.takeLast(6)}; ")
        result.enabled?.let { append("prf.enabled=$it; ") }
        append(result.first?.let { "PRF output, fingerprint ${PrfCheck.fingerprint(it)}" } ?: "no PRF output")
        append("; extensions: ${result.extensions.ifEmpty { listOf("none") }.joinToString()}")
    }

    Page("Passkey PRF check", back) {
        CardSection(footer = "Sign in twice with the same passkey: the two fingerprints must match. " +
            "Nothing is sent to the server or kept on this phone.") {
            Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                FilledAction("Sign in with a passkey", enabled = !busy) {
                    run("sign-in") {
                        val response = passkeys.get(activity, PrfCheck.assertionOptions(random(32)))
                        response?.let { describe(PrfCheck.read(it.toString())) } ?: "no passkey chosen"
                    }
                }
                OutlinedAction("Create a test passkey") {
                    if (!busy) run("creation") {
                        val response = passkeys.create(activity, PrfCheck.creationOptions(random(32), random(16), "PRF check"))
                        response?.let { describe(PrfCheck.read(it.toString())) + ". Delete \"PRF check\" from the password manager afterwards." }
                            ?: "cancelled"
                    }
                }
            }
        }
        if (lines.isNotEmpty()) {
            CardSection("Results, newest first") {
                lines.forEachIndexed { i, line ->
                    if (i > 0) RowDivider()
                    Text(line, style = Theme.type.mono, color = Theme.colors.ink, modifier = Modifier.fillMaxWidth().padding(Space.l))
                }
            }
        }
    }
}

private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
