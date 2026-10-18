package vegabobo.dsusideloader.ui.screen.home

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
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
import vegabobo.dsusideloader.ui.cards.LogcatCard
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
import vegabobo.dsusideloader.ui.design.DashboardTool
import vegabobo.dsusideloader.ui.design.ExpressiveInstallPane
import vegabobo.dsusideloader.ui.design.ImageLibraryPanel
import vegabobo.dsusideloader.ui.design.InstallPrimaryAction
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { homeViewModel.onFileSelectionResult(uri); sectionName = DashboardSection.OVERVIEW.name }
    }
    val selectFile = { sourcePicker.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-xz", "application/zip", "application/octet-stream")) }
    val idle = !uiState.isInstalling()
    val ready = uiState.passedInitialChecks && uiState.additionalCard == AdditionalCardState.NONE

    val warnings: @Composable () -> Unit = {
        Box(modifier = Modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())) {
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
        if (uiState.sourceLoading) {
            val sourceRequestId = uiState.sourceRequestId
            CardBox(cardTitle = stringResource(R.string.installation)) {
                LoadingIndicator()
                Text(stringResource(R.string.re_reading_source), modifier = Modifier.padding(vertical = 12.dp))
                FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = { homeViewModel.cancelSourceSelection(sourceRequestId) }) { Text(stringResource(R.string.cancel)) }
            }
        } else {
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
    }
    DashboardLayout(
        section = section,
        onSection = { sectionName = it.name },
        onSettings = { navigate(Destinations.Preferences) },
        bottomAction = {
            if (section == DashboardSection.OVERVIEW && idle && ready && !uiState.sourceLoading) {
                InstallPrimaryAction(
                    text = stringResource(if (uiState.installationCard.isInstallable) R.string.rx_review_plan else R.string.rx_select_image),
                    onClick = { if (uiState.installationCard.isInstallable) homeViewModel.onClickInstall() else selectFile() },
                )
            } else if (section == DashboardSection.IMAGES) {
                InstallPrimaryAction(stringResource(R.string.re_add_image), enabled = idle && !uiState.sourceLoading, onClick = selectFile)
            }
        },
    ) { wide ->
        when (section) {
            DashboardSection.OVERVIEW -> {
                warnings()
                if (ready) {
                    if (idle) {
                        ExpressiveInstallPane(
                            wide = wide,
                            filename = uiState.installationCard.text,
                            sourceLoading = uiState.sourceLoading,
                            error = uiState.installationCard.errorText,
                            enabled = idle && !uiState.sourceLoading,
                            permissionMode = OperationModeUtils.getOperationModeAsString(homeViewModel.session.getOperationMode()),
                            userdata = uiState.userDataCard, imageSize = uiState.imageSizeCard,
                            backend = uiState.backend, hash = uiState.expectedSha256,
                            rootAvailable = homeViewModel.session.isRoot() && Build.VERSION.SDK_INT >= 30,
                            sourceKey = homeViewModel.session.userSelection.selectedFileUri.toString(),
                            initialNote = uiState.library.firstOrNull { it.uri == homeViewModel.session.userSelection.selectedFileUri.toString() }?.note ?: "",
                            onSelectFile = selectFile,
                            onCancelSource = { homeViewModel.cancelSourceSelection(uiState.sourceRequestId) },
                            onUserdata = homeViewModel::updateUserdataSize,
                            onUserdataToggle = { homeViewModel.onCheckUserdataCard() },
                            onImageSize = homeViewModel::updateImageSize,
                            onImageSizeToggle = { homeViewModel.onCheckImageSizeCard() },
                            onBackend = homeViewModel::selectBackend,
                            onHash = homeViewModel::updateExpectedSha256,
                            onSave = homeViewModel::saveCurrentPreset,
                        )
                        if (uiState.libraryError.isNotEmpty()) Text(uiState.libraryError, color = MaterialTheme.colorScheme.error)
                    } else {
                        installation()
                    }
                }
            }
            DashboardSection.IMAGES -> ImageLibraryPanel(
                images = uiState.library,
                error = uiState.libraryError,
                enabled = !uiState.isInstalling() && !uiState.sourceLoading,
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
                    FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = homeViewModel::showLogsWarning) { Text(stringResource(R.string.view_logs)) }
                    LogcatCard(logs = uiState.installationLogs)
                }
            }
            DashboardSection.TOOLS -> {
                Text(stringResource(R.string.operation_mode), style = MaterialTheme.typography.titleMedium)
                Text(OperationModeUtils.getOperationModeAsString(homeViewModel.session.getOperationMode()), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.re_backend_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DashboardTool(Icons.Outlined.Article, stringResource(R.string.view_logs), stringResource(if (uiState.installationLogs.isEmpty()) R.string.re_logs_empty else R.string.rx_log_summary), enabled = uiState.installationLogs.isNotEmpty(), onClick = homeViewModel::showLogsWarning)
                if (homeViewModel.session.installationScriptPath.isNotEmpty()) {
                    DashboardTool(Icons.Outlined.Terminal, stringResource(R.string.rx_adb_commands), stringResource(R.string.rx_adb_summary), onClick = { navigate(Destinations.ADBInstallation) })
                }
                DashboardTool(Icons.Outlined.Info, stringResource(R.string.re_help), stringResource(R.string.rx_dsu_docs), onClick = { uriHandler.openUri(HomeLinks.DSU_DOCS) })
                DashboardTool(Icons.Outlined.HelpOutline, stringResource(R.string.rx_learn_dsu), stringResource(R.string.rx_learn_summary), onClick = { uriHandler.openUri(HomeLinks.DSU_LEARN_MORE) })
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
