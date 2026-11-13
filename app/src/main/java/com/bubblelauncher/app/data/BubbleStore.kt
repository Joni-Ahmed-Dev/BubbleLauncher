package com.bubblelauncher.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class BubbleMode { ALL, SELECTED, NOTHING }

data class BubbleItem(val pkg: String, val label: String, val enabled: Boolean = true)

data class BubbleSettings(
    val mode: BubbleMode = BubbleMode.ALL,
    val sizeDp: Int = 56,
    val opacity: Float = 1f,
    val snapToEdge: Boolean = true,
    val haptics: Boolean = true,
    val showBadges: Boolean = true,
    val floatingWindow: Boolean = true,
    val autoStart: Boolean = false
)

object BubbleStore {
    private lateinit var prefs: SharedPreferences

    private val _bubbles = MutableStateFlow<List<BubbleItem>>(emptyList())
    val bubbles: StateFlow<List<BubbleItem>> = _bubbles.asStateFlow()

    private val _recent = MutableStateFlow<List<BubbleItem>>(emptyList())
    val recent: StateFlow<List<BubbleItem>> = _recent.asStateFlow()

    private val _settings = MutableStateFlow(BubbleSettings())
    val settings: StateFlow<BubbleSettings> = _settings.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _badges = MutableStateFlow<Set<String>>(emptySet())
    val badges: StateFlow<Set<String>> = _badges.asStateFlow()

    private val _layoutNonce = MutableStateFlow(0)
    val layoutNonce: StateFlow<Int> = _layoutNonce.asStateFlow()

    private val positions = HashMap<String, IntArray>()

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("bubble_store", Context.MODE_PRIVATE)
        _bubbles.value = readItems("bubbles")
        _recent.value = readItems("recent")
        _settings.value = BubbleSettings(
            mode = runCatching { BubbleMode.valueOf(prefs.getString("mode", "ALL")!!) }.getOrDefault(BubbleMode.ALL),
            sizeDp = prefs.getInt("size", 56),
            opacity = prefs.getFloat("opacity", 1f),
            snapToEdge = prefs.getBoolean("snap", true),
            haptics = prefs.getBoolean("haptics", true),
            showBadges = prefs.getBoolean("badges", true),
            floatingWindow = prefs.getBoolean("floating", true),
            autoStart = prefs.getBoolean("autostart", false)
        )
        runCatching {
            val o = JSONObject(prefs.getString("positions", "{}")!!)
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                positions[k] = intArrayOf(a.getInt(0), a.getInt(1))
            }
        }
    }

    // ---------- bubbles ----------
    fun visible(): List<BubbleItem> = when (_settings.value.mode) {
        BubbleMode.ALL -> _bubbles.value
        BubbleMode.SELECTED -> _bubbles.value.filter { it.enabled }
        BubbleMode.NOTHING -> emptyList()
    }

    fun add(item: BubbleItem) {
        if (_bubbles.value.any { it.pkg == item.pkg }) return
        _bubbles.value = _bubbles.value + item
        _recent.value = _recent.value.filterNot { it.pkg == item.pkg }
        saveItems("bubbles", _bubbles.value)
        saveItems("recent", _recent.value)
    }

    fun remove(pkg: String, toRecent: Boolean = true) {
        val item = _bubbles.value.firstOrNull { it.pkg == pkg } ?: return
        _bubbles.value = _bubbles.value.filterNot { it.pkg == pkg }
        if (toRecent) {
            _recent.value = (listOf(item) + _recent.value.filterNot { it.pkg == pkg }).take(12)
        }
        positions.remove(pkg)
        saveItems("bubbles", _bubbles.value)
        saveItems("recent", _recent.value)
        savePositions()
    }

    fun setEnabled(pkg: String, enabled: Boolean) {
        _bubbles.value = _bubbles.value.map { if (it.pkg == pkg) it.copy(enabled = enabled) else it }
        saveItems("bubbles", _bubbles.value)
    }

    fun restore(pkg: String) {
        val item = _recent.value.firstOrNull { it.pkg == pkg } ?: return
        add(item.copy(enabled = true))
    }

    fun deleteRecent(pkg: String) {
        _recent.value = _recent.value.filterNot { it.pkg == pkg }
        saveItems("recent", _recent.value)
    }

    fun clearRecent() {
        _recent.value = emptyList()
        saveItems("recent", _recent.value)
    }

    // ---------- settings ----------
    fun updateSettings(transform: (BubbleSettings) -> BubbleSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("mode", s.mode.name)
            .putInt("size", s.sizeDp)
            .putFloat("opacity", s.opacity)
            .putBoolean("snap", s.snapToEdge)
            .putBoolean("haptics", s.haptics)
            .putBoolean("badges", s.showBadges)
            .putBoolean("floating", s.floatingWindow)
            .putBoolean("autostart", s.autoStart)
            .apply()
    }

    // ---------- runtime state ----------
    fun setRunning(v: Boolean) { _running.value = v }
    fun setBadges(set: Set<String>) { _badges.value = set }

    // ---------- positions ----------
    fun getPos(key: String): IntArray? = positions[key]

    fun setPos(key: String, x: Int, y: Int) {
        positions[key] = intArrayOf(x, y)
        savePositions()
    }

    fun clearPositions() {
        positions.clear()
        savePositions()
        _layoutNonce.value = _layoutNonce.value + 1
    }

    // ---------- persistence ----------
    private fun readItems(key: String): List<BubbleItem> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]")!!)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            BubbleItem(o.getString("p"), o.getString("l"), o.optBoolean("e", true))
        }
    }.getOrDefault(emptyList())

    private fun saveItems(key: String, items: List<BubbleItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("p", it.pkg).put("l", it.label).put("e", it.enabled)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun savePositions() {
        val o = JSONObject()
        positions.forEach { (k, v) -> o.put(k, JSONArray().put(v[0]).put(v[1])) }
        prefs.edit().putString("positions", o.toString()).apply()
    }
}
