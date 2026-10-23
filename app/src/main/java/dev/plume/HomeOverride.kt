package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay installed and enabled: on Google TV 14 Home goes to the highest-priority home
 * screen whatever the default is set to, so without it the setup wizard's RecoveryActivity takes Home (a crash loop),
 * and Gemini's results (movie and show pages) are Google TV launcher pages. So whenever Google TV's home screen
 * appears (Home pressed, the TV waking, Back out of the last app), this opens telepath over it at once.
 * It never sees or filters remote buttons: filtering them sends every press through accessibility, and on TVs that
 * turns a held Volume button into a single step.
 */
class HomeOverride : AccessibilityService() {
    private var wakeRegistered = false
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    stockHomeAllowed = false
                    goHome()
                }
                // Android announces a Home press by closing system dialogs with reason "homekey". While Google TV is
                // already in front (opened from the shortcut, or left there by an update) no new window appears to
                // redirect from; answering the press itself covers that. Elsewhere it just repeats the redirect.
                CLOSE_SYSTEM_DIALOGS -> if (intent.getStringExtra("reason") == "homekey") {
                    stockHomeAllowed = false
                    goHome()
                }
            }
        }
    }

    override fun onServiceConnected() {
        running = this
        if (!wakeRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(CLOSE_SYSTEM_DIALOGS) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(wakeReceiver, filter)
            }
            wakeRegistered = true
        }
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (stockHomeAllowed) return // opened on purpose from the Google TV shortcut
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.packageName == GOOGLE_TV &&
            e.className?.toString() == "$GOOGLE_TV.home.HomeActivity"
        ) goHome()
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() = startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (wakeRegistered) {
            unregisterReceiver(wakeReceiver)
            wakeRegistered = false
        }
        running = null
        super.onDestroy()
    }

    companion object {
        /** The bound service, so the launcher's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set

        /**
         * Set by the Google TV shortcut: Google TV's own home stays on screen (and Back into it isn't covered)
         * until Home is pressed, the TV wakes, or telepath shows again.
         */
        @Volatile
        var stockHomeAllowed = false
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val CLOSE_SYSTEM_DIALOGS = "android.intent.action.CLOSE_SYSTEM_DIALOGS"
    }
}
