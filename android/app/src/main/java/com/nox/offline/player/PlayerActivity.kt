package com.nox.offline.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Плеер: Media3 ExoPlayer, локальный файл, без сети. Полный экран,
 * play/pause/перемотка/время — стандартный PlayerView. Позиция просмотра
 * пишется в Room каждые несколько секунд и при уходе с экрана.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, m: MediaEntity): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, m.id)
                .putExtra(EXTRA_PATH, m.filePath)
                .putExtra(EXTRA_TITLE, m.title)
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var mediaId: Long = -1
    private var saver: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val view = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerAutoShow = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        playerView = view
        setContentView(view)

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    savePosition(0L)
                }
            }
        })
        exo.prepare()
        NoxLog.event("player-open", "media" to mediaId)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (mediaId >= 0) NoxApp.get(this@PlayerActivity).db.playback().get(mediaId) else null
            }
            if (saved != null && saved.positionMs > 0 && saved.durationMs > 0 &&
                saved.positionMs < saved.durationMs - 3000) {
                exo.seekTo(saved.positionMs)
            }
            exo.playWhenReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        saver = lifecycleScope.launch {
            while (isActive) {
                delay(4000)
                savePosition()
            }
        }
    }

    override fun onStop() {
        savePosition()
        saver?.cancel()
        saver = null
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun savePosition(force: Long? = null) {
        val p = player ?: return
        if (mediaId < 0) return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val position = force ?: p.currentPosition
        val entity = PlaybackEntity(mediaId = mediaId, positionMs = position, durationMs = duration,
            updatedAt = System.currentTimeMillis())
        lifecycleScope.launch(Dispatchers.IO) {
            try { NoxApp.get(this@PlayerActivity).db.playback().upsert(entity) } catch (_: Exception) { }
        }
    }
}
