package com.mupa.agent.argos.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.mupa.agent.argos.managers.DeviceIdentityManager

/**
 * Read-only bridge so MPlayer (a non-Device-Owner app) can read the same hardware serial the
 * Argos Agent uses to key `m_argos/devices/{serial}` — without this, MPlayer falls back to
 * ANDROID_ID/system properties and ends up with a different device_id than the Agent on the
 * same physical device. See ARGOS_DEVICE_SERIAL_PROVIDER.md (mplayer repo) for the full
 * contract; MPlayer's DeviceIdentityManager already queries this and falls back safely if the
 * query fails for any reason, so this provider has no hard dependents.
 */
class DeviceInfoProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "serial", CODE_SERIAL)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        if (matcher.match(uri) != CODE_SERIAL) return null
        val ctx = context ?: return null
        val serial = runCatching {
            DeviceIdentityManager.getDeviceFingerprint(ctx).serial?.trim().orEmpty()
        }.getOrDefault("")
        return MatrixCursor(arrayOf(COLUMN_SERIAL)).apply { addRow(arrayOf(serial)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.mupa.agent.argos.provider.device"
        private const val COLUMN_SERIAL = "serial"
        private const val CODE_SERIAL = 1
    }
}
