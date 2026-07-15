package com.mupa.agent.argos.kiosk

import androidx.activity.ComponentActivity

class KioskManager {
    fun enableLockTask(activity: ComponentActivity) {
        runCatching { activity.startLockTask() }
    }

    fun disableLockTask(activity: ComponentActivity) {
        runCatching { activity.stopLockTask() }
    }
}

