package com.mupa.agent.argos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity

class LockTaskBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action.orEmpty()
        when (action) {
            "com.mupa.agent.argos.action.LOCKTASK_START" -> runCatching { startLockTask() }
            "com.mupa.agent.argos.action.LOCKTASK_STOP" -> runCatching { stopLockTask() }
        }
        finish()
    }
}

