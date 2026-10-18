package vegabobo.dsusideloader.ui.sdialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vegabobo.dsusideloader.R
import vegabobo.dsusideloader.model.DSUConstants
import vegabobo.dsusideloader.ui.components.DialogItem
import vegabobo.dsusideloader.ui.components.DialogLikeBottomSheet

@Composable
fun ConfirmInstallationSheet(
    filename: String,
    userdata: String,
    fileSize: Long,
    backend: String = "",
    expectedSha256: String = "",
    onClickConfirm: () -> Unit,
    onClickCancel: () -> Unit,
) {
    DialogLikeBottomSheet(
        title = stringResource(id = R.string.proceed_installation),
        icon = Icons.Outlined.InstallMobile,
        text = stringResource(id = R.string.proceed_installation_description),
        content = {
            Spacer(modifier = Modifier.padding(4.dp))
            DialogItem(
                icon = Icons.Outlined.InsertDriveFile,
                title = "${stringResource(id = R.string.selected_file)}:",
                text = filename,
                textColor = MaterialTheme.colorScheme.onBackground,
            )
            DialogItem(
                icon = Icons.Outlined.Storage,
                title = "${stringResource(id = R.string.userdata_size)}:",
                text = "${userdata}GB",
                textColor = MaterialTheme.colorScheme.onBackground,
            )
            if (fileSize != DSUConstants.DEFAULT_IMAGE_SIZE) {
                DialogItem(
                    icon = Icons.Outlined.Article,
                    title = "${stringResource(id = R.string.image_size)}:",
                    text = "${fileSize}b",
                    textColor = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (backend.isNotEmpty()) {
                DialogItem(icon = Icons.Outlined.InstallMobile, title = stringResource(R.string.re_backend), text = backend)
            }
            if (expectedSha256.isNotEmpty()) {
                DialogItem(icon = Icons.Outlined.Article, title = stringResource(R.string.re_sha_label), text = expectedSha256)
            }
        },
        confirmText = stringResource(id = R.string.proceed),
        cancelText = stringResource(id = R.string.cancel),
        onClickConfirm = { onClickConfirm() },
        onClickCancel = { onClickCancel() },
    )
}
