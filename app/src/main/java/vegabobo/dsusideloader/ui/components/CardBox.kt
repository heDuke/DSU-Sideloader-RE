package vegabobo.dsusideloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Business content in the official Card; shape and colors follow Material defaults. */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    cardTitle: String = "",
    addToggle: Boolean = false,
    isToggleChecked: Boolean = false,
    isToggleEnabled: Boolean = true,
    addPadding: Boolean = true,
    cardColor: Color = Color.Unspecified,
    onCheckedChange: ((Boolean) -> Unit) = {},
    content: @Composable (ColumnScope) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (cardColor == Color.Unspecified) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(Modifier.padding(if (addPadding) 16.dp else 0.dp)) {
            if (cardTitle.isNotEmpty()) {
                if (addToggle) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CardTitle(Modifier.weight(1f), cardTitle = cardTitle)
                        Switch(checked = isToggleChecked, onCheckedChange = onCheckedChange, enabled = isToggleEnabled)
                    }
                } else {
                    CardTitle(cardTitle = cardTitle, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            content(this)
        }
    }
}
