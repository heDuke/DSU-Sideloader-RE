package vegabobo.dsusideloader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

/** Native list item with the existing preference callback contract. */
@Composable
fun PreferenceItem(
    title: String,
    description: String = "",
    icon: ImageVector? = null,
    onClick: (Boolean) -> Unit = {},
    isChecked: Boolean = false,
    showToggle: Boolean = false,
    isEnabled: Boolean = true,
) {
    val interaction = if (showToggle) {
        Modifier.toggleable(value = isChecked, enabled = isEnabled, role = Role.Switch, onValueChange = { onClick(isChecked) })
    } else {
        Modifier.clickable(enabled = isEnabled) { onClick(isChecked) }
    }
    ListItem(
        modifier = interaction,
        headlineContent = { Text(title) },
        supportingContent = if (description.isEmpty()) null else { { Text(description) } },
        leadingContent = if (icon == null) null else { { Icon(icon, contentDescription = null) } },
        trailingContent = if (!showToggle) null else { { Switch(checked = isChecked, onCheckedChange = null, enabled = isEnabled) } },
    )
}
