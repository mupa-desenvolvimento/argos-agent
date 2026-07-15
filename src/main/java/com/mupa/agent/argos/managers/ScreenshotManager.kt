package com.mupa.agent.argos.managers

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.mupa.agent.argos.firebase.FirebaseRuntimeMode
import java.io.ByteArrayOutputStream

/**
 * Captura a tela do dispositivo e publica a URL no RTDB para visualização na Web.
 *
 * Estratégia de captura (melhor esforço):
 *   1. `screencap -p` direto (apps de sistema / device owner em muitos coletores).
 *   2. `su -c screencap -p` (dispositivos com root).
 * O PNG é lido do stdout, enviado ao Firebase Storage e a URL gravada em
 *   m_argos/devices/{deviceId}/screenshot = { url, at, status }
 */
class ScreenshotManager(context: Context) {
    private val appContext = context.applicationContext

    fun captureAndUpload(deviceId: String): Boolean {
        val png = capture() ?: run {
            writeStatus(deviceId, status = "failed", url = null, message = "capture_unavailable")
            return false
        }
        return try {
            val ts = System.currentTimeMillis()
            val storage = FirebaseStorage.getInstance()
            val ref = storage.reference.child("screenshots/$deviceId/$ts.png")
            Tasks.await(ref.putBytes(png))
            val url = Tasks.await(ref.downloadUrl).toString()
            writeStatus(deviceId, status = "ok", url = url, message = null, at = ts)
            true
        } catch (e: Exception) {
            writeStatus(deviceId, status = "failed", url = null, message = e.message ?: "upload_error")
            false
        }
    }

    private fun capture(): ByteArray? {
        // 1. direct screencap
        runScreencap(arrayOf("screencap", "-p"))?.let { if (looksPng(it)) return it }
        // 2. via su
        runScreencap(arrayOf("su", "-c", "screencap -p"))?.let { if (looksPng(it)) return it }
        return null
    }

    private fun runScreencap(cmd: Array<String>): ByteArray? {
        return try {
            val proc = Runtime.getRuntime().exec(cmd)
            val out = ByteArrayOutputStream()
            proc.inputStream.use { input -> input.copyTo(out) }
            proc.waitFor()
            val bytes = out.toByteArray()
            if (bytes.size > 1000) bytes else null
        } catch (_: Exception) { null }
    }

    private fun looksPng(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private fun writeStatus(deviceId: String, status: String, url: String?, message: String?, at: Long = System.currentTimeMillis()) {
        // RTDB is event-bus-only (seq counters); screenshot status/URL must not be persisted there.
        if (!FirebaseRuntimeMode.allowAgentPublish) return
        try {
            val node = FirebaseDatabase.getInstance().reference
                .child("m_argos").child("devices").child(deviceId).child("screenshot")
            val map = hashMapOf<String, Any?>("status" to status, "at" to at)
            if (url != null) map["url"] = url
            if (message != null) map["message"] = message
            node.setValue(map)
        } catch (_: Exception) { /* ignore */ }
    }
}
