package vegabobo.dsusideloader.ui.components.buttons

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Only binds business actions; dimensions, type, states and morphing use native defaults. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit,
    colorButton: Color? = null,
    colorText: Color? = null,
    textButton: Boolean = false,
    isEnabled: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    if (textButton) {
        TextButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = isEnabled,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColors(contentColor = colorText ?: Color.Unspecified),
        ) { Text(text); content() }
    } else {
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = isEnabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = colorButton ?: Color.Unspecified, contentColor = colorText ?: Color.Unspecified),
        ) { Text(text); content() }
    }
}
