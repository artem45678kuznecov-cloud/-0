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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nox.offline.NoxApp
import com.nox.offline.R
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.storage.MediaLocator
import com.nox.offline.storage.PlaybackRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плеер: Media3 ExoPlayer, локальный файл или документ из папки
 * пользователя, без сети.
 *
 *  - «Картинка в картинке»: кнопка, автоматический вход при выходе из
 *    плеера (по настройке), системные действия ⟲10 ▶/⏸ 10⟳.
 *  - Двойное нажатие слева/справа — перемотка на 10 секунд, по центру —
 *    пауза. Одиночное — показать/скрыть управление.
 *  - Масштаб: «вписать» или «заполнить с обрезкой» — без растягивания.
 *  - Скорость, аудиодорожки и субтитры — стандартное меню Media3.
 *  - Экземпляр один (singleTask): новое видео заменяет текущее.
 *  - Позиция пишется в Room каждые 4 секунды, на паузе и при уходе.
 *    Декодер освобождается, как только просмотр действительно закрыт.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val EXTRA_FROM_START = "from_start"
        private const val ACTION_PIP = "com.nox.offline.player.PIP"
        private const val EXTRA_PIP = "control"
        private const val PIP_PLAY = 1
        private const val PIP_BACK = 2
        private const val PIP_FWD = 3
        private const val SEEK_MS = 10_000L

        fun intent(context: Context, m: MediaEntity, fromStart: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, m.id)
                .putExtra(EXTRA_FROM_START, fromStart)
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var hint: TextView
    private lateinit var resizeButton: ImageButton
    private var media: MediaEntity? = null
    private var fromStart = false
    private var saver: Job? = null
    private var hintJob: Job? = null
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoAspect = Rational(16, 9)

    private val app get() = NoxApp.get(this)

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = player ?: return
            when (intent.getIntExtra(EXTRA_PIP, 0)) {
                PIP_PLAY -> if (p.isPlaying) p.pause() else p.play()
                PIP_BACK -> p.seekTo((p.currentPosition - SEEK_MS).coerceAtLeast(0))
                PIP_FWD -> p.seekTo(p.currentPosition + SEEK_MS)
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
        readIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Второго плеера нет: новое видео заменяет текущее, позиция старого сохраняется.
        savePosition()
        readIntent(intent)
    }

    private fun readIntent(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_MEDIA_ID, -1)
        fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) { app.db.media().get(id) }
            if (m == null) { finish(); return@launch }
            media = m
            titleView.text = m.title
            startPlayback(m)
        }
    }

    // ------------------------------------------------------------------
    //  Воспроизведение
    // ------------------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerView.keepScreenOn = isPlaying
                if (!isPlaying) savePosition()
                updatePipParams()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) savePosition(ended = true)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    val w = (size.width * size.pixelWidthHeightRatio).toInt()
                    videoAspect = Rational(w, size.height)
                    updatePipParams()
                }
            }
        })
        playerView.player = exo
        player = exo
        return exo
    }

    private fun startPlayback(m: MediaEntity) {
        val exo = ensurePlayer()
        PlaybackRegistry.playingMediaId = m.id
        exo.setMediaItem(MediaItem.fromUri(MediaLocator.playUri(m)))
        exo.prepare()
        NoxLog.event("player-open", "media" to m.id, "external" to m.isExternal)
        // «Начать сначала» действует один раз: после сворачивания и
        // возврата видео продолжается с сохранённого места.
        val startOver = fromStart
        fromStart = false
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { app.db.playback().get(m.id) }
            if (!startOver && saved != null && !saved.completed && saved.positionMs > 0 && saved.durationMs > 0 &&
                saved.positionMs < saved.durationMs - 3000) {
                exo.seekTo(saved.positionMs)
            } else {
                exo.seekTo(0)
            }
            exo.playWhenReady = true
        }
    }

    private fun releasePlayer() {
        savePosition()
        playerView.player = null
        player?.release()
        player = null
        PlaybackRegistry.playingMediaId = -1
    }

    private fun savePosition(ended: Boolean = false) {
        val p = player ?: return
        val m = media ?: return
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        val entity = PlaybackEntity(
            mediaId = m.id,
            positionMs = if (ended) 0 else p.currentPosition,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            completed = ended || (p.currentPosition >= duration - 5_000),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            try { app.db.playback().upsert(entity) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------
    //  Жизненный цикл и «картинка в картинке»
    // ------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        media?.let { if (player == null) startPlayback(it) }
        saver = lifecycleScope.launch {
            while (isActive) {
                delay(4000)
                if (player?.isPlaying == true) savePosition()
            }
        }
    }

    override fun onStop() {
        saver?.cancel()
        saver = null
        // В PiP окно остаётся видимым — видео не останавливаем. Иначе
        // просмотр действительно закрыт: декодер освобождается.
        if (!isInPip()) releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        releasePlayer()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // На Android 12+ это делает setAutoEnterEnabled, здесь — для 8–11.
        if (Build.VERSION.SDK_INT < 31 && app.settings.player.value.pipOnLeave && player?.isPlaying == true) enterPip()
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
        val playing = player?.isPlaying == true
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
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // Окно PiP закрыли крестиком: просмотр окончен.
            releasePlayer()
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
            setShowSubtitleButton(true)
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
                val p = player ?: return false
                val third = playerView.width / 3f
                when {
                    e.x < third -> { p.seekTo((p.currentPosition - SEEK_MS).coerceAtLeast(0)); showHint("−10 с") }
                    e.x > third * 2 -> { p.seekTo(p.currentPosition + SEEK_MS); showHint("+10 с") }
                    else -> { if (p.isPlaying) p.pause() else p.play() }
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
