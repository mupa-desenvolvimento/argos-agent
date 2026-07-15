package com.mupa.agent.argos.security

import android.content.Context

class SecurityManager(private val context: Context) {
    fun isRooted(): Boolean = false
    fun isDeveloperOptionsEnabled(): Boolean = false
    fun isUsbDebuggingEnabled(): Boolean = false
}

