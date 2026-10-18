package vegabobo.dsusideloader.ui.screen.home

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.collectLatest
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.ui.cards.DsuInfoCard
import vegabobo.dsusideloader.ui.cards.ImageSizeCard
import vegabobo.dsusideloader.ui.cards.LogcatCard
import vegabobo.dsusideloader.ui.cards.UserdataCard
import vegabobo.dsusideloader.ui.cards.installation.InstallationCard
import vegabobo.dsusideloader.ui.cards.warnings.GrantingPermissionCard
import vegabobo.dsusideloader.ui.cards.warnings.RequiresLogPermissionCard
import vegabobo.dsusideloader.ui.cards.warnings.SetupStorage
import vegabobo.dsusideloader.ui.cards.warnings.StorageWarningCard
import vegabobo.dsusideloader.ui.cards.warnings.UnlockedBootloaderCard
import vegabobo.dsusideloader.ui.cards.warnings.UnsupportedCard
import vegabobo.dsusideloader.ui.components.CardBox
import vegabobo.dsusideloader.ui.design.DashboardLayout
import vegabobo.dsusideloader.ui.design.DashboardSection
import vegabobo.dsusideloader.ui.design.ImageLibraryPanel
import vegabobo.dsusideloader.ui.design.InstallCustomization
import vegabobo.dsusideloader.ui.design.OverviewHero
import vegabobo.dsusideloader.ui.design.backendLabel
import vegabobo.dsusideloader.ui.screen.Destinations
import vegabobo.dsusideloader.ui.sdialogs.CancelSheet
import vegabobo.dsusideloader.ui.sdialogs.ConfirmInstallationSheet
import vegabobo.dsusideloader.ui.sdialogs.DiscardDSUSheet
import vegabobo.dsusideloader.ui.sdialogs.ImageSizeWarningSheet
import vegabobo.dsusideloader.ui.sdialogs.ViewLogsBottomSheet
import vegabobo.dsusideloader.ui.util.KeepScreenOn
import vegabobo.dsusideloader.util.OperationModeUtils
import vegabobo.dsusideloader.util.collectAsStateWithLifecycle

object HomeLinks {
    const val DSU_LEARN_MORE = "https://developer.android.com/topic/dsu"
    const val DSU_DOCS = "https://source.android.com/devices/tech/ota/dynamic-system-updates"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(
    navigate: (String) -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    if (uiState.shouldKeepScreenOn) {
        KeepScreenOn()
    }

    LaunchedEffect(Unit) {
        homeViewModel.loadImageLibrary()
        homeViewModel.setupUserPreferences()
        homeViewModel.session.operationMode.collectLatest {
            homeViewModel.initialChecks()
        }
    }

    var sectionName by rememberSaveable { mutableStateOf(DashboardSection.OVERVIEW.name) }
    val section = DashboardSection.valueOf(sectionName)
    val warnings: @Composable () -> Unit = {
        Box(modifier = Modifier.animateContentSize()) {
            when (uiState.additionalCard) {
                AdditionalCardState.NO_DYNAMIC_PARTITIONS ->
                    UnsupportedCard(
                        onClickClose = { exitProcess(0) },
                        onClickContinueAnyway = { homeViewModel.overrideDynamicPartitionCheck() },
                    )

                AdditionalCardState.SETUP_STORAGE ->
                    SetupStorage { homeViewModel.takeUriPermission(it) }

                AdditionalCardState.UNAVAIABLE_STORAGE ->
                    StorageWarningCard(
                        minPercentageFreeStorage = homeViewModel.allocPercentageInt.toString(),
                        onClick = { homeViewModel.overrideUnavaiableStorage() },
                    )

                AdditionalCardState.MISSING_READ_LOGS_PERMISSION ->
                    RequiresLogPermissionCard(
                        onClickGrant = { homeViewModel.grantReadLogs() },
                        onClickRefuse = { homeViewModel.refuseReadLogs() },
                    )

                AdditionalCardState.GRANTING_READ_LOGS_PERMISSION ->
                    GrantingPermissionCard()

                AdditionalCardState.BOOTLOADER_UNLOCKED_WARNING ->
                    UnlockedBootloaderCard { homeViewModel.onClickBootloaderUnlockedWarning() }

                AdditionalCardState.NONE -> {}
            }
        }
    }
    val installation: @Composable () -> Unit = {
        InstallationCard(
            uiState = uiState.installationCard,
            onClickInstall = { homeViewModel.onClickInstall() },
            onClickUnmountSdCardAndRetry = { homeViewModel.onClickUnmountSdCardAndRetry() },
            onClickSetSeLinuxPermissive = { homeViewModel.onClickSetSeLinuxPermissive() },
            onClickRetryInstallation = { homeViewModel.onClickRetryInstallation() },
            onClickClear = { homeViewModel.resetInstallationCard() },
            onSelectFileSuccess = { homeViewModel.onFileSelectionResult(it) },
            onClickCancelInstallation = { homeViewModel.onClickCancel() },
            onClickDiscardInstalledGsiAndInstall = { homeViewModel.onClickDiscardGsiAndStartInstallation() },
            onClickDiscardDsu = { homeViewModel.showDiscardSheet() },
            onClickRebootToDynOS = { homeViewModel.onClickRebootToDynOS() },
            onClickViewLogs = { homeViewModel.showLogsWarning() },
            onClickViewCommands = { navigate(Destinations.ADBInstallation) },
            onCardAction = homeViewModel.installationCardActionHandler(uiState.installationCard),
            minPercentageOfFreeStorage = homeViewModel.allocPercentageInt.toString(),
        )
    }
    val options: @Composable () -> Unit = {
        Text(stringResource(R.string.re_options), style = MaterialTheme.typography.headlineSmall)
        UserdataCard(
            isEnabled = uiState.isInstalling(),
            uiState = uiState.userDataCard,
            onCheckedChange = { homeViewModel.onCheckUserdataCard() },
            onValueChange = { homeViewModel.updateUserdataSize(it) },
        )
        ImageSizeCard(
            isEnabled = uiState.isInstalling(),
            uiState = uiState.imageSizeCard,
            onCheckedChange = { homeViewModel.onCheckImageSizeCard() },
            onValueChange = { homeViewModel.updateImageSize(it) },
        )
        InstallCustomization(
            sourceKey = homeViewModel.session.userSelection.selectedFileUri.toString(),
            initialNote = uiState.library.firstOrNull { it.uri == homeViewModel.session.userSelection.selectedFileUri.toString() }?.note ?: "",
            enabled = !uiState.isInstalling(),
            rootAvailable = homeViewModel.session.isRoot() && Build.VERSION.SDK_INT >= 30,
            backend = uiState.backend,
            hash = uiState.expectedSha256,
            canSave = uiState.installationCard.isInstallable,
            onBackend = homeViewModel::selectBackend,
            onHash = homeViewModel::updateExpectedSha256,
            onSave = homeViewModel::saveCurrentPreset,
        )
        if (uiState.libraryError.isNotEmpty()) Text(uiState.libraryError, color = MaterialTheme.colorScheme.error)
    }
    DashboardLayout(
        section = section,
        onSection = { sectionName = it.name },
        onSettings = { navigate(Destinations.Preferences) },
    ) { wide ->
        when (section) {
            DashboardSection.OVERVIEW -> {
                OverviewHero(OperationModeUtils.getOperationModeAsString(homeViewModel.session.getOperationMode()))
                warnings()
                if (uiState.passedInitialChecks && uiState.additionalCard == AdditionalCardState.NONE) {
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                installation()
                                FilledTonalButton(onClick = { sectionName = DashboardSection.TASKS.name }) { Text(stringResource(R.string.re_view_task)) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { options() }
                        }
                    } else {
                        installation()
                        options()
                    }
                }
            }
            DashboardSection.IMAGES -> ImageLibraryPanel(
                images = uiState.library,
                error = uiState.libraryError,
                enabled = !uiState.isInstalling(),
                onAdd = { homeViewModel.onFileSelectionResult(it); sectionName = DashboardSection.OVERVIEW.name },
                onOpen = { homeViewModel.selectSavedImage(it); sectionName = DashboardSection.OVERVIEW.name },
                onFavorite = homeViewModel::toggleFavorite,
                onRemove = homeViewModel::removeSavedImage,
                onImport = homeViewModel::importImageLibrary,
                onExport = homeViewModel::exportImageLibrary,
            )
            DashboardSection.TASKS -> {
                Text(stringResource(R.string.re_tasks_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.re_tasks_body))
                warnings()
                if (uiState.passedInitialChecks && uiState.additionalCard == AdditionalCardState.NONE && uiState.isInstalling()) {
                    installation()
                } else {
                    CardBox { Text(stringResource(R.string.re_tasks_idle)) }
                }
                if (uiState.installationLogs.isNotEmpty()) {
                    FilledTonalButton(onClick = homeViewModel::showLogsWarning) { Text(stringResource(R.string.view_logs)) }
                    LogcatCard(logs = uiState.installationLogs)
                }
            }
            DashboardSection.TOOLS -> {
                Text(stringResource(R.string.re_tools_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.re_tools_body))
                CardBox(cardTitle = stringResource(R.string.operation_mode)) {
                    Text(OperationModeUtils.getOperationModeAsString(homeViewModel.session.getOperationMode()), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.re_backend_help), modifier = Modifier.padding(top = 12.dp))
                }
                FilledTonalButton(onClick = homeViewModel::showLogsWarning, enabled = uiState.installationLogs.isNotEmpty()) { Text(stringResource(R.string.view_logs)) }
                if (uiState.installationLogs.isEmpty()) Text(stringResource(R.string.re_logs_empty))
                if (homeViewModel.session.installationScriptPath.isNotEmpty()) {
                    FilledTonalButton(onClick = { navigate(Destinations.ADBInstallation) }) { Text(stringResource(R.string.installation)) }
                }
                FilledTonalButton(onClick = { uriHandler.openUri(HomeLinks.DSU_DOCS) }) { Text(stringResource(R.string.re_help)) }
                DsuInfoCard(
                    onClickViewDocs = { uriHandler.openUri(HomeLinks.DSU_DOCS) },
                    onClickLearnMore = { uriHandler.openUri(HomeLinks.DSU_LEARN_MORE) },
                )
            }
        }
    }

    when (uiState.sheetDisplay) {
        SheetDisplayState.CONFIRM_INSTALLATION ->
            ConfirmInstallationSheet(
                filename = homeViewModel.obtainSelectedFilename(),
                userdata = homeViewModel.session.userSelection.getUserDataSizeAsGB(),
                fileSize = homeViewModel.session.userSelection.userSelectedImageSize,
                backend = backendLabel(uiState.backend),
                expectedSha256 = uiState.expectedSha256,
                onClickConfirm = { homeViewModel.onConfirmInstallationSheet() },
                onClickCancel = { homeViewModel.dismissSheet() },
            )

        SheetDisplayState.CANCEL_INSTALLATION ->
            CancelSheet(
                onClickConfirm = { homeViewModel.onClickCancelInstallationButton() },
                onClickCancel = { homeViewModel.dismissSheet() },
            )

        SheetDisplayState.IMAGESIZE_WARNING ->
            ImageSizeWarningSheet(
                onClickConfirm = { homeViewModel.dismissSheet() },
                onClickCancel = { homeViewModel.onCheckImageSizeCard() },
            )

        SheetDisplayState.DISCARD_DSU ->
            DiscardDSUSheet(
                onClickConfirm = { homeViewModel.onClickDiscardGsi() },
                onClickCancel = { homeViewModel.dismissSheet() },
            )

        SheetDisplayState.VIEW_LOGS ->
            ViewLogsBottomSheet(
                logs = uiState.installationLogs,
                onClickSaveLogs = { homeViewModel.saveLogs(it) },
                onDismiss = { homeViewModel.dismissSheet() },
            )

        SheetDisplayState.NONE -> {}
    }
}
