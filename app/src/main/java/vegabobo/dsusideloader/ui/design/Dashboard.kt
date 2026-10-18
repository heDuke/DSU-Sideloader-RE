package vegabobo.dsusideloader.ui.design

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.loader.BackendPreference
import vegabobo.dsusideloader.loader.SavedImage
import vegabobo.dsusideloader.ui.components.ApplicationScreen
import vegabobo.dsusideloader.ui.components.CardBox
import vegabobo.dsusideloader.ui.components.TopBar

// Expressive visual tokens use stable APIs supported by this project's Compose version.
// Newer Material Expressive experimental components can be adopted in a toolchain-only PR.
enum class DashboardSection(val title: Int, val icon: ImageVector) {
    OVERVIEW(R.string.re_nav_overview, Icons.Outlined.Dashboard),
    IMAGES(R.string.re_nav_images, Icons.Outlined.FolderOpen),
    TASKS(R.string.re_nav_tasks, Icons.Outlined.TaskAlt),
    TOOLS(R.string.re_nav_tools, Icons.Outlined.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardLayout(
    section: DashboardSection,
    onSection: (DashboardSection) -> Unit,
    onSettings: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row {
            if (wide) {
                NavigationRail(Modifier.fillMaxHeight().width(112.dp).padding(top = 24.dp)) {
                    DashboardSection.values().forEach { item ->
                        NavigationRailItem(
                            selected = section == item,
                            onClick = { onSection(item) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.title)) },
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
            ApplicationScreen(
                modifier = Modifier.padding(horizontal = if (wide) 24.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                topBar = {
                    TopBar(
                        barTitle = stringResource(section.title),
                        icon = Icons.Outlined.Settings,
                        iconContentDescription = stringResource(R.string.settings),
                        scrollBehavior = it,
                        onClickIcon = onSettings,
                    )
                },
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            DashboardSection.values().forEach { item ->
                                NavigationBarItem(
                                    selected = section == item,
                                    onClick = { onSection(item) },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(stringResource(item.title)) },
                                )
                            }
                        }
                    }
                },
            ) { content(wide) }
        }
    }
}

@Composable
fun OverviewHero(mode: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomEnd = 12.dp, bottomStart = 36.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.re_environment, mode), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.re_hero_title), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.re_hero_body), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.re_plan_steps), style = MaterialTheme.typography.labelLarge)
        }
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

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InstallCustomization(
    sourceKey: String,
    initialNote: String,
    enabled: Boolean,
    rootAvailable: Boolean,
    backend: BackendPreference,
    hash: String,
    canSave: Boolean,
    onBackend: (BackendPreference) -> Unit,
    onHash: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    var note by remember(sourceKey, initialNote) { mutableStateOf(initialNote) }
    CardBox(cardTitle = stringResource(R.string.re_backend)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendPreference.values().forEach { choice ->
                if (choice != BackendPreference.ROOT_DIRECT || rootAvailable || backend == choice) {
                    FilterChip(
                        selected = backend == choice,
                        onClick = { onBackend(choice) },
                        enabled = enabled && (choice != BackendPreference.ROOT_DIRECT || rootAvailable),
                        label = { Text(backendLabel(choice)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        Text(stringResource(R.string.re_backend_help), style = MaterialTheme.typography.bodyMedium)
    }
    CardBox(cardTitle = stringResource(R.string.re_integrity)) {
        val invalid = hash.isNotEmpty() && !hash.matches(Regex("[a-fA-F0-9]{64}"))
        OutlinedTextField(
            value = hash,
            onValueChange = onHash,
            enabled = enabled,
            label = { Text(stringResource(R.string.re_sha_label)) },
            isError = invalid,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (invalid) R.string.re_sha_invalid else R.string.re_sha_help),
            style = MaterialTheme.typography.bodyMedium,
            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    CardBox(cardTitle = stringResource(R.string.re_save_preset)) {
        Text(stringResource(R.string.re_preset_help), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(2000) },
            enabled = enabled,
            label = { Text(stringResource(R.string.re_note)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        FilledTonalButton(onClick = { onSave(note) }, enabled = enabled && canSave, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.re_save_preset))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageLibraryPanel(
    images: List<SavedImage>,
    error: String,
    enabled: Boolean,
    onAdd: (android.net.Uri) -> Unit,
    onOpen: (SavedImage) -> Unit,
    onFavorite: (SavedImage) -> Unit,
    onRemove: (SavedImage) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onExport: (android.net.Uri) -> Unit,
) {
    val add = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onAdd) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onImport) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(onExport) }
    var removing by remember { mutableStateOf<SavedImage?>(null) }
    Text(stringResource(R.string.re_library_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.re_library_body), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { add.launch(arrayOf("*/*")) }, enabled = enabled, modifier = Modifier.heightIn(min = 52.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.re_add_image))
        }
        OutlinedButton(onClick = { importPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text(stringResource(R.string.re_import)) }
        OutlinedButton(onClick = { exportPicker.launch("dsu-image-presets.txt") }, enabled = images.isNotEmpty()) { Text(stringResource(R.string.re_export)) }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    if (images.isEmpty()) CardBox { Text(stringResource(R.string.re_library_empty), style = MaterialTheme.typography.titleLarge) }
    images.sortedByDescending { it.favorite }.forEach { image ->
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(image.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onFavorite(image) }) {
                    Icon(
                        if (image.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(if (image.favorite) R.string.re_unfavorite else R.string.re_favorite),
                    )
                }
                IconButton(onClick = { removing = image }) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.re_remove_image))
                }
            }
            Text(stringResource(R.string.re_saved_options, image.userdataGiB, backendLabel(image.backend)), style = MaterialTheme.typography.bodyMedium)
            if (image.note.isNotEmpty()) Text(image.note, modifier = Modifier.padding(top = 8.dp))
            if (image.sha256.isNotEmpty()) Text("SHA-256: ${image.sha256}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            FilledTonalButton(onClick = { onOpen(image) }, enabled = enabled, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.re_open_image)) }
        }
    }
    Text(stringResource(R.string.re_library_transfer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    removing?.let { image ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.re_remove_image)) },
            text = { Text(stringResource(R.string.re_remove_body)) },
            confirmButton = { TextButton(onClick = { onRemove(image); removing = null }) { Text(stringResource(R.string.re_remove_image)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
