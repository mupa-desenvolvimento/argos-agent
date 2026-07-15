package com.mupa.agent.argos.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mupa.agent.argos.ui.compose.ArgosBackground
import com.mupa.agent.argos.ui.compose.ArgosTheme
import com.mupa.agent.argos.ui.compose.screens.KioskAppsScreen

class KioskAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArgosTheme {
                ArgosBackground {
                    KioskAppsScreen(onBack = { finish() })
                }
            }
        }
    }
}
