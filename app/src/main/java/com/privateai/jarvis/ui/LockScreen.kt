package com.privateai.jarvis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.privateai.jarvis.security.SecurityManager

@Composable
fun LockScreen(securityManager: SecurityManager) {
    val context = LocalContext.current
    val isSetupMode = remember { !securityManager.isPinSet() }
    var firstPin by remember { mutableStateOf("") }
    var enteredPin by remember { mutableStateOf("") }
    var statusText by remember {
        mutableStateOf(
            if (isSetupMode) "Create a 4–6 digit secret PIN" else "Enter PIN to unlock J.A.R.V.I.S."
        )
    }
    var isError by remember { mutableStateOf(false) }
    var crashTrace by remember { mutableStateOf(securityManager.getLastCrashTrace()) }

    val triggerBiometric = {
        val activity = context as? FragmentActivity
        if (activity != null && securityManager.isBiometricEnabled()) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        securityManager.unlockViaBiometric()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock J.A.R.V.I.S.")
                .setSubtitle("Verify your identity")
                .setNegativeButtonText("Use PIN")
                .build()
            prompt.authenticate(info)
        }
    }

    LaunchedEffect(Unit) {
        if (!isSetupMode && securityManager.isBiometricEnabled() && crashTrace == null) {
            triggerBiometric()
        }
    }

    if (crashTrace != null) {
        AlertDialog(
            onDismissRequest = {
                securityManager.clearLastCrashTrace()
                crashTrace = null
            },
            title = { Text("Previous Crash Log Captured", color = Color(0xFFF87171)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(crashTrace!!, fontSize = 11.sp, color = Color.White)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JarvisCrash", crashTrace))
                    Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
                    securityManager.clearLastCrashTrace()
                    crashTrace = null
                }) {
                    Text("Copy & Dismiss")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    securityManager.clearLastCrashTrace()
                    crashTrace = null
                }) {
                    Text("Dismiss")
                }
            },
            containerColor = Color(0xFF111827)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E17))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "J.A.R.V.I.S.",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText,
                color = if (isError) Color(0xFFF87171) else Color(0xFF94A3B8),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { index ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(
                                color = if (index < enteredPin.length) Color(0xFF38BDF8) else Color(0xFF1E293B),
                                shape = CircleShape
                            )
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("BIO", "0", "DEL")
            )

            rows.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { key ->
                        KeypadButton(
                            label = key,
                            showBiometric = !isSetupMode && securityManager.isBiometricEnabled(),
                            onClick = {
                                isError = false
                                when (key) {
                                    "DEL" -> if (enteredPin.isNotEmpty()) {
                                        enteredPin = enteredPin.dropLast(1)
                                    }
                                    "BIO" -> triggerBiometric()
                                    else -> {
                                        if (enteredPin.length < 6) {
                                            enteredPin += key
                                        }
                                        if (!isSetupMode && enteredPin.length >= 4) {
                                            if (securityManager.verifyPin(enteredPin)) {
                                                enteredPin = ""
                                            } else if (enteredPin.length == 6) {
                                                isError = true
                                                statusText = "Incorrect PIN"
                                                enteredPin = ""
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (isSetupMode && enteredPin.length >= 4) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (firstPin.isEmpty()) {
                            firstPin = enteredPin
                            enteredPin = ""
                            statusText = "Confirm your PIN"
                        } else if (firstPin == enteredPin) {
                            val canBio = BiometricManager.from(context)
                                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                                BiometricManager.BIOMETRIC_SUCCESS
                            securityManager.setBiometricEnabled(canBio)
                            securityManager.setPin(enteredPin)
                        } else {
                            firstPin = ""
                            enteredPin = ""
                            isError = true
                            statusText = "PINs did not match. Try again."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text(if (firstPin.isEmpty()) "Next" else "Save & Encrypt Vault")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun KeypadButton(
    label: String,
    showBiometric: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = label != "BIO" || showBiometric,
        modifier = Modifier.size(76.dp),
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color(0xFF1E293B),
            contentColor = Color.White
        )
    ) {
        when (label) {
            "DEL" -> Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
            "BIO" -> Icon(Icons.Default.Fingerprint, contentDescription = "Biometric", tint = Color(0xFF38BDF8))
            else -> Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
