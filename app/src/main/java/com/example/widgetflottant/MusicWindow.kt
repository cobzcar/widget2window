package com.example.widgetflottant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Lecteur musique flottant : pochette, boutons (précédent, -10 s, lecture/pause, +10 s, suivant), titre et artiste.
 * Il suit la lecture en cours de n'importe quelle appli de musique (Poweramp, Spotify, YouTube Music…).
 * - Un appui sur la pochette ouvre l'appli de musique.
 * - Les commandes de déplacement/fermeture/redimensionnement se masquent 0,7 s après le dernier toucher.
 * - Un appui sur la barre réduit la fenêtre à la seule barre ; un second appui la rouvre.
 */
class MusicWindow(
    private val context: Context,
    private val wm: WindowManager,
    private val onClose: () -> Unit
) {
    private companion object {
        const val HIDE_DELAY_MS = 700L
        const val SEEK_MS = 10_000L
        const val PREFS = "music_window"
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val barH = dp(30)
    private val handleSize = dp(36)
    private val minW = dp(200)
    private val minH = dp(260)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var collapsed = prefs.getBoolean("collapsed", false)
    private var fullH = prefs.getInt("h", dp(400))

    private val params = WindowManager.LayoutParams(
        prefs.getInt("w", dp(280)),
        if (collapsed) barH else fullH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("x", dp(40))
        y = prefs.getInt("y", dp(60))
    }

    // ---------- Vues ----------

    private val controls = mutableListOf<View>()
    private val hideRunnable = Runnable { hideControls() }

    private val root: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> showControls()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleHide()
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var playPause: ImageView
    private lateinit var gripView: TextView
    private lateinit var contentView: View
    private lateinit var handleView: View

    // ---------- Lecture en cours ----------

    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private var controller: MediaController? = null
    private var artToken = 0
    private var attached = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = updatePlayIcon(state)
        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            pick(safeSessions())
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list)
    }

    init {
        build()
    }

    private fun screenW() = context.resources.displayMetrics.widthPixels
    private fun screenH() = context.resources.displayMetrics.heightPixels

    private fun build() {
        GlassStyle.apply(context, wm, params, root, dp(18).toFloat(), dp(1))
        root.clipToOutline = true

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), barH + dp(4), dp(14), dp(12))
        }

        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x33FFFFFF)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            // Un appui sur la pochette ouvre l'appli de musique
            setOnClickListener { openPlayerApp() }
        }
        content.addView(artView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Boutons de lecture
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(icon: Int, big: Boolean, action: () -> Unit): ImageView {
            val size = if (big) dp(60) else dp(44)
            val b = ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(0xFFE8E8E8.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { action() }
            }
            row.addView(b, LinearLayout.LayoutParams(0, size, 1f))
            return b
        }
        button(android.R.drawable.ic_media_previous, false) { controller?.transportControls?.skipToPrevious() }
        button(android.R.drawable.ic_media_rew, false) { seekBy(-SEEK_MS) }
        playPause = button(android.R.drawable.ic_media_play, true) { togglePlay() }
        button(android.R.drawable.ic_media_ff, false) { seekBy(SEEK_MS) }
        button(android.R.drawable.ic_media_next, false) { controller?.transportControls?.skipToNext() }
        content.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        }
        subtitleView = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(titleView)
        content.addView(subtitleView)

        contentView = content
        root.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Barre du haut : déplacer / réduire + fermer (se masque)
        gripView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x55000000)
            addView(gripView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(close, LinearLayout.LayoutParams(barH, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP))

        // Poignée de redimensionnement (se masque)
        val resizeHandle = TextView(context).apply {
            text = "◢"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(6), dp(2))
            setBackgroundColor(0x44000000)
        }
        root.addView(
            resizeHandle,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END)
        )
        handleView = resizeHandle

        controls.add(bar)
        controls.add(resizeHandle)
        attachDrag(gripView)
        attachResize(resizeHandle)

        showMessage("Aucune lecture en cours", "Lance une musique sur la box")
        applyCollapsed()
    }

    // ---------- Réduction à la barre ----------

    private fun applyCollapsed() {
        contentView.visibility = if (collapsed) View.GONE else View.VISIBLE
        handleView.visibility = if (collapsed) View.GONE else View.VISIBLE
        gripView.text = if (collapsed) "≡  agrandir" else "≡  déplacer / réduire"
        params.height = if (collapsed) barH else fullH
        update()
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        applyCollapsed()
        persist()
        showControls()
        scheduleHide()
    }

    // ---------- Ouvrir l'appli de musique ----------

    private fun openPlayerApp() {
        val c = controller ?: return
        try {
            val pending = c.sessionActivity
            if (pending != null) {
                pending.send()
                return
            }
        } catch (_: Exception) {
            // on essaie l'autre méthode
        }
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(c.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Données du lecteur ----------

    private fun safeSessions(): List<MediaController>? = try {
        msm.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        null
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (best != null && best.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        if (best == null) {
            showMessage("Aucune lecture en cours", "Lance une musique sur la box")
            updatePlayIcon(null)
            return
        }
        best.registerCallback(callback, main)
        updateMetadata(best.metadata)
        updatePlayIcon(best.playbackState)
    }

    private fun showMessage(title: String, subtitle: String) {
        titleView.text = title
        subtitleView.text = subtitle
        artView.setImageDrawable(null)
    }

    private fun updateMetadata(md: MediaMetadata?) {
        if (md == null) {
            showMessage("Aucune lecture en cours", "Lance une musique sur la box")
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: "Titre inconnu"
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        titleView.text = title
        subtitleView.text = listOfNotNull(artist, album).joinToString(" – ")

        val bitmap = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            artToken++
            artView.setImageBitmap(bitmap)
            return
        }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri != null) loadArt(uri) else artView.setImageDrawable(null)
    }

    /** Charge une pochette donnée par adresse (content://, file:// ou http(s)://), hors du thread principal. */
    private fun loadArt(uri: String) {
        val token = ++artToken
        thread {
            val bmp: Bitmap? = try {
                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
                null
            }
            main.post { if (token == artToken) artView.setImageBitmap(bmp) }
        }
    }

    private fun updatePlayIcon(state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        playPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val s = c.playbackState ?: return
        val position = if (s.state == PlaybackState.STATE_PLAYING) {
            s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        } else {
            s.position
        }
        c.transportControls.seekTo(maxOf(0L, position + deltaMs))
    }

    // ---------- Masquage des commandes ----------

    private fun showControls() {
        root.removeCallbacks(hideRunnable)
        controls.forEach { v ->
            if (collapsed && v === handleView) return@forEach
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun scheduleHide() {
        root.removeCallbacks(hideRunnable)
        root.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun hideControls() {
        // Réduite, la barre reste visible pour pouvoir rouvrir la fenêtre.
        if (collapsed) return
        controls.forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    // ---------- Déplacement, appui (réduire) et redimensionnement ----------

    private fun attachDrag(handle: View) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > touchSlop || abs(e.rawY - downY) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = (startX + (e.rawX - downX).toInt())
                            .coerceIn(-(params.width - dp(72)), screenW() - dp(72))
                        params.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, screenH() - barH)
                        update()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) persist() else toggleCollapsed()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    persist()
                    true
                }
                else -> false
            }
        }
    }

    private fun attachResize(handle: View) {
        var startW = 0
        var startH = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = params.height
                    downX = e.rawX
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (e.rawX - downX).toInt()).coerceIn(minW, maxOf(minW, screenW()))
                    params.height = (startH + (e.rawY - downY).toInt()).coerceIn(minH, maxOf(minH, screenH()))
                    fullH = params.height
                    update()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persist()
                    true
                }
                else -> false
            }
        }
    }

    private fun update() {
        if (attached) wm.updateViewLayout(root, params)
    }

    private fun persist() {
        prefs.edit()
            .putInt("x", params.x).putInt("y", params.y)
            .putInt("w", params.width).putInt("h", fullH)
            .putBoolean("collapsed", collapsed)
            .apply()
    }

    // ---------- Cycle de vie ----------

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            pick(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            showMessage("Accès aux notifications requis", "Autorise l'appli dans les réglages (voir l'appli)")
        }
        scheduleHide()
    }

    fun hide() {
        if (!attached) return
        root.removeCallbacks(hideRunnable)
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        attached = false
    }
}
