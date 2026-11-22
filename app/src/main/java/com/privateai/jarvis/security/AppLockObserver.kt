package com.privateai.jarvis.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class AppLockObserver(
    private val securityManager: SecurityManager
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        securityManager.lock()
    }
}
