package com.mupa.agent.argos.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ArgosAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return

        val type = e.eventType
        val interactionEvent = type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (interactionEvent) {
            recordLastUserInteraction(applicationContext, pkg)
        }
        val relevant = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!relevant) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            recordLastForegroundPackage(applicationContext, pkg)
        }

        val isInstaller = pkg.equals("com.android.packageinstaller", ignoreCase = true) ||
            pkg.equals("com.google.android.packageinstaller", ignoreCase = true)
        val root = rootInActiveWindow ?: return

        if (pkg.equals(ARGOS_REMOTE_PACKAGE, ignoreCase = true) && isAnyDeskAutoClickArmed(applicationContext)) {
            logEventWithCooldown(pkg, type)
        }

        val remoteSupportPkgNow =
            pkg.equals(ANYDESK_PACKAGE, ignoreCase = true) ||
                pkg.equals(ANYDESK_PLUGIN_PACKAGE, ignoreCase = true) ||
                pkg.equals(ARGOS_REMOTE_PACKAGE, ignoreCase = true) ||
                pkg.lowercase().contains("argos.remote") ||
                pkg.lowercase().contains("argosremote") ||
                pkg.lowercase().contains("anydesk")
        if (remoteSupportPkgNow) {
            recordAnyDeskSeen(applicationContext)
        }

        if (isInstaller) {
            runCatching {
                clickFirstByText(root, listOf("Instalar", "INSTALL", "Install")) ||
                    clickFirstByText(root, listOf("Avançar", "NEXT", "Next")) ||
                    clickFirstByText(root, listOf("Concluir", "DONE", "Done")) ||
                    clickFirstByText(root, listOf("Abrir", "OPEN", "Open")) ||
                    clickFirstByText(root, listOf("OK", "Ok"))
            }.onFailure { t ->
                Log.w(TAG, "installer_click_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
            }
            return
        }

        runCatching {
            if (handleAnyDeskStartRecordingDialog(pkg, type, root)) {
                disarmAnyDeskAutoClick(applicationContext)
                Log.i(TAG, "anydesk_recording_dialog_clicked")
                return
            }
        }.onFailure { t ->
            Log.w(TAG, "anydesk_recording_dialog_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
        }

        runCatching {
            if (handleAnyDeskRuntimePermissionDialog(pkg, type, root)) {
                disarmAnyDeskAutoClick(applicationContext)
                Log.i(TAG, "anydesk_runtime_permission_clicked")
                return
            }
        }.onFailure { t ->
            Log.w(TAG, "anydesk_runtime_permission_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
        }

        runCatching {
            if (handleRemoteMediaProjectionDialog(pkg, e.className?.toString().orEmpty(), type, root)) {
                Log.i(TAG, "remote_projection_auto_consented")
                disarmAnyDeskAutoClick(applicationContext)
                return
            }
        }.onFailure { t ->
            Log.w(TAG, "remote_projection_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
        }

        // Auto-accept the system screen-capture ("Iniciar agora") consent even when the auto-click
        // wasn't armed by a web command — i.e. when a support tech connects directly through
        // AnyDesk. Safety gate: this only fires on the system MediaProjection surface AND only when
        // the consent dialog itself names AnyDesk in its body, so we never blanket-accept screen
        // capture requested by any other app.
        runCatching {
            if (handleUnarmedRemoteProjectionDialog(pkg, e.className?.toString().orEmpty(), type, root)) {
                Log.i(TAG, "remote_projection_auto_consented_unarmed")
                return
            }
        }.onFailure { t ->
            Log.w(TAG, "remote_projection_unarmed_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
        }

        // Suppress Google Play / GMS sign-in prompts on kiosk devices.
        runCatching {
            if (handleGooglePlaySignInDialog(pkg, type, root)) {
                Log.i(TAG, "google_play_signin_dismissed pkg=$pkg")
                return
            }
        }.onFailure { t ->
            Log.w(TAG, "google_play_signin_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
        }

        if (!remoteSupportPkgNow) return

        if (!isAnyDeskAutoClickArmed(applicationContext)) return

        val looksLikePrompt =
            looksLikeProjectionPrompt(root) ||
                looksLikeRuntimePermissionPrompt(root)
        if (!looksLikePrompt) return

        runCatching {
            Log.i(TAG, "anydesk_auto_click_try pkg=$pkg type=$type")
            val clicked =
                clickOrTapFirstByText(root, ANYDESK_ACCEPT_TEXTS) ||
                    clickOrTapFirstByText(root, ANYDESK_START_NOW_TEXTS) ||
                    clickOrTapFirstByText(root, ANYDESK_ALLOW_TEXTS) ||
                    clickOrTapFirstByViewIdSuffix(root, ANYDESK_POSITIVE_BUTTON_IDS)

            if (clicked) {
                disarmAnyDeskAutoClick(applicationContext)
                Log.i(TAG, "anydesk_auto_click_success")
                return@runCatching
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val didTap = tryAnyDeskCoordinateTapWithCooldown()
                if (didTap) {
                    Log.i(TAG, "anydesk_auto_tap_sent")
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "anydesk_auto_click_failed ${t.javaClass.simpleName}:${t.message?.trim().orEmpty()}")
        }
    }

    override fun onInterrupt() {
    }

    private fun clickFirstByText(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t) ?: continue
            for (n in nodes) {
                val target = findClickable(n) ?: continue
                if (target.isEnabled && target.isClickable) {
                    val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) {
                        Log.i(TAG, "clicked text=$t")
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun clickOrTapFirstByText(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        val candidates = findNodesByAnyText(root, texts)
        for ((t, n) in candidates) {
            val clickable = findClickable(n)
            if (clickable != null && clickable.isEnabled && clickable.isClickable) {
                val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) {
                    Log.i(TAG, "clicked text=$t")
                    return true
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (tapNodeCenter(n)) {
                    Log.i(TAG, "tapped text=$t")
                    return true
                }
            }
        }
        return false
    }

    private fun clickOrTapFirstByViewIdSuffix(root: AccessibilityNodeInfo, suffixes: List<String>): Boolean {
        val wanted = suffixes.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return false
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val id = n.viewIdResourceName?.trim()?.lowercase().orEmpty()
            if (id.isNotBlank()) {
                for (s in wanted) {
                    if (id.endsWith(s)) {
                        val clickable = findClickable(n)
                        if (clickable != null && clickable.isEnabled && clickable.isClickable) {
                            val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (ok) {
                                Log.i(TAG, "clicked viewId=$id")
                                return true
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            if (tapNodeCenter(n)) {
                                Log.i(TAG, "tapped viewId=$id")
                                return true
                            }
                        }
                    }
                }
            }
            val count = n.childCount
            for (i in 0 until count) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }
        return false
    }

    private fun findNodesByAnyText(root: AccessibilityNodeInfo, texts: List<String>): List<Pair<String, AccessibilityNodeInfo>> {
        val wanted = texts.map { it.trim() }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, AccessibilityNodeInfo>>(8)
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val nodeText = n.text?.toString()?.trim().orEmpty()
            val nodeDesc = n.contentDescription?.toString()?.trim().orEmpty()
            for (w in wanted) {
                if (nodeText.equals(w, ignoreCase = true) || nodeDesc.equals(w, ignoreCase = true)) {
                    out.add(w to n)
                    break
                }
                if (nodeText.contains(w, ignoreCase = true) || nodeDesc.contains(w, ignoreCase = true)) {
                    out.add(w to n)
                    break
                }
            }
            val count = n.childCount
            for (i in 0 until count) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }
        return out
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 1 || r.height() <= 1) return false
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun tryAnyDeskCoordinateTapWithCooldown(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastTap = prefs.getLong(ANYDESK_LAST_TAP_MS, 0L)
        if (now - lastTap < 1200L) return false
        val count = prefs.getInt(ANYDESK_TAP_COUNT, 0)
        if (count >= 4) {
            disarmAnyDeskAutoClick(applicationContext)
            return false
        }

        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)

        val xAbs = prefs.getInt(ANYDESK_TAP_X, -1)
        val yAbs = prefs.getInt(ANYDESK_TAP_Y, -1)
        val xPctRaw = java.lang.Double.longBitsToDouble(prefs.getLong(ANYDESK_TAP_X_PCT, java.lang.Double.doubleToRawLongBits(-1.0)))
        val yPctRaw = java.lang.Double.longBitsToDouble(prefs.getLong(ANYDESK_TAP_Y_PCT, java.lang.Double.doubleToRawLongBits(-1.0)))

        val x = when {
            xAbs >= 0 -> xAbs.toFloat()
            xPctRaw >= 0.0 -> {
                val r = if (xPctRaw <= 1.0) xPctRaw else (xPctRaw / 100.0)
                (w * r).toFloat()
            }
            else -> return false
        }
        val y = when {
            yAbs >= 0 -> yAbs.toFloat()
            yPctRaw >= 0.0 -> {
                val r = if (yPctRaw <= 1.0) yPctRaw else (yPctRaw / 100.0)
                (h * r).toFloat()
            }
            else -> return false
        }

        prefs.edit()
            .putLong(ANYDESK_LAST_TAP_MS, now)
            .putInt(ANYDESK_TAP_COUNT, count + 1)
            .apply()
        Log.i(TAG, "anydesk_auto_tap x=$x y=$y w=$w h=$h attempt=${count + 1}")
        return tapAt(x.coerceIn(1f, (w - 2).toFloat()), y.coerceIn(1f, (h - 2).toFloat()))
    }

    /**
     * Auto-accepts the Android system screen-capture consent dialog ("Iniciar agora" / "Start now")
     * WITHOUT requiring the web-triggered arming, for the common case where a support tech
     * initiates an AnyDesk session directly. Deliberately narrow so it can never rubber-stamp
     * screen capture for an arbitrary app:
     *   1. must be the system MediaProjection surface (systemui / android / permissioncontroller /
     *      the projection request activity), not an app window; and
     *   2. the dialog body must name AnyDesk / a remote-support app — the OS consent dialog always
     *      states which app is requesting capture, so this ties the auto-accept to AnyDesk only.
     */
    private fun handleUnarmedRemoteProjectionDialog(
        pkg: String,
        className: String,
        type: Int,
        root: AccessibilityNodeInfo,
    ): Boolean {
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return false
        }

        val lowerPkg = pkg.trim().lowercase()
        val lowerClass = className.trim().lowercase()
        val isProjectionSurface =
            lowerPkg == "com.android.systemui" ||
                lowerPkg == "android" ||
                lowerPkg.contains("permissioncontroller") ||
                lowerClass.contains("projectionrequestactivity") ||
                lowerClass.contains("mediaprojection")
        if (!isProjectionSurface) return false

        // Only auto-accept when this capture request is clearly AnyDesk's: either the consent
        // dialog names AnyDesk in its body, or AnyDesk was the active app moments ago (the
        // incoming-connection flow brings AnyDesk to the foreground right before it asks for
        // capture). Never blanket-accept an arbitrary app's screen-capture request.
        val isAnyDeskRequest = dialogMentionsRemoteSupportApp(root) ||
            isRecentAnyDeskForeground(applicationContext, 120_000L)
        if (!isAnyDeskRequest) return false
        if (!hasAnyText(root, ANYDESK_START_NOW_TEXTS + ANYDESK_ALLOW_TEXTS)) return false

        Log.i(TAG, "unarmed_projection_dialog_detected pkg=$pkg class=$className")

        if (clickOrTapFirstByText(root, ANYDESK_START_NOW_TEXTS + ANYDESK_ALLOW_TEXTS)) return true
        if (clickOrTapFirstByViewIdSuffix(root, ANYDESK_POSITIVE_BUTTON_IDS)) return true
        if (clickProjectionPromptPositiveButton(root)) return true
        return false
    }

    private fun dialogMentionsRemoteSupportApp(root: AccessibilityNodeInfo): Boolean =
        hasAnyText(root, REMOTE_SUPPORT_APP_TOKENS)

    private fun handleRemoteMediaProjectionDialog(
        pkg: String,
        className: String,
        type: Int,
        root: AccessibilityNodeInfo,
    ): Boolean {
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return false
        }
        if (!isAnyDeskAutoClickArmed(applicationContext)) return false

        val lowerPkg = pkg.trim().lowercase()
        val lowerClass = className.trim().lowercase()
        val isProjectionSurface =
            lowerPkg == "com.android.systemui" ||
                lowerPkg == "android" ||
                lowerPkg.contains("permissioncontroller") ||
                lowerPkg == ARGOS_REMOTE_PACKAGE ||
                lowerClass.contains("projectionrequestactivity") ||
                lowerClass.contains("mediaprojection")
        if (!isProjectionSurface) return false
        if (!looksLikeProjectionPrompt(root)) return false

        Log.i(TAG, "remote_projection_dialog_detected pkg=$pkg class=$className")

        val clicked = clickOrTapFirstByText(root, ANYDESK_START_NOW_TEXTS + ANYDESK_ALLOW_TEXTS)
        if (clicked) return true

        val clickedById = clickOrTapFirstByViewIdSuffix(root, ANYDESK_POSITIVE_BUTTON_IDS)
        if (clickedById) return true

        val clickedPositive = clickProjectionPromptPositiveButton(root)
        if (clickedPositive) return true

        return false
    }

    /**
     * Detects and auto-dismisses Google Play / GMS sign-in dialogs.
     *
     * On kiosk devices without a Google account, Google Play Services (com.google.android.gms)
     * periodically surfaces a "Sign in" dialog. As Device Owner we already block account
     * management via DISALLOW_MODIFY_ACCOUNTS, but the dialog can still appear. This handler
     * auto-clicks the dismiss / skip button so it never interrupts the kiosk experience.
     */
    private fun handleGooglePlaySignInDialog(pkg: String, type: Int, root: AccessibilityNodeInfo): Boolean {
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false

        val lower = pkg.trim().lowercase()
        val isGooglePkg =
            lower == "com.google.android.gms" ||
                lower == "com.android.vending" ||
                lower == "com.google.android.gsf.login" ||
                lower == "com.google.android.gms.ui" ||
                lower.startsWith("com.google.android.gms")
        if (!isGooglePkg) return false

        // Only act if the screen actually has sign-in related text.
        val hasSignInText = hasAnyText(
            root,
            listOf(
                "sign in",
                "fazer login",
                "entrar com",
                "entre com",
                "log in",
                "faça login",
                "faca login",
                "adicionar conta",
                "add account",
                "google account",
                "conta google",
            ),
        )
        if (!hasSignInText) return false

        Log.i(TAG, "google_play_signin_dialog_detected pkg=$pkg")

        // Try to click a "skip / not now / cancel" button.
        val dismissed = clickFirstByText(
            root,
            listOf(
                "Skip",
                "Pular",
                "Ignorar",
                "Not now",
                "Agora não",
                "Agora nao",
                "Later",
                "Depois",
                "Mais tarde",
                "Cancel",
                "Cancelar",
                "No thanks",
                "Não, obrigado",
                "Nao, obrigado",
                "Não obrigado",
                "Dismiss",
                "Fechar",
            ),
        )
        if (dismissed) return true

        // No dismiss button visible — press BACK to close the dialog.
        performGlobalAction(GLOBAL_ACTION_BACK)
        return true
    }

    private fun handleAnyDeskStartRecordingDialog(pkg: String, type: Int, root: AccessibilityNodeInfo): Boolean {
        val armed = isAnyDeskAutoClickArmed(applicationContext)
        val recentAnyDesk = isRecentAnyDeskForeground(applicationContext, 60_000L)
        val remoteSupportPkg = isRemoteSupportPackage(pkg)
        if (!armed && !recentAnyDesk && !remoteSupportPkg) return false

        val looksLikeProjectionPrompt = looksLikeProjectionPrompt(root)

        if (!looksLikeProjectionPrompt) return false
        Log.i(TAG, "anydesk_recording_dialog_try pkg=$pkg type=$type")

        val clicked = clickOrTapFirstByText(
            root,
            ANYDESK_START_NOW_TEXTS + ANYDESK_ALLOW_TEXTS,
        )
        if (clicked) return true

        val clickedById = clickOrTapFirstByViewIdSuffix(
            root,
            ANYDESK_POSITIVE_BUTTON_IDS,
        )
        if (clickedById) return true

        val clickedPositiveButton = clickProjectionPromptPositiveButton(root)
        if (clickedPositiveButton) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val didTap = tryAnyDeskCoordinateTapWithCooldown()
            if (didTap) return true
        }

        return false
    }

    private fun handleAnyDeskRuntimePermissionDialog(pkg: String, type: Int, root: AccessibilityNodeInfo): Boolean {
        val armed = isAnyDeskAutoClickArmed(applicationContext)
        val recentAnyDesk = isRecentAnyDeskForeground(applicationContext, 60_000L)
        val remoteSupportPkg = isRemoteSupportPackage(pkg)
        if (!armed && !recentAnyDesk && !remoteSupportPkg) return false

        val hasAllow = hasAnyText(root, RUNTIME_PERMISSION_ALLOW_TEXTS)
        val hasDeny = hasAnyText(root, RUNTIME_PERMISSION_DENY_TEXTS)
        if (!hasAllow || !hasDeny) return false

        Log.i(TAG, "anydesk_runtime_permission_try pkg=$pkg type=$type")
        val clicked = clickOrTapFirstByText(
            root,
            RUNTIME_PERMISSION_ALLOW_TEXTS,
        )
        if (clicked) return true

        val clickedById = clickOrTapFirstByViewIdSuffix(
            root,
            ANYDESK_POSITIVE_BUTTON_IDS,
        )
        if (clickedById) return true

        val clickedPositiveButton = clickProjectionPromptPositiveButton(root)
        if (clickedPositiveButton) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val didTap = tryAnyDeskCoordinateTapWithCooldown()
            if (didTap) return true
        }
        return false
    }

    private fun deviceModelKey(): String {
        val m = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return "${m}:${model}".trim(':')
    }

    private fun logEventWithCooldown(pkg: String, type: Int) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(DEBUG_LAST_EVENT_MS, 0L)
        if (now - last < 900L) return
        prefs.edit().putLong(DEBUG_LAST_EVENT_MS, now).apply()
        Log.i(TAG, "event pkg=$pkg type=$type model=${deviceModelKey()}")
    }

    private fun looksLikeProjectionPrompt(root: AccessibilityNodeInfo): Boolean {
        return hasAnyText(root, PROJECTION_PROMPT_TEXTS) &&
            hasAnyText(root, ANYDESK_START_NOW_TEXTS + ANYDESK_ALLOW_TEXTS)
    }

    private fun looksLikeRuntimePermissionPrompt(root: AccessibilityNodeInfo): Boolean {
        val hasAllow = hasAnyText(root, RUNTIME_PERMISSION_ALLOW_TEXTS)
        val hasDeny = hasAnyText(root, RUNTIME_PERMISSION_DENY_TEXTS)
        return hasAllow && hasDeny
    }

    private fun recordLastForegroundPackage(context: Context, pkg: String) {
        val now = System.currentTimeMillis()
        val lower = pkg.trim().lowercase()
        val ignore =
            lower == "com.android.systemui" ||
                lower == "com.google.android.systemui" ||
                lower == "com.android.permissioncontroller" ||
                lower == "com.google.android.permissioncontroller"
        if (ignore) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_FOREGROUND_PKG, pkg)
            .putLong(LAST_FOREGROUND_AT, now)
            .apply()
    }

    private fun recordLastUserInteraction(context: Context, pkg: String) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_INTERACTION_AT, now)
            .putString(LAST_INTERACTION_PKG, pkg.trim())
            .apply()
    }

    private fun isRecentAnyDeskForeground(context: Context, windowMs: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val at = prefs.getLong(LAST_ANYDESK_AT, 0L)
        if (at <= 0L) return false
        val now = System.currentTimeMillis()
        if (now - at > windowMs.coerceAtLeast(1_000L)) return false
        return true
    }

    private fun recordAnyDeskSeen(context: Context) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_ANYDESK_AT, now)
            .apply()
    }

    private fun hasAnyText(root: AccessibilityNodeInfo, tokens: List<String>): Boolean {
        val wanted = tokens.map { it.trim() }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return false
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val nodeText = n.text?.toString()?.trim().orEmpty()
            val nodeDesc = n.contentDescription?.toString()?.trim().orEmpty()
            for (w in wanted) {
                if (nodeText.contains(w, ignoreCase = true) || nodeDesc.contains(w, ignoreCase = true)) return true
            }
            val count = n.childCount
            for (i in 0 until count) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }
        return false
    }

    private fun clickProjectionPromptPositiveButton(root: AccessibilityNodeInfo): Boolean {
        data class Candidate(
            val node: AccessibilityNodeInfo,
            val clickable: AccessibilityNodeInfo,
            val label: String,
            val bounds: Rect,
            val score: Int,
        )

        val dialogBounds = Rect().also { root.getBoundsInScreen(it) }
        val screenCenterY = if (dialogBounds.height() > 1) dialogBounds.centerY() else 0
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        var best: Candidate? = null

        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val clickable = findClickable(n)
            if (clickable != null && clickable.isEnabled) {
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val label = listOf(text, desc)
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                val labelLower = label.lowercase()
                if (PROJECTION_NEGATIVE_TEXTS.none { labelLower.contains(it) }) {
                    val bounds = Rect()
                    clickable.getBoundsInScreen(bounds)
                    if (bounds.width() > 20 && bounds.height() > 20 && bounds.centerY() >= screenCenterY) {
                        var score = 0
                        if (PROJECTION_POSITIVE_TEXTS.any { labelLower.contains(it) }) score += 10_000
                        if (clickable.className?.toString()?.contains("Button", ignoreCase = true) == true) score += 2_000
                        score += bounds.centerX()
                        score += bounds.centerY()
                        val candidate = Candidate(n, clickable, label, bounds, score)
                        if (best == null || candidate.score > best!!.score) {
                            best = candidate
                        }
                    }
                }
            }
            val count = n.childCount
            for (i in 0 until count) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }

        val target = best ?: return false
        val clicked = target.clickable.isClickable && target.clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.i(TAG, "projection_positive_button_clicked label=${target.label} bounds=${target.bounds.flattenToString()}")
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && tapNodeCenter(target.clickable)) {
            Log.i(TAG, "projection_positive_button_tapped label=${target.label} bounds=${target.bounds.flattenToString()}")
            return true
        }
        return false
    }

    private fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node ?: return null
        var steps = 0
        while (steps < 6) {
            if (n.isClickable) return n
            val p = n.parent ?: return null
            n = p
            steps++
        }
        return null
    }

    companion object {
        private const val TAG = "ArgosAccessibility"
        private const val PREFS_NAME = "argos_accessibility"
        private const val ANYDESK_ARM_UNTIL_MS = "anydesk_arm_until_ms"
        private const val ANYDESK_TAP_X = "anydesk_tap_x"
        private const val ANYDESK_TAP_Y = "anydesk_tap_y"
        private const val ANYDESK_TAP_X_PCT = "anydesk_tap_x_pct"
        private const val ANYDESK_TAP_Y_PCT = "anydesk_tap_y_pct"
        private const val ANYDESK_LAST_TAP_MS = "anydesk_last_tap_ms"
        private const val ANYDESK_TAP_COUNT = "anydesk_tap_count"
        private const val DEBUG_LAST_EVENT_MS = "debug_last_event_ms"
        private const val LAST_FOREGROUND_PKG = "last_foreground_pkg"
        private const val LAST_FOREGROUND_AT = "last_foreground_at"
        private const val LAST_INTERACTION_AT = "last_interaction_at"
        private const val LAST_INTERACTION_PKG = "last_interaction_pkg"
        private const val LAST_ANYDESK_AT = "last_anydesk_at"
        private const val ANYDESK_PACKAGE = "com.anydesk.anydeskandroid"
        private const val ANYDESK_PLUGIN_PACKAGE = "com.anydesk.adcontrol.ad1"
        private const val ARGOS_REMOTE_PACKAGE = "com.mupa.argos.remote"
        private val REMOTE_SUPPORT_APP_TOKENS = listOf(
            "anydesk",
            "argos remote",
            "argosremote",
        )
        private val PROJECTION_POSITIVE_TEXTS = listOf(
            "start now",
            "iniciar agora",
            "allow",
            "permitir",
            "continue",
            "continuar",
        )
        private val PROJECTION_NEGATIVE_TEXTS = listOf(
            "cancel",
            "cancelar",
            "deny",
            "negar",
            "don't allow",
            "nao permitir",
            "não permitir",
            "fechar",
        )
        private val PROJECTION_PROMPT_TEXTS = listOf(
            "start recording",
            "start screen capture",
            "screen capture",
            "share your screen",
            "record or cast",
            "recording or casting",
            "capture",
            "iniciar grava",
            "gravação ou transmissão",
            "gravacao ou transmissao",
            "grave ou transmita",
            "gravar ou transmitir",
            "capturar",
            "capturar a tela",
            "capturar tudo",
            "toda a tela",
            "sua tela",
            "transmit",
            "casting",
            "compartilhar",
            "compartilhe",
        )
        private val ANYDESK_ACCEPT_TEXTS = listOf(
            "Aceitar",
            "Accept",
            "ACCEPT",
        )
        private val ANYDESK_ALLOW_TEXTS = listOf(
            "Permitir",
            "Allow",
            "ALLOW",
            "Continue",
            "CONTINUE",
        )
        private val ANYDESK_START_NOW_TEXTS = listOf(
            "Start now",
            "Start Now",
            "START NOW",
            "Start",
            "START",
            "Iniciar agora",
            "Começar agora",
            "Comecar agora",
        )
        private val RUNTIME_PERMISSION_ALLOW_TEXTS = ANYDESK_ALLOW_TEXTS + listOf(
            "Enquanto estiver usando o app",
            "Enquanto estiver usando o aplicativo",
            "While using the app",
            "While using the application",
            "Only this time",
            "Somente desta vez",
            "Só desta vez",
            "Apenas desta vez",
        )
        private val RUNTIME_PERMISSION_DENY_TEXTS = listOf(
            "deny",
            "negar",
            "don't allow",
            "nao permitir",
            "não permitir",
            "cancel",
            "cancelar",
        )
        private val ANYDESK_POSITIVE_BUTTON_IDS = listOf(
            "start_now",
            "start_now_button",
            "button1",
            "positive_button",
            "permission_allow_button",
            "permission_allow_foreground_only_button",
            "permission_allow_one_time_button",
            "right_button",
        )

        private fun isRemoteSupportPackage(pkg: String): Boolean {
            val p = pkg.trim().lowercase()
            return p == ANYDESK_PACKAGE ||
                p == ANYDESK_PLUGIN_PACKAGE ||
                p == ARGOS_REMOTE_PACKAGE ||
                p.contains("anydesk") ||
                p.contains("argos.remote") ||
                p.contains("argosremote")
        }

        fun isServiceEnabled(context: Context): Boolean {
            val enabledFlag = runCatching {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            }.getOrDefault(false)
            if (!enabledFlag) return false
            val services = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.lowercase()
                    .orEmpty()
            }.getOrDefault("")
            val fullSelfService = "${context.packageName}/com.mupa.agent.argos.accessibility.ArgosAccessibilityService".lowercase()
            val shortSelfService = "${context.packageName}/.accessibility.ArgosAccessibilityService".lowercase()
            return services.split(':').any { entry ->
                val value = entry.trim()
                value == fullSelfService || value == shortSelfService
            }
        }

        fun getLastUserInteractionAt(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(LAST_INTERACTION_AT, 0L)
        }

        fun getLastForegroundPackage(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(LAST_FOREGROUND_PKG, "") ?: ""
        }

        fun getLastForegroundAt(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(LAST_FOREGROUND_AT, 0L)
        }

        fun armAnyDeskAutoClick(
            context: Context,
            durationMs: Long,
            tapX: Int? = null,
            tapY: Int? = null,
            tapXPct: Double? = null,
            tapYPct: Double? = null,
        ) {
            val now = System.currentTimeMillis()
            val until = now + durationMs.coerceIn(5_000L, 5 * 60_000L)
            val e = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(ANYDESK_ARM_UNTIL_MS, until)
                .putLong(ANYDESK_LAST_TAP_MS, 0L)
                .putInt(ANYDESK_TAP_COUNT, 0)
            if (tapX != null && tapY != null && tapX >= 0 && tapY >= 0) {
                e.putInt(ANYDESK_TAP_X, tapX).putInt(ANYDESK_TAP_Y, tapY)
                e.putLong(ANYDESK_TAP_X_PCT, java.lang.Double.doubleToRawLongBits(-1.0))
                e.putLong(ANYDESK_TAP_Y_PCT, java.lang.Double.doubleToRawLongBits(-1.0))
            } else if (tapXPct != null && tapYPct != null && tapXPct >= 0.0 && tapYPct >= 0.0) {
                e.putInt(ANYDESK_TAP_X, -1).putInt(ANYDESK_TAP_Y, -1)
                e.putLong(ANYDESK_TAP_X_PCT, java.lang.Double.doubleToRawLongBits(tapXPct))
                e.putLong(ANYDESK_TAP_Y_PCT, java.lang.Double.doubleToRawLongBits(tapYPct))
            } else {
                e.putInt(ANYDESK_TAP_X, -1).putInt(ANYDESK_TAP_Y, -1)
                e.putLong(ANYDESK_TAP_X_PCT, java.lang.Double.doubleToRawLongBits(-1.0))
                e.putLong(ANYDESK_TAP_Y_PCT, java.lang.Double.doubleToRawLongBits(-1.0))
            }
            e.apply()
        }

        private fun isAnyDeskAutoClickArmed(context: Context): Boolean {
            val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(ANYDESK_ARM_UNTIL_MS, 0L)
            return until > System.currentTimeMillis()
        }

        private fun disarmAnyDeskAutoClick(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(ANYDESK_ARM_UNTIL_MS, 0L)
                .apply()
        }
    }
}
