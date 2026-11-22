package com.privateai.jarvis.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privateai.jarvis.data.ActionLogEntity
import com.privateai.jarvis.data.PinnedAppEntity
import com.privateai.jarvis.data.VaultDatabase
import com.privateai.jarvis.launcher.AppRepository
import com.privateai.jarvis.launcher.InstalledApp
import com.privateai.jarvis.security.SecurityManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    securityManager: SecurityManager,
    database: VaultDatabase
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appRepo = remember { AppRepository(context) }
    val dao = remember { database.vaultDao() }

    var allApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showHyperOsSheet by remember { mutableStateOf(false) }

    val pinnedEntities by dao.observePinnedApps().collectAsState(initial = emptyList())
    val pinnedPackages = remember(pinnedEntities) { pinnedEntities.map { it.packageName }.toSet() }

    LaunchedEffect(Unit) {
        allApps = appRepo.getInstalledApps()
    }

    val filteredApps = remember(allApps, searchQuery, pinnedPackages) {
        val matching = if (searchQuery.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        matching.sortedWith(
            compareByDescending<InstalledApp> { pinnedPackages.contains(it.packageName) }
                .thenBy { it.label.lowercase() }
        )
    }

    Scaffold(
        containerColor = Color(0xFF0A0E17),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "J.A.R.V.I.S. VAULT",
                        color = Color(0xFF38BDF8),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "SQLCipher AES-256 Active • Long-press app to pin",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }

                Row {
                    IconButton(onClick = { showHyperOsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Device Setup",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                    IconButton(onClick = { securityManager.lock() }) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock Now",
                            tint = Color(0xFFF87171)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${allApps.size} apps...", color = Color(0xFF64748B)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF111827),
                    unfocusedContainerColor = Color(0xFF111827)
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isPinned = pinnedPackages.contains(app.packageName)
                    AppTile(
                        app = app,
                        isPinned = isPinned,
                        onLaunch = {
                            if (appRepo.launchApp(app.packageName)) {
                                scope.launch {
                                    dao.insertLog(
                                        ActionLogEntity(
                                            toolName = "launch_app",
                                            summary = "Launched ${app.label}",
                                            status = "EXECUTED"
                                        )
                                    )
                                }
                            }
                        },
                        onTogglePin = {
                            scope.launch {
                                if (isPinned) {
                                    dao.unpinApp(app.packageName)
                                } else {
                                    dao.pinApp(PinnedAppEntity(app.packageName))
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showHyperOsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHyperOsSheet = false },
            containerColor = Color(0xFF111827)
        ) {
            HyperOsSetupSheet(context = context)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: InstalledApp,
    isPinned: Boolean,
    onLaunch: () -> Unit,
    onTogglePin: () -> Unit
) {
    val bitmap = remember(app.packageName) { drawableToBitmap(app.icon) }

    Column(
        modifier = Modifier
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = onTogglePin
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.size(52.dp)
            )
            if (isPinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.TopEnd)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HyperOsSetupSheet(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isBatteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "HyperOS 3 & Android 16 Setup",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("1. Set J.A.R.V.I.S. as Default Home Launcher")
        }

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isBatteryExempt) "2. Battery Unrestricted (Done)" else "2. Disable Battery Optimization")
        }

        Button(
            onClick = {
                try {
                    val intent = Intent().apply {
                        component = ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("3. Enable HyperOS Autostart")
        }

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("4. Open App Info (Allow Restricted Settings)")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(144)
    val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(144)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
