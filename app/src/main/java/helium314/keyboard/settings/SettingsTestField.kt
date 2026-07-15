// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R

/**
 * A scratch text field embedded in the settings top bar so you can try the keyboard (and its
 * suggestions/completions) without leaving the settings you are editing. The settings *search*
 * field sets NO_SUGGESTIONS and so suppresses the whole suggestion pipeline; this field deliberately
 * does not, so what you type here behaves like a normal text field.
 *
 * Its content and visibility are hoisted into [state] — a process-scoped holder — so they persist as
 * you navigate between settings screens (each screen re-creates the [SearchScreen] scaffold, which
 * would otherwise reset a locally-remembered field). A Clear button empties it on demand.
 */
object SettingsTestFieldState {
    var visible by mutableStateOf(false)
    var text by mutableStateOf(TextFieldValue())
}

@Composable
fun SettingsTestFieldToggle() {
    IconButton(onClick = { SettingsTestFieldState.visible = !SettingsTestFieldState.visible }) {
        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.test_field_toggle))
    }
}

@Composable
fun SettingsTestField(
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface
    ),
) {
    AnimatedVisibility(visible = SettingsTestFieldState.visible, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = SettingsTestFieldState.text,
            onValueChange = { SettingsTestFieldState.text = it },
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.test_field_placeholder)) },
            trailingIcon = {
                IconButton(onClick = {
                    if (SettingsTestFieldState.text.text.isEmpty()) SettingsTestFieldState.visible = false
                    else SettingsTestFieldState.text = TextFieldValue()
                }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.test_field_clear))
                }
            },
            colors = colors,
        )
    }
}
