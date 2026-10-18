package vegabobo.dsusideloader.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.loader.BackendPreference
import vegabobo.dsusideloader.ui.screen.home.ImageSizeCardState
import vegabobo.dsusideloader.ui.screen.home.UserDataCardState

/** Presentation only: every installation still passes through the ViewModel's guarded entry. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveInstallPane(
    wide: Boolean,
    filename: String,
    sourceLoading: Boolean,
    error: String,
    enabled: Boolean,
    permissionMode: String,
    userdata: UserDataCardState,
    imageSize: ImageSizeCardState,
    backend: BackendPreference,
    hash: String,
    rootAvailable: Boolean,
    sourceKey: String,
    initialNote: String,
    onSelectFile: () -> Unit,
    onCancelSource: () -> Unit,
    onUserdata: (String) -> Unit,
    onUserdataToggle: (Boolean) -> Unit,
    onImageSize: (String) -> Unit,
    onImageSizeToggle: (Boolean) -> Unit,
    onBackend: (BackendPreference) -> Unit,
    onHash: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf("") }
    var hashDraft by rememberSaveable(sourceKey) { mutableStateOf(hash) }
    var note by rememberSaveable(sourceKey) { mutableStateOf(initialNote) }
    val capacity = if (userdata.isSelected && userdata.text.isNotEmpty()) userdata.text.removeSuffix("GB") + " GiB" else "2 GiB"
    val source: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rx_install_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(permissionMode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 8.dp, bottomStart = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.clickable(enabled = enabled && !sourceLoading, role = Role.Button, onClick = onSelectFile).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialShapes.Cookie6Sided.toShape(), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(60.dp)) {
                            if (sourceLoading) LoadingIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(8.dp)) else Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(18.dp))
                        }
                        Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (filename.isEmpty()) R.string.rx_local_source else R.string.rx_selected_source), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (sourceLoading) stringResource(R.string.re_reading_source) else filename.ifEmpty { stringResource(R.string.rx_source_title) },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!sourceLoading) Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(if (filename.isEmpty()) R.string.rx_source_formats else R.string.rx_change_source), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (sourceLoading) TextButton(onCancelSource) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    val options: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.rx_install_options), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            OptionRow(Icons.Outlined.SdStorage, stringResource(R.string.userdata_size), capacity, enabled, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp)) { sheet = "userdata" }
            OptionRow(Icons.Outlined.Tune, stringResource(R.string.re_backend), backendLabel(backend), enabled, RoundedCornerShape(4.dp)) { sheet = "backend" }
            OptionRow(Icons.Outlined.Security, stringResource(R.string.re_integrity), stringResource(if (hash.isEmpty()) R.string.rx_not_set else R.string.rx_hash_set), enabled, RoundedCornerShape(4.dp)) { hashDraft = hash; sheet = "hash" }
            OptionRow(Icons.Outlined.MoreHoriz, stringResource(R.string.rx_advanced), stringResource(R.string.rx_advanced_summary), enabled, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)) { sheet = "advanced" }
            if (filename.isNotEmpty()) TextButton(onClick = { sheet = "save" }, enabled = enabled && !sourceLoading, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.re_save_preset)) }
        }
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1.15f)) { source() }
            Column(Modifier.weight(1f)) { options() }
        }
    } else {
        source()
        options()
    }
    if (sheet.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { sheet = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (sheet) {
                    "userdata" -> {
                        SheetHeading(stringResource(R.string.userdata_size), stringResource(R.string.rx_userdata_help))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(2, 4, 8, 16).forEachIndexed { index, gib ->
                                ToggleButton(
                                    checked = if (userdata.isSelected) userdata.text == "${gib}GB" || userdata.text == gib.toString() else gib == 2,
                                    onCheckedChange = { if (!userdata.isSelected) onUserdataToggle(true); onUserdata(gib.toString()) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shapes = when (index) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); 3 -> ButtonGroupDefaults.connectedTrailingButtonShapes(); else -> ButtonGroupDefaults.connectedMiddleButtonShapes() },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                                ) { Text(gib.toString(), style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                        Text(stringResource(R.string.rx_capacity_unit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = if (userdata.isSelected) userdata.text else "", onValueChange = { if (!userdata.isSelected) onUserdataToggle(true); onUserdata(it) }, label = { Text(stringResource(R.string.rx_custom_capacity)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = userdata.isError, supportingText = { if (userdata.isError) Text(stringResource(R.string.allowed_userdata_allocation, userdata.maximumAllowed)) }, modifier = Modifier.fillMaxWidth())
                    }
                    "backend" -> {
                        SheetHeading(stringResource(R.string.re_backend), stringResource(R.string.re_backend_help))
                        BackendPreference.values().forEach { choice ->
                            val available = choice != BackendPreference.ROOT_DIRECT || rootAvailable
                            Row(Modifier.fillMaxWidth().selectable(selected = backend == choice, enabled = available, role = Role.RadioButton) { onBackend(choice) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = backend == choice, onClick = null, enabled = available)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(backendLabel(choice), style = MaterialTheme.typography.titleMedium, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!available) Text(stringResource(R.string.rx_root_requirement), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    "hash" -> {
                        SheetHeading(stringResource(R.string.re_integrity), stringResource(R.string.re_sha_help))
                        val invalid = hashDraft.isNotEmpty() && !hashDraft.matches(Regex("[0-9a-fA-F]{64}"))
                        OutlinedTextField(value = hashDraft, onValueChange = { hashDraft = it.trim().take(128) }, label = { Text(stringResource(R.string.re_sha_label)) }, isError = invalid, supportingText = { if (invalid) Text(stringResource(R.string.re_sha_invalid)) }, modifier = Modifier.fillMaxWidth())
                    }
                    "advanced" -> {
                        SheetHeading(stringResource(R.string.rx_advanced), stringResource(R.string.not_recommended_option))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Switch(checked = imageSize.isSelected, onCheckedChange = { sheet = ""; onImageSizeToggle(it) })
                            Text(stringResource(R.string.image_size), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        if (imageSize.isSelected) OutlinedTextField(value = imageSize.text, onValueChange = onImageSize, label = { Text(stringResource(R.string.image_size_info)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    "save" -> {
                        SheetHeading(stringResource(R.string.re_save_preset), stringResource(R.string.re_preset_help))
                        OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text(stringResource(R.string.re_note)) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Button(onClick = { if (sheet == "save") onSave(note); if (sheet == "hash") onHash(hashDraft); sheet = "" }, enabled = sheet != "hash" || hashDraft.isEmpty() || hashDraft.matches(Regex("[0-9a-fA-F]{64}")), shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(if (sheet == "save") R.string.rx_save else R.string.rx_done)) }
            }
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, value: String, enabled: Boolean, shape: RoundedCornerShape, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
            headlineContent = { Text(title) },
            supportingContent = { Text(value) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}

@Composable
private fun ColumnScope.SheetHeading(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
