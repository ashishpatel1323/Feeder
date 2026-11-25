package com.nononsenseapps.feeder.ui.compose.ebooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.dropbox.DropboxClient

/**
 * Dropbox status on the Ebooks and ZIM tabs. The phone signs in through sync: signing in once on
 * Feeder web (or the Mac app) is enough.
 */
@Composable
fun DropboxCard(
    what: String,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val context = LocalContext.current
    val dropbox = remember { DropboxClient.get(context) }
    val link by dropbox.link.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (link != null) Icons.Outlined.Cloud else Icons.Outlined.CloudOff, contentDescription = null)
            Column(Modifier.weight(1f)) {
                val current = link
                if (current != null) {
                    Text(
                        stringResource(R.string.dropbox_synced, what, current.name.ifBlank { current.email }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(stringResource(R.string.dropbox_not_signed_in, what), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (link != null) {
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.dropbox_sign_out)) }
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
}
