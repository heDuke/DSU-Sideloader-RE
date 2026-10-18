package preview

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import vegabobo.dsusideloader.loader.BackendPreference
import vegabobo.dsusideloader.loader.SavedImage
import androidx.compose.ui.res.stringResource
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.ui.components.ApplicationScreen
import vegabobo.dsusideloader.ui.components.TopBar
import vegabobo.dsusideloader.ui.components.PreferenceItem
import vegabobo.dsusideloader.ui.cards.updater.UpdaterCard
import vegabobo.dsusideloader.ui.screen.about.UpdaterCardState
import vegabobo.dsusideloader.ui.screen.about.UpdateStatus
import vegabobo.dsusideloader.ui.cards.installation.InstallationCard
import vegabobo.dsusideloader.ui.screen.home.InstallationCardState
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.ui.design.DashboardLayout
import vegabobo.dsusideloader.ui.design.DashboardSection
import vegabobo.dsusideloader.ui.design.ExpressiveInstallPane
import vegabobo.dsusideloader.ui.design.ImageLibraryPanel
import vegabobo.dsusideloader.ui.design.InstallPrimaryAction
import vegabobo.dsusideloader.ui.screen.home.ImageSizeCardState
import vegabobo.dsusideloader.ui.screen.home.UserDataCardState
import vegabobo.dsusideloader.ui.theme.DSUHelperTheme

/** Real production composables rendered by layoutlib; callbacks/registry are substitutes. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class PreviewTest(private val name: String, private val dark: Boolean, private val scale: Float, private val wide: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf(
            arrayOf<Any>("phone-light", false, 1f, false),
            arrayOf<Any>("phone-dark", true, 1f, false),
            arrayOf<Any>("phone-large-text", false, 2f, false),
            arrayOf<Any>("wide-light", false, 1f, true),
        )
    }
    private val device = (if (wide) DeviceConfig.PIXEL_5.copy(screenWidth = 2560, screenHeight = 1800) else DeviceConfig.PIXEL_5).copy(locale = "zh-rCN")
    @get:Rule val paparazzi = Paparazzi(deviceConfig = device)
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
        }
    }

    @Test fun emptyInstall() = render(DashboardSection.OVERVIEW, false)
    @Test fun selectedInstall() = render(DashboardSection.OVERVIEW, true)
    @Test fun library() = render(DashboardSection.IMAGES, true)

    @Test fun nativeComponents() = paparazzi.snapshot("$name-native-components") {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalDensity provides Density(density.density, scale)) {
            DSUHelperTheme(darkTheme = dark, dynamicColor = false) {
                ApplicationScreen(topBar = { TopBar(scrollBehavior = it, barTitle = stringResource(R.string.settings), onClickBackButton = {}) }) {
                    PreferenceItem(title = stringResource(R.string.re_brand_colors), description = stringResource(R.string.re_brand_help), showToggle = true, isChecked = true)
                    UpdaterCard(UpdaterCardState(updateStatus = UpdateStatus.UPDATE_FOUND, updateVersion = "2.04"), true, {}, {}, {}, {})
                }
            }
        }
    }

    @Test fun activeTask() = paparazzi.snapshot("$name-active-task") {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalDensity provides Density(density.density, scale)) {
            DSUHelperTheme(darkTheme = dark, dynamicColor = false) {
                DashboardLayout(DashboardSection.TASKS, {}, {}) {
                    InstallationCard(
                        uiState = InstallationCardState(installationStep = InstallationStep.VERIFYING_IMAGE_INTEGRITY, isProgressBarIndeterminate = true), minPercentageOfFreeStorage = "20",
                        onClickClear = {}, onClickInstall = {}, onClickRetryInstallation = {}, onClickUnmountSdCardAndRetry = {}, onClickSetSeLinuxPermissive = {},
                        onClickCancelInstallation = {}, onClickDiscardInstalledGsiAndInstall = {}, onClickDiscardDsu = {}, onClickRebootToDynOS = {}, onSelectFileSuccess = {},
                        onClickViewLogs = {}, onClickViewCommands = {}, onCardAction = {},
                    )
                }
            }
        }
    }

    private fun render(section: DashboardSection, selected: Boolean) = paparazzi.snapshot("$name-${section.name.lowercase()}-$selected") {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalDensity provides Density(density.density, scale)) {
            DSUHelperTheme(darkTheme = dark, dynamicColor = false) {
                DashboardLayout(section, {}, {}, bottomAction = {
                    InstallPrimaryAction(if (section == DashboardSection.IMAGES) "添加镜像" else if (selected) "查看安装方案" else "选择镜像", onClick = {})
                }) { wideLayout ->
                    when (section) {
                        DashboardSection.OVERVIEW -> ExpressiveInstallPane(
                            wide = wideLayout, filename = if (selected) "Android-16-GSI.img.gz" else "", sourceLoading = false, error = "", enabled = true,
                            permissionMode = "ADB", userdata = if (selected) UserDataCardState(isSelected = true, text = "8GB") else UserDataCardState(), imageSize = ImageSizeCardState(),
                            backend = BackendPreference.AUTO, hash = if (selected) "a".repeat(64) else "", rootAvailable = false,
                            sourceKey = "content://preview/1", initialNote = "", onSelectFile = {}, onCancelSource = {}, onUserdata = {}, onUserdataToggle = {}, onImageSize = {}, onImageSizeToggle = {}, onBackend = {}, onHash = {}, onSave = {},
                        )
                        DashboardSection.IMAGES -> ImageLibraryPanel(
                            images = listOf(
                                SavedImage("content://preview/1", "Android-16-GSI.img.gz", "a".repeat(64), 8, favorite = true, note = "Android 16 应用兼容性测试"),
                                SavedImage("content://preview/2", "LineageOS-22.2-arm64.img.xz", "", 4, note = "备用测试镜像"),
                            ),
                            error = "", enabled = true, onOpen = {}, onFavorite = {}, onRemove = {}, onImport = {}, onExport = {},
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}
