package vegabobo.dsusideloader.ui.cards.updater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.BuildConfig
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.ui.components.PreferenceItem
import vegabobo.dsusideloader.ui.components.SimpleCard
import vegabobo.dsusideloader.ui.components.buttons.PrimaryButton
import vegabobo.dsusideloader.ui.components.buttons.SecondaryButton
import vegabobo.dsusideloader.ui.screen.about.UpdateStatus
import vegabobo.dsusideloader.ui.screen.about.UpdaterCardState

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun UpdaterCard(
    uiState: UpdaterCardState,
    isUpdaterAvailable: Boolean,
    onClickImage: () -> Unit,
    onClickCheckUpdates: () -> Unit,
    onClickDownloadUpdate: () -> Unit,
    onClickViewChangelog: () -> Unit,
) {
    fun isCheckingForUpdates(): Boolean =
        uiState.updateStatus == UpdateStatus.CHECKING_FOR_UPDATES

    fun isUpdateFound(): Boolean =
        uiState.updateStatus == UpdateStatus.UPDATE_FOUND

    SimpleCard(
        addPadding = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.version_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) },
                leadingContent = {
                    IconButton(onClick = onClickImage, shapes = IconButtonDefaults.shapes()) {
                        Image(painter = painterResource(R.drawable.app_icon_mini), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(24.dp))
                    }
                },
            )
            if (isCheckingForUpdates()) LoadingIndicator(modifier = Modifier.padding(16.dp))
            if (uiState.isDownloading) LinearWavyProgressIndicator(progress = { uiState.progressBar }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }

        Spacer(modifier = Modifier.padding(4.dp))
        if (isUpdaterAvailable) {
            PreferenceItem(
                title = stringResource(id = R.string.check_updates_title),
                description =
                when (uiState.updateStatus) {
                    UpdateStatus.NO_UPDATE_FOUND ->
                        stringResource(id = R.string.check_updates_text_updated)

                    UpdateStatus.UPDATE_FOUND ->
                        stringResource(R.string.check_updates_text_found, uiState.updateVersion)

                    else ->
                        stringResource(id = R.string.check_updates_text_idle)
                },
                onClick = { onClickCheckUpdates() },
            )
            AnimatedVisibility(visible = isUpdateFound()) {
                FlowRow(
                    modifier = Modifier
                        .padding(all = 12.dp)
                        .padding(end = 4.dp),
                ) {
                    SecondaryButton(
                        text = stringResource(id = R.string.changelog),
                        onClick = { onClickViewChangelog() },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    PrimaryButton(
                        text = stringResource(id = R.string.download),
                        onClick = { onClickDownloadUpdate() },
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.padding(6.dp))
        }
    }
}
