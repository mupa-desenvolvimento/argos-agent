package com.mupa.agent.argos.security

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class AdminAuditLogEntry(
    val ts: Long,
    val action: String,
    val success: Boolean,
    val payloadJson: String,
)

class AdminAuditLogStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts INTEGER NOT NULL,
              action TEXT NOT NULL,
              success INTEGER NOT NULL,
              payload_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE(ts DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_action ON $TABLE(action)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun add(action: String, success: Boolean, payload: JSONObject) {
        val values = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("action", action.trim())
            put("success", if (success) 1 else 0)
            put("payload_json", payload.toString())
        }
        writableDatabase.insert(TABLE, null, values)
    }

    fun listRecent(limit: Int = 100): List<AdminAuditLogEntry> {
        val cur = readableDatabase.query(
            TABLE,
            arrayOf("ts", "action", "success", "payload_json"),
            null,
            null,
            null,
            null,
            "ts DESC",
            limit.coerceIn(1, 500).toString(),
        )
        return cur.use { c ->
            val out = ArrayList<AdminAuditLogEntry>(c.count)
            while (c.moveToNext()) out.add(mapRow(c))
            out
        }
    }

    private fun mapRow(c: Cursor): AdminAuditLogEntry {
        return AdminAuditLogEntry(
            ts = c.getLong(0),
            action = c.getString(1),
            success = c.getInt(2) == 1,
            payloadJson = c.getString(3),
        )
    }

    companion object {
        private const val DB_NAME = "argos_admin_audit.db"
        private const val DB_VERSION = 1
        private const val TABLE = "admin_audit"
    }
}

