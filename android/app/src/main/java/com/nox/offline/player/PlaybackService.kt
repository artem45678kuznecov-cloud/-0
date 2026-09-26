package com.nox.offline.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nox.offline.MainActivity
import com.nox.offline.core.NoxLog

/**
 * MediaSession поверх ЕДИНСТВЕННОГО плеера [PlaybackHub]: уведомление с
 * управлением, кнопки гарнитуры, экран блокировки, режим «Слушать» при
 * выключенном экране. Сервис своего плеера не создаёт и не освобождает —
 * им владеет [PlaybackHub]. На передний план Media3 выводит его сама,
 * пока идёт воспроизведение (foregroundServiceType = mediaPlayback).
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val hub = PlaybackHub.get(this)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, hub.player()).setSessionActivity(open).build()
        NoxLog.event("session-created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Смахнули NOX из недавних: если ничего не играет — сервис уходит, звук сам не включится. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            PlaybackHub.get(this).save()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Плеер принадлежит PlaybackHub и живёт дальше; освобождается только сессия.
        session?.release()
        session = null
        NoxLog.event("session-released")
        super.onDestroy()
    }
}
