package com.mupa.agent.argos.admin

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager

class AdminToolsManager(private val context: Context) {
    fun lockTaskState(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.lockTaskModeState else 0
    }

    fun startLockTask(activity: ComponentActivity): String? {
        return runCatching {
            activity.startLockTask()
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun stopLockTask(activity: ComponentActivity): String? {
        return runCatching {
            activity.stopLockTask()
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun openAndroidSettings(): Intent {
        return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openDeveloperSettings(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openAppSettings(): Intent {
        return Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun rebootIfDeviceOwner(): String? {
        val dpm = DeviceOwnerPolicyManager(context)
        return dpm.rebootDeviceError(context.packageName)
    }

    fun requestShutdown(): String? {
        val intent = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("android.intent.extra.KEY_CONFIRM", false)
        return runCatching {
            context.startActivity(intent)
            null
        }.getOrElse { t ->
            t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }
        }
    }

    fun closeAppIfDeviceOwner(packageName: String): String? {
        val dpm = DeviceOwnerPolicyManager(context)
        return dpm.tryStopApp(context.packageName, packageName)
    }

    fun setVolume(percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val value = (max * (percent.coerceIn(0, 100) / 100f)).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }
}
