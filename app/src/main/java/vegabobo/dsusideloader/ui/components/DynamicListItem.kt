package vegabobo.dsusideloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable

@Composable
fun DynamicListItem(listLength: Int, currentValue: Int, content: @Composable () -> Unit) {
    Column {
        content()
        if (currentValue < listLength) HorizontalDivider()
    }
}
