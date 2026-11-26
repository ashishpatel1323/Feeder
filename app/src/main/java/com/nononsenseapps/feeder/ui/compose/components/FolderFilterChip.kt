package com.nononsenseapps.feeder.ui.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Picked folders that still exist, in the order of [folders]. */
fun pickedFolders(
    picked: Collection<String>,
    folders: List<String>,
): List<String> = folders.filter { it in picked }

/** Adds or removes one folder (every folder picked still leaves out feeds in no folder, unlike All). */
fun toggleFolder(
    picked: Collection<String>,
    name: String,
    folders: List<String>,
): List<String> = pickedFolders(if (name in picked) picked - name else picked + name, folders)

/** "All folders", the one folder's name, or "3 folders". */
fun folderFilterLabel(
    picked: List<String>,
    allLabel: String,
    several: (Int) -> String,
): String =
    when (picked.size) {
        0 -> allLabel
        1 -> picked[0]
        else -> several(picked.size)
    }

/**
 * One filter chip for folders, in place of a row of folder chips: shows "All", the one picked
 * folder, or "2 groups", and opens a menu of check boxes. All clears the pick. The menu stays
 * open while folders are ticked, so several can be picked in one go.
 */
@Composable
fun FolderFilterChip(
    folders: List<String>,
    picked: List<String>,
    onChange: (List<String>) -> Unit,
    allLabel: String,
    several: (Int) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = folderFilterLabel(picked, allLabel, several)
    Box(modifier) {
        FilterChip(
            selected = picked.isNotEmpty(),
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
            leadingIcon = {
                Icon(
                    if (picked.isNotEmpty()) Icons.Default.Check else Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics { this.contentDescription = "$contentDescription: $label" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(allLabel, fontWeight = if (picked.isEmpty()) FontWeight.SemiBold else null) },
                leadingIcon = { Checkbox(checked = picked.isEmpty(), onCheckedChange = null) },
                onClick = {
                    onChange(emptyList())
                    open = false
                },
            )
            HorizontalDivider()
            folders.forEach { f ->
                val checked = f in picked
                DropdownMenuItem(
                    text = { Text(f, fontWeight = if (checked) FontWeight.SemiBold else null) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = { onChange(toggleFolder(picked, f, folders)) },
                )
            }
        }
    }
}
