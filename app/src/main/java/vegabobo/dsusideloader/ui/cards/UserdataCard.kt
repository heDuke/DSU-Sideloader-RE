package vegabobo.dsusideloader.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.ui.components.CardBox
import vegabobo.dsusideloader.ui.components.FileSelectionBox
import vegabobo.dsusideloader.ui.screen.home.UserDataCardState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UserdataCard(
    isEnabled: Boolean,
    uiState: UserDataCardState,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    CardBox(
        modifier = modifier,
        cardTitle = stringResource(id = R.string.userdata_size),
        addToggle = true,
        isToggleEnabled = !isEnabled,
        isToggleChecked = uiState.isSelected,
        onCheckedChange = onCheckedChange,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4, 8, 16).forEach { gib ->
                FilterChip(
                    selected = if (uiState.text.isEmpty()) gib == 2 else uiState.text == "${gib}GB",
                    enabled = !isEnabled,
                    onClick = {
                        if (!uiState.isSelected) onCheckedChange(true)
                        onValueChange(gib.toString())
                    },
                    label = { Text(stringResource(R.string.re_gib, gib)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = uiState.isSelected,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                FileSelectionBox(
                    modifier = Modifier.padding(bottom = 4.dp),
                    isEnabled = !isEnabled,
                    isError = uiState.isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textFieldValue = uiState.text,
                    textFieldTitle = stringResource(id = R.string.userdata_size_info),
                    onValueChange = onValueChange,
                )
                AnimatedVisibility(visible = uiState.isError) {
                    Text(
                        modifier = Modifier.padding(start = 1.dp),
                        text = stringResource(
                            id = R.string.allowed_userdata_allocation,
                            uiState.maximumAllowed,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 14.sp,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
