package vegabobo.dsusideloader.ui.design

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.loader.BackendPreference
import vegabobo.dsusideloader.loader.SavedImage

enum class DashboardSection(val title: Int, val icon: ImageVector) {
    OVERVIEW(R.string.rx_install, Icons.Outlined.InstallMobile),
    IMAGES(R.string.re_nav_images, Icons.Outlined.FolderOpen),
    TASKS(R.string.re_nav_tasks, Icons.Outlined.TaskAlt),
    TOOLS(R.string.re_nav_tools, Icons.Outlined.Tune),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardLayout(
    section: DashboardSection,
    onSection: (DashboardSection) -> Unit,
    onSettings: () -> Unit,
    bottomAction: @Composable () -> Unit = {},
    content: @Composable (Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row {
            if (wide) {
                WideNavigationRail {
                    DashboardSection.values().forEach { item ->
                        WideNavigationRailItem(
                            selected = section == item,
                            onClick = { onSection(item) },
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.title)) },
                            railExpanded = false,
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        subtitle = { Text(stringResource(section.title)) },
                        actions = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) } },
                    )
                },
                bottomBar = {
                    Column(if (wide) Modifier.navigationBarsPadding() else Modifier) {
                        bottomAction()
                        if (!wide) {
                            ShortNavigationBar {
                                DashboardSection.values().forEach { item ->
                                    ShortNavigationBarItem(
                                        selected = section == item,
                                        onClick = { onSection(item) },
                                        icon = { Icon(item.icon, null) },
                                        label = { Text(stringResource(item.title)) },
                                    )
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    content(wide)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstallPrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) { Text(text) }
    }
}

@Composable
fun backendLabel(backend: BackendPreference): String = stringResource(
    when (backend) {
        BackendPreference.AUTO -> R.string.re_backend_auto
        BackendPreference.SYSTEM_DSU -> R.string.re_backend_system
        BackendPreference.ROOT_DIRECT -> R.string.re_backend_root
    },
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageLibraryPanel(
    images: List<SavedImage>,
    error: String,
    enabled: Boolean,
    onOpen: (SavedImage) -> Unit,
    onFavorite: (SavedImage) -> Unit,
    onRemove: (SavedImage) -> Unit,
    onImport: (Uri) -> Unit,
    onExport: (Uri) -> Unit,
) {
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onImport) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(onExport) }
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SavedImage?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.rx_your_images), style = MaterialTheme.typography.headlineMedium)
            Text(pluralStringResource(R.plurals.rx_image_count, images.size, images.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.rx_library_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.re_import)) }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = { menu = false; importPicker.launch(arrayOf("text/*", "application/octet-stream")) })
                DropdownMenuItem(text = { Text(stringResource(R.string.re_export)) }, leadingIcon = { Icon(Icons.Outlined.Upload, null) }, enabled = images.isNotEmpty(), onClick = { menu = false; exportPicker.launch("dsu-image-presets.txt") })
            }
        }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    if (images.isEmpty()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.re_library_empty)) },
            supportingContent = { Text(stringResource(R.string.rx_library_empty_body)) },
            leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
        )
    }
    Column {
        images.sortedByDescending { it.favorite }.forEach { image ->
            key(image.uri) {
                var itemMenu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable(enabled = enabled) { onOpen(image) },
                    headlineContent = { Text(image.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.re_saved_options, image.userdataGiB, backendLabel(image.backend)))
                            if (image.note.isNotEmpty()) Text(image.note, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (image.sha256.isNotEmpty()) Text(stringResource(R.string.rx_hash_saved))
                        }
                    },
                    leadingContent = { Icon(if (image.favorite) Icons.Outlined.Favorite else Icons.Outlined.InstallMobile, null) },
                    trailingContent = {
                        Box {
                            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { itemMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.rx_image_actions)) }
                            DropdownMenu(itemMenu, { itemMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (image.favorite) R.string.re_unfavorite else R.string.re_favorite)) },
                                    leadingIcon = { Icon(if (image.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null) },
                                    onClick = { itemMenu = false; onFavorite(image) },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.re_remove_image)) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { itemMenu = false; removing = image })
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    removing?.let { image ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.re_remove_image)) }, text = { Text(stringResource(R.string.re_remove_body)) }, confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { onRemove(image); removing = null }) { Text(stringResource(R.string.re_remove_image)) } }, dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun DashboardTool(icon: ImageVector, title: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
    )
}
