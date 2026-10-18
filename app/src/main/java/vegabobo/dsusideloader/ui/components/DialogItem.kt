package vegabobo.dsusideloader.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun DialogItem(icon: ImageVector, title: String, text: String, textColor: Color = Color.Unspecified) {
    ListItem(
        overlineContent = { Text(title, color = textColor) },
        headlineContent = { Text(text, color = textColor) },
        leadingContent = { Icon(icon, null) },
    )
}
