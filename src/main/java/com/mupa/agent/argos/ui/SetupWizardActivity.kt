package com.mupa.agent.argos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mupa.agent.argos.ui.compose.ArgosBackground
import com.mupa.agent.argos.ui.compose.ArgosTheme
import com.mupa.agent.argos.ui.compose.screens.SetupWizardScreen

class SetupWizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArgosTheme {
                ArgosBackground {
                    SetupWizardScreen(
                        onBack = { finish() },
                        onStateChanged = {},
                    )
                }
            }
        }
    }
}
