package vegabobo.dsusideloader.ui.components.buttons

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
        TextButton(onClick = onClick, enabled = isEnabled, modifier = modifier.heightIn(min = 48.dp)) {
            Text(text = text)
        }
    } else {
        Button(
            shapes = ButtonDefaults.shapes(),
            modifier = modifier.heightIn(min = 52.dp),
            onClick = onClick,
            enabled = isEnabled,
            colors =
            if (colorButton != null) {
                ButtonDefaults.buttonColors(colorButton)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                text = text,
                color = colorText ?: MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
            content()
        }
    }
}
