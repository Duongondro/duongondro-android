package app.duongondro.ui.practice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.duongondro.R
import app.duongondro.data.Covers
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme

/**
 * The cover at the top of a practice: the person's own photo if they chose
 * one, else the built-in thangka. It is cropped from the top, so a figure's
 * head is not cut off, runs up under the status bar, and sits back a little
 * in the dark theme. A photo never leaves the phone.
 */
@Composable
internal fun CoverHeader(practiceId: String, own: ImageBitmap?, builtIn: Int?, changed: () -> Unit) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    val pick = rememberCoverPicker(practiceId, changed)
    val height = Size.cover + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        val modifier = Modifier.fillMaxWidth().height(height).alpha(Theme.colors.coverAlpha)
        if (own != null) {
            Image(own, null, modifier, alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
        } else if (builtIn != null) {
            Image(painterResource(builtIn), null, modifier, alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
        }
        IconButton(
            onClick = { choosing = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.m).size(Size.minTap).background(Theme.colors.coverButton, CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_photo), contentDescription = stringResource(R.string.cover_change), tint = Theme.colors.ink)
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.cover_title)) },
            text = { Text(stringResource(R.string.cover_note)) },
            confirmButton = { TextButton(onClick = { choosing = false; pick() }) { Text(stringResource(R.string.cover_choose)) } },
            dismissButton = {
                if (own != null) TextButton(onClick = {
                    choosing = false
                    Covers.remove(context, practiceId)
                    changed()
                }) { Text(stringResource(R.string.cover_use_default), color = Theme.colors.destructive) }
            },
        )
    }
}

/** For practices without a cover: a quiet row offering one. */
@Composable
internal fun AddCoverButton(practiceId: String, changed: () -> Unit) {
    val pick = rememberCoverPicker(practiceId, changed)
    TextButton(onClick = pick) {
        Icon(painterResource(R.drawable.ic_photo), contentDescription = null, tint = Theme.colors.accent, modifier = Modifier.size(Space.xl))
        Text(stringResource(R.string.cover_add), color = Theme.colors.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = Space.s))
    }
}

/** The Android Photo Picker: no storage permission, and the app only ever sees the one photo chosen. */
@Composable
private fun rememberCoverPicker(practiceId: String, changed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && Covers.save(context, uri, practiceId)) changed()
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
