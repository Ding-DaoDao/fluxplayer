package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.NextDialog
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

@Composable
fun RenameDialog(
    name: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text = name, selection = TextRange(name.length)))
    }
    val focusRequester = remember { FocusRequester() }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_to)) },
        content = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            DoneButton(
                enabled = textFieldValue.text.isNotBlank(),
                onClick = { onDone(textFieldValue.text) },
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )

    LaunchedEffect(key1 = Unit) {
        // To fix focus requester not initialized error on screen rotation
        delay(200.milliseconds)
        focusRequester.requestFocus()
    }
}
