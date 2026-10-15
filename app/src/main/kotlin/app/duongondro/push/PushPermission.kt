package app.duongondro.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.duongondro.R
import app.duongondro.account.Gender
import app.duongondro.model.AppModel
import app.duongondro.reminders.rememberNotificationPermission
import app.duongondro.ui.FilledAction
import app.duongondro.ui.GenderedString
import app.duongondro.ui.TextAction
import app.duongondro.ui.card
import app.duongondro.ui.stringResource
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme

/**
 * Asks for notifications for friends' pushes, as iOS's PushModel.ask: at a
 * moment with a reason (the Friends card, "Tell me when they practise", after
 * a poke), never at launch, and the system prompt at most once. Granted, push
 * is turned on for the account. [onResult] follows the answer, for a screen
 * that shows the card.
 */
@Composable
fun rememberPushPermission(model: AppModel, onResult: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launch = rememberNotificationPermission { granted ->
        Push.asked(context)
        if (granted) model.refreshPush()
        onResult()
    }
    return { if (Push.canAsk(context)) launch() }
}

/** The Friends card (iOS's NotificationAsk): why, then the system prompt, or "Not now", remembered. */
@Composable
fun PushAskCard(friend: String, unnamed: Boolean, gender: Gender?, ask: () -> Unit, notNow: () -> Unit) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = Theme.colors.accent)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text(stringResource(R.string.push_ask_title), style = Theme.type.body.copy(fontWeight = FontWeight.Bold), color = Theme.colors.ink)
                Text(if (unnamed) stringResource(GenderedString.PushAskUnnamed, gender) else stringResource(R.string.push_ask_detail, friend), style = Theme.type.secondary, color = Theme.colors.muted)
            }
        }
        FilledAction(stringResource(R.string.push_ask_turn_on), height = Size.secondary, onClick = ask)
        TextAction(stringResource(R.string.not_now), onClick = notNow)
    }
}
