package com.nononsenseapps.feeder.ui.compose.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderFilterTest {
    private val folders = listOf("Health", "Product", "Science")

    @Test
    fun togglesInFolderOrderAndDropsGoneFolders() {
        assertEquals(listOf("Science"), toggleFolder(emptyList(), "Science", folders))
        assertEquals(listOf("Health", "Science"), toggleFolder(listOf("Science"), "Health", folders))
        assertEquals(listOf("Science"), toggleFolder(listOf("Health", "Science"), "Health", folders))
        assertEquals(listOf("Product"), toggleFolder(listOf("Gone"), "Product", folders))
        assertEquals(folders, toggleFolder(listOf("Health", "Product"), "Science", folders))
        assertEquals(listOf("Science"), pickedFolders(setOf("Science", "Gone"), folders))
    }

    @Test
    fun labels() {
        val several = { n: Int -> "$n groups" }
        assertEquals("All groups", folderFilterLabel(emptyList(), "All groups", several))
        assertEquals("Science", folderFilterLabel(listOf("Science"), "All groups", several))
        assertEquals("2 groups", folderFilterLabel(listOf("A", "B"), "All groups", several))
    }
}
