package vegabobo.dsusideloader.ui.cards.installation.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.ui.components.FileSelectionBox
import vegabobo.dsusideloader.ui.components.buttons.PrimaryButton
import vegabobo.dsusideloader.ui.components.buttons.SecondaryButton
import vegabobo.dsusideloader.ui.screen.home.InstallationCardState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotInstallingCardContent(
    textFieldInteraction: MutableInteractionSource,
    uiState: InstallationCardState,
    onClickClear: () -> Unit,
    onClickSelectFile: () -> Unit,
    onClickInstall: () -> Unit,
) {
    FileSelectionBox(
        textFieldInteraction = textFieldInteraction,
        isEnabled = true,
        isError = uiState.isError,
        isReadOnly = true,
        textFieldValue = uiState.text,
        textFieldTitle = stringResource(id = R.string.select_gsi_info),
    )
    Spacer(modifier = Modifier.padding(top = 10.dp))
    AnimatedVisibility(visible = uiState.isError) {
        Text(
            text = uiState.errorText.ifEmpty { stringResource(id = R.string.file_unsupported) },
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(text = stringResource(R.string.re_add_image), onClick = onClickSelectFile, isEnabled = uiState.isTextFieldEnabled || uiState.isInstallable)
        if (uiState.isInstallable) {
            SecondaryButton(
                text = stringResource(R.string.clear),
                onClick = onClickClear,
            )
        }
        PrimaryButton(
            text = stringResource(R.string.install),
            onClick = onClickInstall,
            isEnabled = uiState.isInstallable,
        )
    }
}
