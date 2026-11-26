package com.nononsenseapps.feeder.ui.compose.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/** Where a delete takes effect. Shown as the second line of [ConfirmDeleteDialog]. */
enum class DeleteScope {
    /** Synced through Feeder's own sync: it goes away on every device. */
    ALL_DEVICES,

    /** Local copy only (downloads, files on this phone). */
    THIS_DEVICE,

    /** The file in Dropbox. */
    DROPBOX,
}

/** Label of the destructive button. */
enum class DeleteVerb(
    @StringRes val res: Int,
) {
    DELETE(R.string.confirm_delete_action_delete),
    REMOVE(R.string.confirm_delete_action_remove),
    UNSUBSCRIBE(R.string.confirm_delete_action_unsubscribe),
    CLEAR(R.string.confirm_delete_action_clear),
}

/**
 * The one delete confirmation used everywhere in the app (web has the same dialog in
 * `web/src/confirmDelete.tsx`). Title like "Delete playlist?", the item named in bold, then a line
 * saying where it is deleted (see [DeleteScope]), Cancel and a red destructive button.
 *
 * [extra] is an optional sentence after the scope line, e.g. what stays behind.
 * [keepsLocalCopy] only matters for [DeleteScope.DROPBOX].
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    itemName: String,
    scope: DeleteScope,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    verb: DeleteVerb = DeleteVerb.DELETE,
    extra: String? = null,
    keepsLocalCopy: Boolean = true,
) {
    val syncOn = rememberSyncConfigured()
    val scopeText =
        when (scope) {
            DeleteScope.ALL_DEVICES ->
                if (syncOn) {
                    stringResource(R.string.confirm_delete_scope_all_devices)
                } else {
                    stringResource(R.string.confirm_delete_scope_all_devices_no_sync)
                }
            DeleteScope.THIS_DEVICE -> stringResource(R.string.confirm_delete_scope_this_device)
            DeleteScope.DROPBOX ->
                if (keepsLocalCopy) {
                    stringResource(R.string.confirm_delete_scope_dropbox)
                } else {
                    stringResource(R.string.confirm_delete_scope_dropbox_no_copy)
                }
        }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(title) },
        text = {
            Text(
                buildAnnotatedString {
                    if (itemName.isNotBlank()) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(itemName) }
                        append("\n\n")
                    }
                    append(scopeText)
                    if (!extra.isNullOrBlank()) {
                        append(" ")
                        append(extra)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(verb.res), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_delete_cancel)) }
        },
    )
}

/** "3 items" for bulk deletes. */
@Composable
fun itemCountLabel(count: Int): String = pluralStringResource(R.plurals.confirm_delete_n_items, count, count)

/** True when this device is in a sync chain, so deletes reach the other devices. */
@Composable
fun rememberSyncConfigured(): Boolean {
    val di = LocalDI.current
    val flow =
        remember(di) {
            val repository: Repository by di.instance()
            repository.getSyncRemoteFlow().map { it?.hasSyncChain() == true }
        }
    val on by flow.collectAsState(initial = true)
    return on
}

/** A delete waiting for its confirmation (see [rememberDeleteConfirmer]). */
class DeleteRequest(
    @StringRes val title: Int,
    val itemName: String,
    val scope: DeleteScope,
    val verb: DeleteVerb = DeleteVerb.DELETE,
    @StringRes val extra: Int? = null,
    val onConfirm: () -> Unit,
)

/** For deletes started from callbacks: [ask] shows [ConfirmDeleteDialog] and runs the delete on yes. */
class DeleteConfirmer {
    var pending by mutableStateOf<DeleteRequest?>(null)
        private set

    fun ask(request: DeleteRequest) {
        pending = request
    }

    fun dismiss() {
        pending = null
    }
}

/** A [DeleteConfirmer] whose dialog is shown by this composable while a request is pending. */
@Composable
fun rememberDeleteConfirmer(): DeleteConfirmer {
    val confirmer = remember { DeleteConfirmer() }
    confirmer.pending?.let { r ->
        ConfirmDeleteDialog(
            title = stringResource(r.title),
            itemName = r.itemName,
            scope = r.scope,
            verb = r.verb,
            extra = r.extra?.let { stringResource(it) },
            onConfirm = {
                confirmer.dismiss()
                r.onConfirm()
            },
            onDismiss = confirmer::dismiss,
        )
    }
    return confirmer
}

/** Highlights: the start of the highlighted text stands in for a name. */
fun DeleteConfirmer.askHighlight(
    text: String,
    onConfirm: () -> Unit,
) {
    val t = text.replace(Regex("\\s+"), " ").trim()
    ask(
        DeleteRequest(
            title = R.string.confirm_delete_title_highlight,
            itemName = "“" + (if (t.length > 90) t.take(90) + "…" else t) + "”",
            scope = DeleteScope.ALL_DEVICES,
            extra = R.string.confirm_delete_extra_highlight,
            onConfirm = onConfirm,
        ),
    )
}
