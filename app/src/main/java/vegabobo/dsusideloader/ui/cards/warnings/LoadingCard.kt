package vegabobo.dsusideloader.ui.cards.warnings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.ui.components.SimpleCard

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GrantingPermissionCard() {
    SimpleCard(
        modifier = Modifier.fillMaxWidth(),
        cardTitle = stringResource(id = R.string.missing_permission),
        text = stringResource(id = R.string.granting_permission),
    ) {
        LoadingIndicator(
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
