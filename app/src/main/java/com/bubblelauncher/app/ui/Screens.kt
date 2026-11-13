@file:OptIn(ExperimentalMaterial3Api::class)

package com.bubblelauncher.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bubblelauncher.app.data.BubbleItem
import com.bubblelauncher.app.data.BubbleMode
import com.bubblelauncher.app.data.BubbleStore
import kotlin.math.roundToInt

// ============================================================ HOME

@Composable
fun HomeTab(
    running: Boolean,
    perms: Perms,
    padding: PaddingValues,
    onToggle: (Boolean) -> Unit,
    onGrantOverlay: () -> Unit,
    onAdd: () -> Unit,
    onDelete: (BubbleItem) -> Unit
) {
    val bubbles by BubbleStore.bubbles.collectAsStateWithLifecycle()
    val settings by BubbleStore.settings.collectAsStateWithLifecycle()
    val badges by BubbleStore.badges.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HeroCard(running, BubbleStore.visible().size, bubbles, onToggle) }

        if (!perms.overlay) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                "ওভারলে অনুমতি দরকার",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "অন্য অ্যাপের ওপরে বাবল দেখাতে এই অনুমতি আবশ্যক।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Button(
                            onClick = onGrantOverlay,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("দিন") }
                    }
                }
            }
        }

        item { SectionTitle("বাবল মোড") }
        item {
            val modes = listOf(
                BubbleMode.ALL to "সব",
                BubbleMode.SELECTED to "নির্বাচিত",
                BubbleMode.NOTHING to "কিছুই না"
            )
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (m, label) ->
                        SegmentedButton(
                            selected = settings.mode == m,
                            onClick = { BubbleStore.updateSettings { it.copy(mode = m) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (settings.mode) {
                        BubbleMode.ALL -> "যোগ করা সব অ্যাপ বাবল হিসেবে দেখাবে"
                        BubbleMode.SELECTED -> "শুধু সুইচ চালু থাকা অ্যাপগুলো বাবল হবে"
                        BubbleMode.NOTHING -> "কোনো বাবল দেখাবে না (সাময়িক বন্ধ)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        item { SectionTitle("আপনার বাবল (${bubbles.size})") }

        if (bubbles.isEmpty()) {
            item {
                EmptyState(
                    "এখনও কোনো বাবল নেই",
                    "অ্যাপ যোগ করুন, সেগুলো স্ক্রিনে ভাসমান বাবল হয়ে থাকবে",
                    "অ্যাপ যোগ করুন",
                    onAdd
                )
            }
        } else {
            items(bubbles, key = { it.pkg }) { item ->
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(item.pkg, 48.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            if (item.pkg in badges) {
                                Text(
                                    "নতুন নোটিফিকেশন",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { BubbleStore.setEnabled(item.pkg, it) },
                            enabled = settings.mode != BubbleMode.NOTHING
                        )
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = "সরান")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(running: Boolean, count: Int, preview: List<BubbleItem>, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
            .padding(22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Bubble Launcher",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = cs.onPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (running) "চালু আছে • $count টি বাবল" else "এখন বন্ধ আছে",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onPrimary.copy(alpha = 0.85f)
                    )
                }
                Switch(
                    checked = running,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = cs.primary,
                        checkedTrackColor = cs.onPrimary,
                        checkedBorderColor = cs.onPrimary,
                        uncheckedThumbColor = cs.onPrimary.copy(alpha = 0.9f),
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = cs.onPrimary.copy(alpha = 0.7f)
                    )
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                preview.take(5).forEach { AppIcon(it.pkg, 44.dp, Modifier.padding(end = 8.dp)) }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(cs.onPrimary.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, null, tint = cs.onPrimary) }
            }
        }
    }
}

// ============================================================ RECENT

@Composable
fun RecentTab(padding: PaddingValues) {
    val recent by BubbleStore.recent.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("সাম্প্রতিক বাবল", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (recent.isNotEmpty()) TextButton(onClick = { BubbleStore.clearRecent() }) { Text("সব মুছুন") }
            }
        }
        if (recent.isEmpty()) {
            item {
                EmptyState(
                    "কিছু নেই",
                    "সম্প্রতি বন্ধ করা বা সরানো বাবল এখানে দেখা যাবে, চাইলে ফিরিয়ে আনতে পারবেন"
                )
            }
        } else {
            items(recent, key = { it.pkg }) { item ->
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(item.pkg, 48.dp)
                        Text(
                            item.label,
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1
                        )
                        TextButton(onClick = { BubbleStore.restore(item.pkg) }) { Text("ফিরিয়ে আনুন") }
                        IconButton(onClick = { BubbleStore.deleteRecent(item.pkg) }) {
                            Icon(Icons.Default.Close, contentDescription = "মুছুন")
                        }
                    }
                }
            }
        }
    }
}

// ============================================================ SETTINGS

@Composable
fun SettingsTab(perms: Perms, padding: PaddingValues, onRequestNotif: () -> Unit) {
    val ctx = LocalContext.current
    val s by BubbleStore.settings.collectAsStateWithLifecycle()
    val bubbles by BubbleStore.bubbles.collectAsStateWithLifecycle()
    var size by remember(s.sizeDp) { mutableFloatStateOf(s.sizeDp.toFloat()) }
    var opacity by remember(s.opacity) { mutableFloatStateOf(s.opacity) }

    fun launch(i: Intent) {
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("সেটিংস", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            SettingCard("চেহারা") {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val m = Modifier.size(size.dp).alpha(opacity)
                    if (bubbles.isNotEmpty()) {
                        AppIcon(bubbles.first().pkg, size.dp, m)
                    } else {
                        Box(m.clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Text("বাবলের আকার: ${size.roundToInt()} dp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    onValueChangeFinished = { BubbleStore.updateSettings { it.copy(sizeDp = size.roundToInt()) } },
                    valueRange = 40f..80f
                )
                Text("স্বচ্ছতা: ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { BubbleStore.updateSettings { it.copy(opacity = opacity) } },
                    valueRange = 0.4f..1f
                )
            }
        }

        item {
            SettingCard("আচরণ") {
                SettingSwitch("কিনারায় সেঁটে যাক", "ছেড়ে দিলে বাবল স্ক্রিনের বাম বা ডান পাশে চলে যাবে", s.snapToEdge) {
                    BubbleStore.updateSettings { c -> c.copy(snapToEdge = it) }
                }
                SettingSwitch("ভাইব্রেশন", "ট্যাপ, লং-প্রেস ও ডিসমিসে হালকা ফিডব্যাক", s.haptics) {
                    BubbleStore.updateSettings { c -> c.copy(haptics = it) }
                }
                SettingSwitch(
                    "নোটিফিকেশন ডট",
                    if (perms.listener) "নতুন নোটিফিকেশন এলে বাবলে লাল ডট দেখাবে" else "চালু করতে নোটিফিকেশন অ্যাক্সেস দিতে হবে",
                    s.showBadges
                ) {
                    BubbleStore.updateSettings { c -> c.copy(showBadges = it) }
                    if (it && !perms.listener) launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                SettingSwitch(
                    "ছোট ভাসমান উইন্ডোতে খোলার চেষ্টা",
                    "Android ১২-এ এটি কাজ করতে ফ্রি-ফর্ম মোড চালু থাকতে হবে, নইলে অ্যাপ পূর্ণ স্ক্রিনে খুলবে",
                    s.floatingWindow
                ) { BubbleStore.updateSettings { c -> c.copy(floatingWindow = it) } }
                SettingSwitch("ফোন চালু হলে অটো স্টার্ট", null, s.autoStart) {
                    BubbleStore.updateSettings { c -> c.copy(autoStart = it) }
                }
                OutlinedButton(
                    onClick = { BubbleStore.clearPositions() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("বাবলগুলো আবার সারিতে সাজান") }
            }
        }

        item {
            SettingCard("অনুমতি") {
                PermRow("অন্য অ্যাপের ওপরে দেখানো", "বাবল দেখানোর জন্য আবশ্যক", perms.overlay) {
                    launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                PermRow("নোটিফিকেশন", "চলমান সার্ভিসের নোটিফিকেশন দেখাতে", perms.notif, onRequestNotif)
                PermRow("নোটিফিকেশন অ্যাক্সেস", "বাবলে নোটিফিকেশন ডটের জন্য (ঐচ্ছিক)", perms.listener) {
                    launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermRow("ব্যাটারি অপ্টিমাইজেশন বন্ধ", "সার্ভিস যেন ব্যাকগ্রাউন্ডে বন্ধ না হয়", perms.battery) {
                    launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            }
        }

        item {
            SettingCard("ছোট উইন্ডো চালু করার উপায়") {
                Text(
                    "Settings → About phone-এ Build number ৭ বার চাপুন → Developer options খুলে " +
                        "\"Enable freeform windows\" ও \"Force activities to be resizable\" চালু করুন, তারপর ফোন রিস্টার্ট দিন। " +
                        "এরপর বাবল থেকে অ্যাপ ছোট ভাসমান উইন্ডোতে খুলবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
