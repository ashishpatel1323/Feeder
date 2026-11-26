package com.nononsenseapps.feeder.ui.compose.shelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.shelf.BookCovers
import com.nononsenseapps.feeder.model.shelf.CoverCandidate
import com.nononsenseapps.feeder.model.shelf.ShelfMarks
import kotlinx.coroutines.launch

/**
 * "Cover from the Internet" (as in Voice): searches for [query] (title and author, editable) and
 * puts the picked picture on the book [key] ("ebook:<id>" / "audiobook:<id>"), on every device.
 */
@Composable
fun CoverPickerDialog(
    key: String,
    query: String,
    audiobook: Boolean,
    marks: ShelfMarks,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(query) }
    var search by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<CoverCandidate>?>(null) }
    var applying by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(search) {
        results = null
        results = BookCovers.search(text, audiobook) { some -> if (some.isNotEmpty()) results = some }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_from_internet)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cover_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search++ }),
                    trailingIcon = {
                        IconButton(onClick = { search++ }) { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cover_from_internet)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (applying != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.cover_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                val found = results
                when {
                    found == null ->
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    found.isEmpty() -> Text(stringResource(R.string.cover_none), style = MaterialTheme.typography.bodyMedium)
                    else ->
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(88.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 420.dp),
                        ) {
                            items(found, key = { it.url }) { c ->
                                AsyncImage(
                                    model = c.thumb,
                                    contentDescription = c.label,
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(enabled = applying == null) {
                                                applying = c.url
                                                failed = false
                                                scope.launch {
                                                    if (BookCovers.apply(context, key, c.url)) {
                                                        marks.setCover(key, c.url)
                                                        onDismiss()
                                                    } else {
                                                        failed = true
                                                    }
                                                    applying = null
                                                }
                                            },
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cover_cancel)) } },
    )
}
