package com.mupa.agent.argos.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.mupa.agent.argos.managers.DeviceIdentityManager

class DeviceIdentityProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        return when (matcher.match(uri)) {
            MATCH_DEVICE_ID -> {
                val manager = DeviceIdentityManager(ctx.applicationContext)
                val cursor = MatrixCursor(COLUMNS)
                cursor.addRow(
                    arrayOf(
                        manager.generateIfMissing(),
                        manager.getCachedSource(),
                        if (manager.getCachedStable()) 1 else 0,
                        manager.getCachedResolvedAtMs(),
                    ),
                )
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        return when (matcher.match(uri)) {
            MATCH_DEVICE_ID -> "vnd.android.cursor.item/vnd.com.mupa.device_id"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.mupa.agent.argos.identity"
        private const val PATH_DEVICE_ID = "device_id"
        private const val MATCH_DEVICE_ID = 1

        val COLUMNS = arrayOf(
            "device_id",
            "source",
            "stable",
            "resolved_at_ms",
        )

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_DEVICE_ID, MATCH_DEVICE_ID)
        }
    }
}

