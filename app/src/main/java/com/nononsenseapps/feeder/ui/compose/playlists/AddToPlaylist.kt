package com.nononsenseapps.feeder.ui.compose.playlists

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.PlaylistPlayer
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.playlists.newPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/** One "Add to playlist" request: the items and a title to show. */
data class AddToPlaylistRequest(
    val ids: List<Long>,
    val title: String,
)

/** Any screen asks for the Add to playlist sheet here; [AddToPlaylistHost] shows it. */
class AddToPlaylistRequests {
    val request = MutableStateFlow<AddToPlaylistRequest?>(null)

    fun open(
        id: Long,
        title: String,
    ) {
        request.value = AddToPlaylistRequest(listOf(id), title)
    }
}

/** Opens the Add to playlist sheet for one item, from any composable. */
@Composable
fun rememberAddToPlaylist(): (Long, String) -> Unit {
    val requests: AddToPlaylistRequests by LocalDI.current.instance()
    return remember(requests) { { id, title -> requests.open(id, title) } }
}

/** The visible "Playlist" button for article rows and the reader. */
@Composable
fun AddToPlaylistChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inPlaylist: Boolean = false,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(stringResource(R.string.playlist_add_button), style = MaterialTheme.typography.bodySmall) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors =
            if (inPlaylist) {
                AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                AssistChipDefaults.assistChipColors()
            },
        modifier = modifier.height(28.dp),
    )
}

/** The Add to playlist sheet: Play next, Play last, your playlists, or a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistHost() {
    val di = LocalDI.current
    val requests: AddToPlaylistRequests by di.instance()
    val store: PlaylistStore by di.instance()
    val player: PlaylistPlayer by di.instance()
    val request by requests.request.collectAsStateWithLifecycle()
    val playlists by store.playlists.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var naming by remember { mutableStateOf<String?>(null) }
    var namingFor by remember { mutableStateOf<AddToPlaylistRequest?>(null) }
    if (request != null) namingFor = request

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val pending = namingFor
    val name = naming
    // A dialog, not a field in the sheet: the keyboard opening over the sheet closed it
    if (name != null && pending != null && request == null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(R.string.playlist_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { naming = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    val pl = newPlaylist(name.trim().ifBlank { "My playlist" }, smart = false, existing = playlists, itemIds = pending.ids)
                    store.save(pl)
                    toast(pl.name)
                    naming = null
                }) { Text(stringResource(R.string.playlist_create)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { naming = null }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
    val r = request ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = { requests.request.value = null }

    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.playlist_add_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                r.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            SheetRow(Icons.Default.SkipNext, stringResource(R.string.playlist_play_next), stringResource(R.string.playlist_play_next_sub)) {
                r.ids.reversed().forEach(player::playNext)
                close()
            }
            SheetRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.playlist_play_last), stringResource(R.string.playlist_play_last_sub)) {
                r.ids.forEach(player::playLast)
                close()
            }
            Text(
                stringResource(R.string.playlist_my_playlists),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
            val manual = playlists.filter { !it.smart }
            manual.forEach { pl ->
                val has = r.ids.all { it in pl.itemIds }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (has) {
                                    store.remove(pl.id, r.ids)
                                } else {
                                    store.add(pl.id, r.ids)
                                    toast(context.getString(R.string.playlist_add_title) + ": " + pl.name)
                                }
                            }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    PlaylistTile(pl.icon, pl.color, 40)
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, fontWeight = FontWeight.SemiBold)
                        Text("${pl.itemIds.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = has, onCheckedChange = null)
                }
            }
            SheetRow(Icons.Default.Add, stringResource(R.string.playlist_new), null, accent = true) {
                naming = ""
                requests.request.value = null
            }
            if (manual.isEmpty()) {
                Text(
                    stringResource(R.string.playlist_smart_not_listed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (accent) MaterialTheme.colorScheme.primary else Color.Unspecified)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The colored square with the playlist's emoji. */
@Composable
fun PlaylistTile(
    icon: String,
    color: Long,
    size: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(size.dp)
                .background(Color(color), RoundedCornerShape((size / 4).dp)),
    ) {
        Text(icon, fontSize = (size * 0.42).sp, color = Color.White)
    }
}

/** Article lists provide this so every row shows the Playlist button. */
val LocalAddToPlaylist = androidx.compose.runtime.compositionLocalOf<((Long, String) -> Unit)?> { null }

/** A compact, always visible "Playlist" button for article rows. */
@Composable
fun SmallPlaylistButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        Text(stringResource(R.string.playlist_add_button), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}
