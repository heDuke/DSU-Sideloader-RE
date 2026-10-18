package vegabobo.dsusideloader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CardTitle(modifier: Modifier = Modifier, cardTitle: String) {
    Text(
        modifier = modifier,
        text = cardTitle,
        style = MaterialTheme.typography.titleLarge,
    )
}
