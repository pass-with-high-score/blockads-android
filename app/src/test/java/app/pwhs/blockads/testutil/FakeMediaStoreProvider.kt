package app.pwhs.blockads.testutil

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.Robolectric
import java.io.File

/** Minimal `media` provider so MediaStore.Downloads insert/openOutputStream work under Robolectric. */
class FakeMediaStoreProvider : ContentProvider() {

    val files = mutableMapOf<String, File>()
    val displayNames = mutableMapOf<String, String>()
    var deletes = 0

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = files.size + 1
        val row = Uri.withAppendedPath(uri, id.toString())
        files[row.toString()] = File.createTempFile("media", ".bin").apply { deleteOnExit() }
        displayNames[row.toString()] = values?.getAsString("_display_name").orEmpty()
        return row
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(uri.toString()), ParcelFileDescriptor.parseMode(mode))

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deletes++
        return 0
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun install(): FakeMediaStoreProvider =
            Robolectric.buildContentProvider(FakeMediaStoreProvider::class.java).create("media").get()
    }
}
