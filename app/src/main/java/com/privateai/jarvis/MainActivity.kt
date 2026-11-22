package com.privateai.jarvis

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.privateai.jarvis.data.VaultDatabase
import com.privateai.jarvis.ui.LauncherScreen
import com.privateai.jarvis.ui.LockScreen

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots and hide app content from Android Recents preview
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val app = application as JarvisApp
        val securityManager = app.securityManager

        setContent {
            // Prevent Back button from exiting a Home Launcher
            BackHandler(enabled = true) { }

            val isUnlocked by securityManager.isUnlocked.collectAsState()

            if (!isUnlocked) {
                LockScreen(securityManager = securityManager)
            } else {
                val database = remember {
                    VaultDatabase.getInstance(applicationContext, securityManager)
                }
                LauncherScreen(
                    securityManager = securityManager,
                    database = database
                )
            }
        }
    }
}
