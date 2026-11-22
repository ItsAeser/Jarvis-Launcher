package com.privateai.jarvis.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class AppLockObserver(
    private val securityManager: SecurityManager
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        // Auto-lock immediately when user leaves the app or turns off the screen
        securityManager.lock()
    }
}
