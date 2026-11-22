package com.privateai.jarvis

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.privateai.jarvis.data.VaultDatabase
import com.privateai.jarvis.ui.LauncherScreen
import com.privateai.jarvis.ui.LockScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : FragmentActivity() {

    companion object {
        const val REQ_CODE_PICK_MODEL = 1009

        private val _pickedModelUri = MutableStateFlow<Uri?>(null)
        val pickedModelUri = _pickedModelUri.asStateFlow()

        fun consumePickedModelUri() {
            _pickedModelUri.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val app = application as JarvisApp
        val securityManager = app.securityManager

        setContent {
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_PICK_MODEL && resultCode == Activity.RESULT_OK) {
            _pickedModelUri.value = data?.data
        }
    }
}
