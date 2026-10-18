package vegabobo.dsusideloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Dialog(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String = "",
    text: String = "",
    confirmText: String = "",
    cancelText: String = "",
    onClickConfirm: () -> Unit = {},
    onClickCancel: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    AlertDialog(
        modifier = modifier,
        icon = { Icon(icon, null) },
        title = if (title.isEmpty()) null else { { Text(title) } },
        onDismissRequest = onClickCancel,
        confirmButton = {
            if (confirmText.isNotEmpty()) TextButton(onClickConfirm, shapes = ButtonDefaults.shapes()) { Text(confirmText) }
        },
        dismissButton = {
            if (cancelText.isNotEmpty()) TextButton(onClickCancel, shapes = ButtonDefaults.shapes()) { Text(cancelText) }
        },
        text = { Column { if (text.isNotEmpty()) Text(text); content() } },
    )
}
