@file:OptIn(ExperimentalMaterial3Api::class)

package com.bubblelauncher.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bubblelauncher.app.data.BubbleItem
import com.bubblelauncher.app.data.BubbleStore
import com.bubblelauncher.app.service.BubbleService
import kotlinx.coroutines.launch

data class Perms(val overlay: Boolean, val notif: Boolean, val listener: Boolean, val battery: Boolean)

fun readPerms(ctx: Context): Perms {
    val notif = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return Perms(
        overlay = Settings.canDrawOverlays(ctx),
        notif = notif,
        listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
        battery = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    )
}

@Composable
fun AppRoot(openPicker: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var perms by remember { mutableStateOf(readPerms(ctx)) }
    var showPicker by remember { mutableStateOf(false) }
    val running by BubbleStore.running.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = readPerms(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        perms = readPerms(ctx)
    }
    fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun openOverlaySettings() {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        )
    }
    fun toggle(on: Boolean) {
        if (on) {
            if (!Settings.canDrawOverlays(ctx)) { openOverlaySettings(); return }
            if (!perms.notif) requestNotif()
            ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java))
        } else {
            ctx.stopService(Intent(ctx, BubbleService::class.java))
        }
    }
    fun delete(item: BubbleItem) {
        BubbleStore.remove(item.pkg)
        scope.launch {
            val r = snack.showSnackbar(
                "${item.label} সরানো হয়েছে",
                actionLabel = "পূর্বাবস্থা",
                duration = SnackbarDuration.Short
            )
            if (r == SnackbarResult.ActionPerformed) BubbleStore.restore(item.pkg)
        }
    }

    LaunchedEffect(openPicker.value) {
        if (openPicker.value) {
            showPicker = true
            openPicker.value = false
            tab = 0
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showPicker = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("অ্যাপ যোগ করুন") }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("বাবল") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Refresh, null) }, label = { Text("সাম্প্রতিক") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("সেটিংস") }
                )
            }
        }
    ) { inner ->
        when (tab) {
            0 -> HomeTab(
                running = running, perms = perms, padding = inner,
                onToggle = ::toggle, onGrantOverlay = ::openOverlaySettings,
                onAdd = { showPicker = true }, onDelete = ::delete
            )
            1 -> RecentTab(inner)
            else -> SettingsTab(perms, inner, onRequestNotif = ::requestNotif)
        }
    }

    if (showPicker) AppPickerSheet(onDismiss = { showPicker = false })
}
