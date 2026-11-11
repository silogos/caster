package com.zerofriction.localcast.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.CustomProfileRules

/**
 * A profile's name: one line, at most [CustomProfileRules.MAX_NAME_LENGTH]
 * characters (typing past it is ignored, and the counter says where it
 * stands). An empty name is shown as an error; the caller decides what a
 * valid one does.
 */
@Composable
internal fun ProfileNameField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val valid = CustomProfileRules.cleanName(value) != null
    OutlinedTextField(
        value = value,
        onValueChange = { typed ->
            onValueChange(typed.replace("\n", " ").take(CustomProfileRules.MAX_NAME_LENGTH))
        },
        label = { Text(stringResource(R.string.profile_name)) },
        singleLine = true,
        isError = !valid,
        supportingText = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(if (valid) "" else stringResource(R.string.profile_name_empty))
                Text(
                    text = stringResource(
                        R.string.profile_name_counter,
                        value.length,
                        CustomProfileRules.MAX_NAME_LENGTH,
                    ),
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
