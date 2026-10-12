package com.lightphone.audiobooks

import com.lightphone.audiobooks.library.LocalBookRepository
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec

/**
 * Tool Manager integration: users upload books from their computer's browser
 * (LightOS serves the interface locally) into the tool's private
 * files/shared/books directory, which [LocalBookRepository] scans alongside
 * /sdcard/Audiobooks.
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest(): ClientToolManifest = ClientToolManifest(
        title = "Audiobooks",
        roots = listOf(
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Books",
                    path = LocalBookRepository.TOOL_MANAGER_BOOKS_DIR,
                    headerText = "Upload audiobook files, or a folder of chapter files, from your computer.",
                ),
            ),
        ),
    )

    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        // Fresh uploads landed in the books root. A process that already has
        // the library loaded rescans now; a fresh process rescans on the first
        // Library show anyway.
        LocalBookRepository.scanIfInitialized()
    }
}
