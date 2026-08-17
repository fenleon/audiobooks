package com.lightphone.audiobooks.server

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Serves the Audiobooks library files to the tool's detached player as
 * `content://` URIs.
 *
 * Playback now runs in the tool's process (SDK detached audio), where the
 * tool plugin forbids storage access — so the tool cannot open the MediaStore
 * URIs the binder hands out (`GetBooks.Part.playbackReference`). This provider
 * proxies them: `content://com.lightphone.audiobooks.server.media/media/<id>`
 * opens the MediaStore row `<id>` in the companion's process, which holds the
 * audio read permission.
 *
 * Exported with no read permission: the tool holds no permission this provider
 * could require, and `grantUriPermissions` can't be applied to URIs the player
 * opens directly. The exposure is limited to apps that can discover the URIs
 * (package visibility restricts provider discovery on API 11+); the URI scheme
 * is otherwise unguessable.
 */
class AudiobookMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val target = mediaStoreUri(uri) ?: return null
        return try {
            context?.contentResolver?.openFileDescriptor(target, "r")
        } catch (error: Exception) {
            Log.w(TAG, "openFile failed for $uri", error)
            null
        }
    }

    override fun getType(uri: Uri): String? {
        val target = mediaStoreUri(uri) ?: return null
        return context?.contentResolver?.getType(target)
    }

    private fun mediaStoreUri(uri: Uri): Uri? {
        val mediaId = uri.lastPathSegment?.toLongOrNull() ?: return null
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
    }

    // Playback only reads files; the rest of the provider surface is unused.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    private companion object {
        const val TAG = "AudiobookMedia"
    }
}
