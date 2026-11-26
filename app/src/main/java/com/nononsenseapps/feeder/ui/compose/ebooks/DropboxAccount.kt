package com.nononsenseapps.feeder.ui.compose.ebooks

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.ui.compose.components.ConfirmDeleteDialog
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox

/**
 * Dropbox in the top app bar of the Ebooks, ZIM and Audiobooks tabs: a cloud icon that opens a
 * small menu with the account, the folder ([folder], e.g. "Ebooks") and Sign out. Signed out, a
 * crossed-out cloud explains how to sign in: the phone signs in through sync, after signing in once
 * on Feeder web (or the Mac app).
 */
@Composable
fun DropboxAccountButton(
    what: String,
    folder: String,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val dropbox = remember { DropboxClient.get(context) }
    val link by dropbox.link.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var howTo by remember { mutableStateOf(false) }
    val label = stringResource(if (link != null) R.string.dropbox_account else R.string.dropbox_not_connected)
    Box {
        PlainTooltipBox(tooltip = { Text(label) }) {
            IconButton(onClick = { if (link != null) menu = true else howTo = true }) {
                Icon(if (link != null) Icons.Outlined.Cloud else Icons.Outlined.CloudOff, contentDescription = label)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            link?.let { current ->
                Column(Modifier.widthIn(min = 220.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(current.name.ifBlank { current.email }.ifBlank { "Dropbox" }, style = MaterialTheme.typography.titleSmall)
                    if (current.name.isNotBlank() && current.email.isNotBlank()) {
                        Text(current.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        stringResource(R.string.dropbox_folder, folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dropbox_sign_out)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    onClick = {
                        menu = false
                        confirm = true
                    },
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.dropbox_sign_out_title)) },
            text = { Text(stringResource(R.string.dropbox_sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    dropbox.signOut()
                    onSignedOut()
                }) { Text(stringResource(R.string.dropbox_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (howTo) {
        AlertDialog(
            onDismissRequest = { howTo = false },
            icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
            title = { Text(stringResource(R.string.dropbox_sign_in_title)) },
            text = { Text(stringResource(R.string.dropbox_not_signed_in, what)) },
            confirmButton = { TextButton(onClick = { howTo = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** The shared delete confirmation for a file in Dropbox. */
@Composable
fun DeleteFromDropboxDialog(
    title: String,
    keepsPhoneCopy: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDeleteDialog(
        title = stringResource(R.string.confirm_delete_title_dropbox),
        itemName = title,
        scope = DeleteScope.DROPBOX,
        keepsLocalCopy = keepsPhoneCopy,
        onConfirm = {
            onDelete()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** Uploading needs Dropbox: signed out, say how to sign in instead. */
fun uploadOrExplain(
    context: android.content.Context,
    signedIn: Boolean,
    what: String,
    upload: () -> Unit,
) {
    if (signedIn) upload() else Toast.makeText(context, context.getString(R.string.dropbox_not_signed_in, what), Toast.LENGTH_LONG).show()
}
