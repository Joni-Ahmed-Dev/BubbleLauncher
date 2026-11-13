package com.bubblelauncher.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bubblelauncher.app.MainActivity
import com.bubblelauncher.app.R
import com.bubblelauncher.app.data.BubbleMode
import com.bubblelauncher.app.data.BubbleItem
import com.bubblelauncher.app.data.BubbleStore
import com.bubblelauncher.app.data.IconCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Floating bubble overlay. Every bubble is its own small overlay window so that
 * touches outside the bubbles always reach the apps underneath.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.bubblelauncher.app.STOP"
        private const val CHANNEL_ID = "bubble_service"
        private const val NOTIF_ID = 1001
        private const val PLUS = "+"
    }

    private class BubbleView(
        val key: String,
        val root: FrameLayout,
        val lp: WindowManager.LayoutParams,
        val badge: View
    )

    private class Metrics(val w: Int, val h: Int, val top: Int, val bottom: Int)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val views = LinkedHashMap<String, BubbleView>()

    private var targetView: FrameLayout? = null
    private var targetCircle: View? = null
    private var targetCx = 0
    private var targetCy = 0
    private var overTarget = false

    private var menuView: View? = null
    private var lastSig = ""
    private var density = 1f

    private fun dp(v: Int) = (v * density).toInt()
    private val pad get() = dp(6)
    private val bubblePx get() = dp(BubbleStore.settings.value.sizeDp)
    private val slot get() = bubblePx + 2 * pad

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BubbleStore.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        createChannel()
        startForeground(NOTIF_ID, buildNotification(0))

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        BubbleStore.setRunning(true)

        scope.launch {
            combine(BubbleStore.bubbles, BubbleStore.settings) { _, _ -> }.collect { rebuild() }
        }
        scope.launch { BubbleStore.badges.collect { updateBadges() } }
        scope.launch {
            BubbleStore.layoutNonce.drop(1).collect {
                removeAllBubbleViews()
                rebuild()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            closeMenu()
            views.values.toList().forEach { settle(it, animate = false) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        closeMenu()
        hideTarget(immediate = true)
        removeAllBubbleViews()
        BubbleStore.setRunning(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ build

    private fun rebuild() {
        closeMenu()
        val s = BubbleStore.settings.value
        val items = BubbleStore.visible()
        val keys: List<String> =
            if (s.mode == BubbleMode.NOTHING) emptyList() else items.map { it.pkg } + PLUS

        val sig = "${s.sizeDp}|${s.opacity}"
        if (sig != lastSig) {
            removeAllBubbleViews()
            lastSig = sig
        }
        views.keys.filter { it !in keys }.toList().forEach { removeBubble(it) }
        keys.forEachIndexed { i, k ->
            if (k !in views) addBubble(k, i, items.firstOrNull { it.pkg == k })
        }
        updateBadges()
        updateNotification(items.size)
    }

    private fun addBubble(key: String, index: Int, item: BubbleItem?) {
        val s = BubbleStore.settings.value
        val size = bubblePx
        val accent = getColor(android.R.color.system_accent1_500)

        val circle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (key == PLUS) accent else Color.WHITE)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(6).toFloat()
        }
        if (key == PLUS) {
            circle.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                setImageResource(R.drawable.ic_add)
                setPadding(size / 4, size / 4, size / 4, size / 4)
            })
        } else {
            circle.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                IconCache.get(this@BubbleService, key)?.let { setImageBitmap(it) }
            })
        }

        val badgeSize = dp(14)
        val badge = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            visibility = View.GONE
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END).apply {
                topMargin = pad - dp(2)
                marginEnd = pad - dp(2)
            }
        }

        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            alpha = s.opacity
            contentDescription = item?.label ?: "Add"
            addView(circle)
            addView(badge)
        }

        val lp = WindowManager.LayoutParams(
            slot, slot,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val m = metrics()
        val saved = BubbleStore.getPos(key)
        if (saved != null) {
            lp.x = saved[0].coerceIn(0, max(0, m.w - slot))
            lp.y = saved[1].coerceIn(m.top, max(m.top, m.h - m.bottom - slot))
        } else {
            val step = bubblePx + dp(8)
            val perRow = max(1, (m.w - dp(16)) / step)
            lp.x = dp(8) - pad + (index % perRow) * step
            lp.y = m.top + dp(8) - pad + (index / perRow) * step
        }

        val bv = BubbleView(key, root, lp, badge)
        root.setOnTouchListener(DragHandler(bv))
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            return
        }
        views[key] = bv

        root.scaleX = 0f
        root.scaleY = 0f
        root.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun removeBubble(key: String) {
        views.remove(key)?.let { removeViewSafe(it.root) }
    }

    private fun removeAllBubbleViews() {
        views.values.toList().forEach { removeViewSafe(it.root) }
        views.clear()
    }

    private fun removeViewSafe(v: View?) {
        if (v == null) return
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun updateLayout(bv: BubbleView) {
        try { wm.updateViewLayout(bv.root, bv.lp) } catch (_: Exception) {}
    }

    private fun updateBadges() {
        val s = BubbleStore.settings.value
        val set = BubbleStore.badges.value
        views.values.forEach {
            if (it.key != PLUS) {
                it.badge.visibility = if (s.showBadges && it.key in set) View.VISIBLE else View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun metrics(): Metrics {
        val m = wm.currentWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        return Metrics(b.width(), b.height(), ins.top, ins.bottom)
    }

    private fun avoidOverlap(self: BubbleView, tx: Int, ty: Int, minY: Int, maxY: Int): Int {
        var y = ty
        var guard = 0
        while (guard++ < 40 && views.values.any {
                it !== self && abs(it.lp.x - tx) < slot * 0.7 && abs(it.lp.y - y) < slot * 0.85
            }) {
            y += (slot * 0.9).toInt()
            if (y > maxY) y = minY
        }
        return y
    }

    private fun settle(bv: BubbleView, animate: Boolean = true) {
        val m = metrics()
        val s = BubbleStore.settings.value
        val minY = m.top
        val maxY = max(minY, m.h - m.bottom - slot)
        var tx = bv.lp.x.coerceIn(0, max(0, m.w - slot))
        if (s.snapToEdge) tx = if (bv.lp.x + slot / 2 < m.w / 2) 0 else m.w - slot
        var ty = bv.lp.y.coerceIn(minY, maxY)
        ty = avoidOverlap(bv, tx, ty, minY, maxY)

        BubbleStore.setPos(bv.key, tx, ty)
        val fx = bv.lp.x
        val fy = bv.lp.y
        if (!animate) {
            bv.lp.x = tx; bv.lp.y = ty; updateLayout(bv); return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                bv.lp.x = (fx + (tx - fx) * t).toInt()
                bv.lp.y = (fy + (ty - fy) * t).toInt()
                updateLayout(bv)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.lp.x = tx; bv.lp.y = ty; updateLayout(bv)
                }
            })
        }.start()
    }

    // ------------------------------------------------------------------ dismiss target

    private fun showTarget() {
        if (targetView != null) return
        val m = metrics()
        val t = dp(88)
        val circleSize = dp(64)
        val circle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(circleSize, circleSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62B2930.toInt())
                setStroke(dp(2), 0x55FFFFFF)
            }
            elevation = dp(6).toFloat()
            addView(ImageView(this@BubbleService).apply {
                layoutParams = FrameLayout.LayoutParams(circleSize, circleSize)
                setImageResource(R.drawable.ic_close)
                setPadding(dp(20), dp(20), dp(20), dp(20))
            })
        }
        val root = FrameLayout(this).apply {
            alpha = 0f
            addView(circle)
        }
        val lp = WindowManager.LayoutParams(
            t, t,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = m.w / 2 - t / 2
            y = m.h - m.bottom - dp(120) - t / 2
        }
        targetCx = m.w / 2
        targetCy = lp.y + t / 2
        try { wm.addView(root, lp) } catch (e: Exception) { return }
        targetView = root
        targetCircle = circle
        root.translationY = dp(40).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    private fun hideTarget(immediate: Boolean = false) {
        val v = targetView ?: return
        targetView = null
        targetCircle = null
        overTarget = false
        if (immediate) { removeViewSafe(v); return }
        v.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(150)
            .withEndAction { removeViewSafe(v) }.start()
    }

    private fun checkTarget(bv: BubbleView) {
        val cx = bv.lp.x + slot / 2
        val cy = bv.lp.y + slot / 2
        val near = hypot((cx - targetCx).toDouble(), (cy - targetCy).toDouble()) < dp(70)
        if (near != overTarget) {
            overTarget = near
            targetCircle?.animate()?.scaleX(if (near) 1.25f else 1f)?.scaleY(if (near) 1.25f else 1f)
                ?.setDuration(120)?.start()
            bv.root.animate().scaleX(if (near) 0.65f else 0.9f).scaleY(if (near) 0.65f else 0.9f)
                .setDuration(120).start()
            if (near) haptic(bv.root, HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    // ------------------------------------------------------------------ touch

    private inner class DragHandler(val bv: BubbleView) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longFired = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val longRunnable = Runnable {
            if (!dragging) {
                longFired = true
                haptic(bv.root, HapticFeedbackConstants.LONG_PRESS)
                showMenu(bv)
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bv.lp.x; startY = bv.lp.y
                    dragging = false; longFired = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    handler.postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longRunnable)
                        closeMenu()
                        if (bv.key != PLUS) showTarget()
                    }
                    if (dragging) {
                        bv.lp.x = startX + dx.toInt()
                        bv.lp.y = startY + dy.toInt()
                        updateLayout(bv)
                        if (bv.key != PLUS) checkTarget(bv)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    val removed = dragging && overTarget && bv.key != PLUS
                    if (!removed) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                    if (dragging) {
                        hideTarget()
                        if (removed) {
                            v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160)
                                .withEndAction { BubbleStore.remove(bv.key) }.start()
                        } else {
                            settle(bv)
                        }
                    } else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                        onTap(bv)
                    }
                    overTarget = false
                }
            }
            return true
        }
    }

    private fun haptic(v: View, type: Int) {
        if (BubbleStore.settings.value.haptics) v.performHapticFeedback(type)
    }

    // ------------------------------------------------------------------ actions

    private fun onTap(bv: BubbleView) {
        haptic(bv.root, HapticFeedbackConstants.VIRTUAL_KEY)
        closeMenu()
        if (bv.key == PLUS) openManage(picker = true) else launchApp(bv.key)
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "অ্যাপটি খোলা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = if (BubbleStore.settings.value.floatingWindow) {
            val m = metrics()
            val w = (m.w * 0.9).toInt()
            val h = (m.h * 0.62).toInt()
            val l = (m.w - w) / 2
            val t = m.top + dp(72)
            ActivityOptions.makeBasic().setLaunchBounds(Rect(l, t, l + w, t + h)).toBundle()
        } else null
        try {
            startActivity(intent, opts)
        } catch (e: Exception) {
            Toast.makeText(this, "অ্যাপটি খোলা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openManage(picker: Boolean) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_PICKER, picker)
        try { startActivity(i) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ long-press menu

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun showMenu(bv: BubbleView) {
        closeMenu()
        val dark = isDark()
        val bg = if (dark) 0xFF2B2930.toInt() else Color.WHITE
        val fg = if (dark) 0xFFE6E0E9.toInt() else 0xFF1D1B20.toInt()
        val danger = 0xFFE53935.toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(10).toFloat()
            setPadding(0, dp(8), 0, dp(8))
        }

        fun title(text: String) {
            panel.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(fg)
                alpha = 0.6f
                maxLines = 1
                setPadding(dp(18), dp(8), dp(18), dp(8))
            })
        }

        fun row(text: String, color: Int = fg, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(color)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(46)
                setPadding(dp(18), 0, dp(18), 0)
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22888888), null, ColorDrawable(Color.WHITE)
                )
                setOnClickListener { closeMenu(); action() }
            })
        }

        if (bv.key == PLUS) {
            title("Bubble Launcher")
            row("অ্যাপ যোগ করুন") { openManage(true) }
            row("বাবল গুছিয়ে রাখুন") { BubbleStore.clearPositions() }
            row("ম্যানেজ") { openManage(false) }
            row("বন্ধ করুন", danger) { stopSelf() }
        } else {
            val label = BubbleStore.bubbles.value.firstOrNull { it.pkg == bv.key }?.label ?: bv.key
            title(label)
            row("খুলুন") { launchApp(bv.key) }
            row("অ্যাপের তথ্য") {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${bv.key}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(i) } catch (_: Exception) {}
            }
            row("ম্যানেজ") { openManage(false) }
            row("বাবল সরান", danger) { BubbleStore.remove(bv.key) }
        }

        val wrap = FrameLayout(this).apply {
            clipChildren = false
            setPadding(dp(12), dp(8), dp(12), dp(16))
            addView(panel)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
            }
        }

        val m = metrics()
        val menuW = dp(230)
        wrap.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuH = wrap.measuredHeight
        val lp = WindowManager.LayoutParams(
            menuW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bv.lp.x - dp(12)).coerceIn(dp(4), max(dp(4), m.w - menuW - dp(4)))
            val below = bv.lp.y + slot - pad
            y = if (below + menuH < m.h - m.bottom) below else max(m.top, bv.lp.y + pad - menuH)
        }
        try { wm.addView(wrap, lp) } catch (e: Exception) { return }
        menuView = wrap
        wrap.pivotX = 0f
        wrap.pivotY = 0f
        wrap.alpha = 0f
        wrap.scaleX = 0.85f
        wrap.scaleY = 0.85f
        wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun closeMenu() {
        removeViewSafe(menuView)
        menuView = null
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Bubble Launcher", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(count: Int): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bubble)
            .setContentTitle("Bubble Launcher চালু আছে")
            .setContentText(if (count > 0) "$count টি বাবল সক্রিয়" else "বাবলে অ্যাপ যোগ করতে + চাপুন")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close), "বন্ধ করুন", stop
                ).build()
            )
            .build()
    }

    private fun updateNotification(count: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(count))
    }
}
