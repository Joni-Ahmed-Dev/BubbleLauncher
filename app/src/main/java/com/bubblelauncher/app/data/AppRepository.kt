package com.bubblelauncher.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppRepository {
    suspend fun loadLaunchable(ctx: Context): List<BubbleItem> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { BubbleItem(it.first, it.second) }
    }
}

object IconCache {
    private val cache = LruCache<String, Bitmap>(96)

    fun get(ctx: Context, pkg: String): Bitmap? {
        cache.get(pkg)?.let { return it }
        return try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = d.toBitmap(144, 144)
            cache.put(pkg, b)
            b
        } catch (e: Exception) {
            null
        }
    }
}
