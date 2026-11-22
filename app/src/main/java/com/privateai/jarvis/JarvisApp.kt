package com.privateai.jarvis

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.privateai.jarvis.security.AppLockObserver
import com.privateai.jarvis.security.SecurityManager

class JarvisApp : Application() {
    lateinit var securityManager: SecurityManager
        private set

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        securityManager = SecurityManager(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLockObserver(securityManager))

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                securityManager.saveLastCrashTrace(trace.take(3500))
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
