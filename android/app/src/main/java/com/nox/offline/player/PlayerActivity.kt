package com.nox.offline.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nox.offline.NoxApp
import com.nox.offline.R
import com.nox.offline.core.NoxLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Полноэкранный просмотр. Своего плеера у него нет: он показывает тот же
 * ExoPlayer из [PlaybackHub], что и вкладка «Плеер», поэтому переход в
 * полный экран и обратно не перезапускает видео и не даёт второго звука.
 * Без обоев и нижней панели — только видео.
 *
 *  - «Картинка в картинке»: кнопка, автоматический вход при выходе (по
 *    настройке), системные действия ⟲10 ▶/⏸ 10⟳.
 *  - Двойное нажатие слева/справа — ±10 секунд, по центру — пауза.
 *  - Масштаб: «вписать» или «заполнить с обрезкой» — без растягивания.
 *  - Свои субтитры, таймер сна и предложение следующей серии работают
 *    и здесь: всё это живёт в [PlaybackHub].
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    companion object {
        private const val ACTION_PIP = "com.nox.offline.player.PIP"
        private const val EXTRA_PIP = "control"
        private const val EXTRA_PIP_NOW = "pip_now"
        private const val PIP_PLAY = 1
        private const val PIP_BACK = 2
        private const val PIP_FWD = 3
        private const val SEEK_MS = 10_000L

        /** Показать на весь экран то, что уже открыто в [PlaybackHub]. */
        fun fullscreen(context: Context, pip: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_PIP_NOW, pip)
    }

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var hint: TextView
    private lateinit var offerView: TextView
    private lateinit var nextButton: ImageButton
    private lateinit var resizeButton: ImageButton
    private var hintJob: Job? = null
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoAspect = Rational(16, 9)
    private var pipOnStart = false

    private val app get() = NoxApp.get(this)

    private val hub get() = app.playback

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_PIP, 0)) {
                PIP_PLAY -> hub.togglePlay()
                PIP_BACK -> hub.seekBy(-SEEK_MS)
                PIP_FWD -> hub.seekBy(SEEK_MS)
            }
            updatePipParams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        buildViews()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipOnStart = intent.getBooleanExtra(EXTRA_PIP_NOW, false)
        if (hub.state.value.now == null) { finish(); return }
        observeHub()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PIP_NOW, false)) enterPip()
    }

    // ------------------------------------------------------------------
    //  Состояние общего плеера
    // ------------------------------------------------------------------

    private fun observeHub() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.state.collect { st ->
                    val now = st.now
                    if (now == null) { finish(); return@collect }
                    titleView.text = now.title
                    playerView.keepScreenOn = st.isPlaying
                    playerView.subtitleView?.setCues(hub.subtitleCues())
                    nextButton.visibility = if (now.context?.next != null) View.VISIBLE else View.GONE
                    val offer = st.offer
                    offerView.visibility = if (offer != null && !isInPip()) View.VISIBLE else View.GONE
                    if (offer != null) offerView.text = "Далее через ${offer.secondsLeft} с: ${offer.title}\nНажмите, чтобы начать сейчас · долгое нажатие — отмена"
                    val a = st.videoAspect
                    if (a > 0f) {
                        val r = Rational((a * 1000).toInt().coerceAtLeast(1), 1000)
                        if (r != videoAspect) { videoAspect = r; updatePipParams() }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settings.player.collect { p ->
                    playerView.subtitleView?.let { SubtitleStyle.apply(it, p.subtitleScale, p.subtitleBackground) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Жизненный цикл и «картинка в картинке»
    // ------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        hub.uiStarted()
        hub.attach(playerView)
        if (pipOnStart) { pipOnStart = false; playerView.post { enterPip() } }
    }

    override fun onStop() {
        // В PiP окно остаётся видимым: видео не останавливаем.
        if (!isInPip()) hub.uiStopped()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        if (::playerView.isInitialized) hub.detach(playerView)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // На Android 12+ это делает setAutoEnterEnabled, здесь — для 8–11.
        if (Build.VERSION.SDK_INT < 31 && app.settings.player.value.pipOnLeave && hub.state.value.isPlaying) enterPip()
    }

    private fun isInPip(): Boolean = isInPictureInPictureMode

    private fun pipSupported(): Boolean = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (!pipSupported()) { showHint("«Картинка в картинке» недоступна на этом устройстве"); return }
        try {
            enterPictureInPictureMode(buildPipParams())
        } catch (e: Exception) {
            NoxLog.event("pip-error", "error" to e.javaClass.simpleName)
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val ratio = videoAspect.let {
            val v = it.toFloat()
            when {
                v > 2.39f -> Rational(239, 100)
                v < 1f / 2.39f -> Rational(100, 239)
                else -> it
            }
        }
        val playing = hub.state.value.isPlaying
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(
                remote(PIP_BACK, R.drawable.ic_pip_replay, "Назад 10 с"),
                remote(PIP_PLAY, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play, if (playing) "Пауза" else "Играть"),
                remote(PIP_FWD, R.drawable.ic_pip_forward, "Вперёд 10 с"),
            ))
        // Плавный переход в окно: система анимирует из области видео.
        if (::playerView.isInitialized && playerView.width > 0) {
            val r = android.graphics.Rect()
            if (playerView.getGlobalVisibleRect(r)) b.setSourceRectHint(r)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(app.settings.player.value.pipOnLeave && playing)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun remote(code: Int, icon: Int, title: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP, code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi)
    }

    private fun updatePipParams() {
        if (!pipSupported()) return
        try { setPictureInPictureParams(buildPipParams()) } catch (_: Exception) { }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else topBar.visibility
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) offerView.visibility = View.GONE
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // Окно PiP закрыли крестиком: просмотр окончен, позиция сохранена.
            hub.pause()
            hub.uiStopped()
            finish()
        }
    }

    // ------------------------------------------------------------------
    //  Интерфейс
    // ------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowSubtitleButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            controllerAutoShow = true
            resizeMode = this@PlayerActivity.resizeMode
            setBackgroundColor(Color.BLACK)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                if (!isInPip()) topBar.visibility = v
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB0000000.toInt(), 0x00000000))
        }
        topBar.addView(iconButton(R.drawable.ic_player_back, "Назад") { finish() })
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nextButton = iconButton(R.drawable.ic_pip_forward, "Следующая серия") { hub.next() }
        topBar.addView(nextButton)
        resizeButton = iconButton(R.drawable.ic_player_resize, "Масштаб") { toggleResize() }
        topBar.addView(resizeButton)
        topBar.addView(iconButton(R.drawable.ic_player_pip, "Картинка в картинке") { enterPip() })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x99000000.toInt()) }
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        offerView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xCC000000.toInt()); setStroke(dp(1), 0xFFF5A524.toInt()) }
            visibility = View.GONE
            setOnClickListener { hub.acceptOffer() }
            setOnLongClickListener { hub.cancelOffer(); true }
        }
        root.addView(offerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(24), dp(96)) })
        setContentView(root)
        installGestures()
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = label
        setBackgroundResource(android.R.drawable.list_selector_background)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
    }

    private fun installGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val third = playerView.width / 3f
                when {
                    e.x < third -> { hub.seekBy(-SEEK_MS); showHint("−10 с") }
                    e.x > third * 2 -> { hub.seekBy(SEEK_MS); showHint("+10 с") }
                    else -> hub.togglePlay()
                }
                return true
            }
        })
        // Кнопки управления Media3 — дочерние элементы и получают касания
        // первыми; сюда доходят только касания по «пустому» кадру.
        playerView.setOnTouchListener { v, ev ->
            detector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun toggleResize() {
        // Только режимы без искажения пропорций: вписать или обрезать.
        resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.resizeMode = resizeMode
        showHint(if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) "Вписать" else "Заполнить экран")
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(900)
            hint.visibility = View.GONE
        }
    }
}
