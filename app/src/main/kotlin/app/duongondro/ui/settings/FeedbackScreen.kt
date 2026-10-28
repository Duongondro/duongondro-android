package app.duongondro.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import app.duongondro.BuildConfig
import app.duongondro.R
import app.duongondro.ui.CardSection
import app.duongondro.ui.ListRow
import app.duongondro.ui.RowDivider
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme

private const val ISSUES = "https://github.com/Duongondro/duongondro-android/issues"

/**
 * Settings › About › Feedback (design: Settings › Feedback): an email to
 * [FEEDBACK_ADDRESS] that starts with what is running, never counts or
 * practices, or a GitHub issue.
 */
@Composable
fun FeedbackScreen(back: () -> Unit) {
    val context = LocalContext.current
    val language = appLanguage(context)
    val revision = BuildConfig.GIT_REVISION.let { if (it == "unknown") it else it.take(7) } + if (BuildConfig.GIT_DIRTY) "-dirty" else ""
    val header = listOf(
        "Duongöndro ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · $revision",
        "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",
        "Language: ${nativeName(language)}",
    ).joinToString("\n")
    Page(stringResource(R.string.feedback), back) {
        CardSection {
            ListRow(stringResource(R.string.write_an_email), detail = null, chevron = true, onClick = {
                val uri = ("mailto:$FEEDBACK_ADDRESS?subject=" + Uri.encode("Duongöndro") + "&body=" + Uri.encode(header + "\n\n")).toUri()
                // Also as extras: some mail apps ignore subject and body in the mailto: link.
                context.openUrl(Intent(Intent.ACTION_SENDTO, uri).putExtra(Intent.EXTRA_SUBJECT, "Duongöndro").putExtra(Intent.EXTRA_TEXT, header + "\n\n"))
            })
            Text(FEEDBACK_ADDRESS, style = Theme.type.secondary, color = Theme.colors.muted,
                modifier = Modifier.padding(start = Space.l, end = Space.l, bottom = Space.s))
            RowDivider()
            ListRow(stringResource(R.string.report_on_github), chevron = true, onClick = { context.openUrl(Intent(Intent.ACTION_VIEW, ISSUES.toUri())) })
            Text(stringResource(R.string.github_needs_account), style = Theme.type.secondary, color = Theme.colors.muted,
                modifier = Modifier.padding(start = Space.l, end = Space.l, bottom = Space.s))
        }
        CardSection(stringResource(R.string.email_starts_with), stringResource(R.string.email_starts_with_footer)) {
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                Text(header, style = Theme.type.mono, color = Theme.colors.soft)
            }
        }
    }
}

private typealias Uri = android.net.Uri
