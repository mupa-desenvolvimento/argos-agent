package com.mupa.agent.argos.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import com.mupa.agent.argos.ui.compose.ArgosBackground
import com.mupa.agent.argos.ui.compose.ArgosTheme
import com.mupa.agent.argos.ui.compose.screens.AdminCenterScreen

class AdminCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArgosTheme {
                ArgosBackground {
                    AdminCenterScreen(
                        onBack = { finish() },
                        onOpenKioskApps = { startActivity(Intent(this@AdminCenterActivity, KioskAppsActivity::class.java)) },
                        onOpenSetup = { startActivity(Intent(this@AdminCenterActivity, com.mupa.agent.argos.ui.SetupWizardActivity::class.java)) },
                    )
                }
            }
        }
    }
}
