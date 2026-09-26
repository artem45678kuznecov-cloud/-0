package com.nox.offline.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.library.Segments
import com.nox.offline.settings.AutoNext
import com.nox.offline.storage.MediaLocator
import com.nox.offline.storage.PlaybackRegistry
import com.nox.offline.subtitles.SubtitleParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Единственный владелец воспроизведения в процессе.
 *
 * Один ExoPlayer на всё приложение: вкладка «Плеер», полноэкранный режим,
 * «картинка в картинке», уведомление, наушники и режим «Слушать»
 * управляют одной и той же сессией ([PlaybackService] держит MediaSession
 * поверх этого же плеера). Переход между экранами только переносит
 * поверхность вывода ([PlayerView.switchTargetView]) — видео не
 * перезапускается, второго звука нет.
 *
 * Декодер освобождается ([Player.stop]), когда просмотр закрыт или
 * интерфейс скрыт вне режима «Слушать». После перезапуска процесса
 * ничего само не играет.
 */
@UnstableApi
class PlaybackHub(private val app: NoxApp) {
    /** Что сейчас открыто. [segment] = null — целый файл. */
    data class Now(
        val media: MediaEntity,
        val segment: Segments.Segment?,
        /** Все главы и серии этого файла. */
        val segments: List<Segments.Segment>,
        val context: PlayContext?,
        val durationMs: Long,
    ) {
        val playable: Playable get() = Playable(media.id, segment?.id ?: 0)
        val title: String get() = segment?.title?.takeIf { it.isNotBlank() } ?: media.title
    }

    data class Offer(val next: Playable, val title: String, val secondsLeft: Int)

    /** Звуковая дорожка файла (язык/озвучка), как её видит плеер. */
    data class AudioTrack(val group: Int, val track: Int, val label: String, val selected: Boolean)

    data class State(
        val now: Now? = null,
        val isPlaying: Boolean = false,
        val buffering: Boolean = false,
        /** Позиция внутри серии (или файла). */
        val positionMs: Long = 0,
        /** Длина серии (или файла). */
        val durationMs: Long = 0,
        /** Абсолютное время в файле. */
        val absoluteMs: Long = 0,
        val speed: Float = 1f,
        /** «Слушать»: видео не декодируется, звук продолжается при выключенном экране. */
        val listen: Boolean = false,
        val error: String = "",
        val sleep: SleepTimer.Mode? = null,
        val sleepRemainingMs: Long? = null,
        val offer: Offer? = null,
        /** Закончилось, и следующего нет. */
        val ended: Boolean = false,
        val subtitleText: String = "",
        val videoAspect: Float = 16f / 9f,
        val hasVideo: Boolean = true,
        val audioTracks: List<AudioTrack> = emptyList(),
        /** Масштаб в окне: false — вписать, true — заполнить с обрезкой. */
        val zoom: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var exo: ExoPlayer? = null
    private var controller: MediaController? = null
    private var controllerPending = false
    private var ticker: Job? = null
    private var lastSaveAt = 0L
    private var cues: List<SubtitleParser.Cue> = emptyList()
    private var cuesFor = 0L
    private var subtitleOffset = 0L
    private var attachedView: PlayerView? = null
    private var visibleUi = 0
    private var hideJob: Job? = null
    private var offerJob: Job? = null
    private var restoreVolume = false

    private val settings get() = app.settings

    // ------------------------------------------------------------------
    //  Плеер и сессия
    // ------------------------------------------------------------------

    /** Плеер создаётся один раз на процесс и только на главном потоке. */
    fun player(): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true)
            // Наушники отключили — пауза (ACTION_AUDIO_BECOMING_NOISY).
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        p.addListener(listener)
        exo = p
        NoxLog.event("player-created")
        return p
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (!isPlaying) save()
            if (isPlaying) ensureTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(buffering = playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) onReady()
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            val m = _state.value.now?.media
            val gone = m != null && !MediaLocator.exists(app, app.saf, m)
            val msg = when {
                gone && m?.isExternal == true -> "Нет доступа к файлу в папке. Откройте «Настройки → Папка для видео» и выберите папку заново — запись в медиатеке сохранена."
                gone -> "Файл недоступен: его удалили или перенесли. Запись в медиатеке сохранена."
                else -> "Не удалось воспроизвести: ${error.errorCodeName}"
            }
            NoxLog.event("player-error", "code" to error.errorCodeName, "media" to m?.id, "gone" to gone)
            _state.value = _state.value.copy(error = msg, isPlaying = false)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _state.value = _state.value.copy(videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val list = ArrayList<AudioTrack>()
            tracks.groups.forEachIndexed { gi, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    val label = f.label?.takeIf { it.isNotBlank() }
                        ?: f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { com.nox.offline.downloader.catalog.CatalogBuilder.languageName(it) }
                        ?: "Дорожка ${list.size + 1}"
                    list.add(AudioTrack(gi, ti, label, g.isTrackSelected(ti)))
                }
            }
            _state.value = _state.value.copy(audioTracks = list)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.value = _state.value.copy(speed = playbackParameters.speed)
        }
    }

    /**
     * Подключение контроллера поднимает [PlaybackService] с MediaSession:
     * уведомление, кнопки наушников, экран блокировки — одна сессия.
     */
    private fun ensureSession() {
        if (controller != null || controllerPending) return
        controllerPending = true
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            controllerPending = false
            controller = runCatching { future.get() }.getOrNull()
            NoxLog.event("session-connected", "ok" to (controller != null))
        }, androidx.core.content.ContextCompat.getMainExecutor(app))
    }

    private fun releaseSession() {
        controller?.release()
        controller = null
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
    }

    // ------------------------------------------------------------------
    //  Открытие
    // ------------------------------------------------------------------

    /**
     * Открыть видео (или серию внутри него). Позиция — сохранённая, если
     * не просят начать сначала. [context] — очередь автоперехода.
     */
    fun open(mediaId: Long, chapterId: Long = 0, context: PlayContext? = null, fromStart: Boolean = false,
             listen: Boolean? = null, play: Boolean = true) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { load(mediaId, chapterId) } ?: run {
                _state.value = _state.value.copy(error = "Видео не найдено в медиатеке")
                return@launch
            }
            val (m, segs, seg, duration, startAt) = loaded
            save()
            cancelOffer()
            val p = player()
            val listenMode = listen ?: _state.value.listen
            val item = mediaItem(m, seg, duration)
            p.setMediaItem(item, if (fromStart) 0 else startAt)
            applyListen(p, listenMode || m.isAudio)
            p.prepare()
            p.setPlaybackSpeed(settings.player.value.lastSpeed.takeIf { it > 0 } ?: 1f)
            p.playWhenReady = play
            PlaybackRegistry.playingMediaId = m.id
            val ctx = context?.moveTo(Playable(m.id, seg?.id ?: 0))
                ?: _state.value.now?.context?.takeIf { it.items.contains(Playable(m.id, seg?.id ?: 0)) }?.moveTo(Playable(m.id, seg?.id ?: 0))
                ?: seg?.let { PlayContext.ofSegments(m.title, m.id, segs, Playable(m.id, it.id)) }
            _state.value = _state.value.copy(
                now = Now(m, seg, segs, ctx, duration), error = "", ended = false, offer = null,
                listen = listenMode || m.isAudio, hasVideo = !m.isAudio, positionMs = if (fromStart) 0 else startAt,
                durationMs = seg?.lengthMs ?: duration, absoluteMs = (seg?.startMs ?: 0) + (if (fromStart) 0 else startAt),
            )
            loadSubtitles(m)
            ensureSession()
            ensureTicker()
            NoxLog.event("player-open", "media" to m.id, "chapter" to (seg?.id ?: 0), "listen" to (listenMode || m.isAudio),
                "external" to m.isExternal, "context" to (ctx?.items?.size ?: 0))
        }
    }

    private data class Loaded(val media: MediaEntity, val segments: List<Segments.Segment>, val segment: Segments.Segment?,
                              val durationMs: Long, val startRelMs: Long)

    private suspend fun load(mediaId: Long, chapterId: Long): Loaded? {
        val m = app.db.media().get(mediaId) ?: return null
        val pb = app.db.playback().get(mediaId)
        val duration = maxOf(m.durationSec * 1000, pb?.durationMs ?: 0)
        val segs = Segments.resolve(app.db.chapters().forMedia(mediaId), duration)
        val seg = if (chapterId > 0) segs.firstOrNull { it.id == chapterId } else null
        val start = if (seg != null) {
            val sp = app.markup.progress(seg.id)
            if (sp != null && !sp.completed && sp.positionMs in 1 until seg.lengthMs - 3000) sp.positionMs else 0L
        } else {
            if (pb != null && !pb.completed && pb.positionMs > 0 && (pb.durationMs <= 0 || pb.positionMs < pb.durationMs - 3000)) pb.positionMs else 0L
        }
        return Loaded(m, segs, seg, duration, start)
    }

    private fun mediaItem(m: MediaEntity, seg: Segments.Segment?, durationMs: Long): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(seg?.title?.takeIf { it.isNotBlank() } ?: m.title)
            .setArtist(m.uploader.ifBlank { null })
            .setAlbumTitle(if (seg != null) m.title else null)
            .apply { if (m.coverPath.isNotBlank() && File(m.coverPath).exists()) setArtworkUri(Uri.fromFile(File(m.coverPath))) }
            .build()
        val b = MediaItem.Builder().setUri(MediaLocator.playUri(m)).setMediaId("media:${m.id}:${seg?.id ?: 0}").setMediaMetadata(meta)
        if (seg != null) {
            // Перемотка внутри виртуальной серии ограничена её границами.
            val last = durationMs > 0 && seg.endMs >= durationMs - 500
            b.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(seg.startMs)
                .setEndPositionMs(if (last) C.TIME_END_OF_SOURCE else seg.endMs)
                .build())
        }
        return b.build()
    }

    /** «Смотреть весь файл» с того же места. */
    fun playWholeFile() {
        val now = _state.value.now ?: return
        if (now.segment == null) return
        val abs = _state.value.absoluteMs
        save()
        val p = player()
        p.setMediaItem(mediaItem(now.media, null, now.durationMs), abs)
        p.prepare()
        _state.value = _state.value.copy(now = now.copy(segment = null, context = null), durationMs = now.durationMs,
            positionMs = abs, offer = null, ended = false)
        NoxLog.event("player-whole-file", "media" to now.media.id)
    }

    // ------------------------------------------------------------------
    //  Управление
    // ------------------------------------------------------------------

    fun togglePlay() {
        val p = exo ?: return
        if (_state.value.ended) { p.seekTo(0); _state.value = _state.value.copy(ended = false) }
        if (p.playbackState == Player.STATE_IDLE && _state.value.now != null) p.prepare()
        if (p.isPlaying) p.pause() else { restoreVolumeIfNeeded(); p.play() }
    }

    fun pause() { exo?.pause() }

    fun seekTo(relMs: Long) {
        val p = exo ?: return
        val len = _state.value.durationMs
        p.seekTo(if (len > 0) relMs.coerceIn(0, len) else relMs.coerceAtLeast(0))
        _state.value = _state.value.copy(ended = false)
        updatePosition()
    }

    fun seekBy(deltaMs: Long) = seekTo((exo?.currentPosition ?: 0) + deltaMs)

    fun setSpeed(speed: Float) {
        exo?.setPlaybackSpeed(speed)
        settings.updatePlayer { it.copy(lastSpeed = speed) }
    }

    /** «Слушать»: видеодорожка выключается в выборе дорожек — декодер видео не работает. */
    fun setListen(on: Boolean) {
        val p = exo ?: return
        val m = _state.value.now?.media
        val value = on || m?.isAudio == true
        applyListen(p, value)
        _state.value = _state.value.copy(listen = value)
        NoxLog.event("player-listen", "on" to value)
    }

    private fun applyListen(p: ExoPlayer, on: Boolean) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
            // Свои субтитры рисуем сами — встроенные текстовые дорожки не мешают.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun selectAudioTrack(t: AudioTrack) {
        val p = exo ?: return
        val g = p.currentTracks.groups.getOrNull(t.group) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, t.track))
            .build()
    }

    fun setZoom(on: Boolean) { _state.value = _state.value.copy(zoom = on) }

    fun next() {
        val ctx = _state.value.now?.context ?: return
        val n = ctx.next ?: return
        open(n.mediaId, n.chapterId, ctx)
    }

    fun previous() {
        val ctx = _state.value.now?.context ?: return
        val p = exo
        // Как в обычных плеерах: дальше 5 секунд от начала — в начало текущей.
        if (p != null && p.currentPosition > 5_000) { p.seekTo(0); return }
        val prev = ctx.previous ?: return
        open(prev.mediaId, prev.chapterId, ctx)
    }

    /** Перейти к главе/серии этого же файла (как отдельный элемент или позиция во всём файле). */
    fun jumpTo(seg: Segments.Segment) {
        val now = _state.value.now ?: return
        if (now.segment == null && seg.kind == ChapterKind.CHAPTER) {
            exo?.seekTo(seg.startMs); updatePosition(); return
        }
        open(now.media.id, seg.id, now.context?.takeIf { it.items.contains(Playable(now.media.id, seg.id)) })
    }

    /** Закрыть просмотр: позиция сохраняется, декодер и сессия освобождаются. */
    fun close() {
        save()
        cancelOffer()
        exo?.let { it.stop(); it.clearMediaItems() }
        _state.value = State(sleep = null)
        PlaybackRegistry.playingMediaId = -1
        ticker?.cancel(); ticker = null
        releaseSession()
        NoxLog.event("player-closed")
    }

    // ------------------------------------------------------------------
    //  Поверхность вывода и видимость интерфейса
    // ------------------------------------------------------------------

    /** Показать видео в этом PlayerView; прежний просто теряет поверхность. */
    fun attach(view: PlayerView) {
        val p = player()
        if (attachedView === view && view.player === p) return
        PlayerView.switchTargetView(p, attachedView, view)
        attachedView = view
    }

    fun detach(view: PlayerView) {
        if (attachedView === view) {
            view.player = null
            attachedView = null
        }
    }

    /** Экран NOX (вкладка или полноэкранный плеер) стал видимым. */
    fun uiStarted() {
        visibleUi++
        hideJob?.cancel()
    }

    /**
     * Экран скрыт. Если ни одного экрана NOX не видно и это не «Слушать» —
     * пауза (переход между вкладкой и полноэкранным режимом успевает
     * открыть новый экран раньше, поэтому не прерывается).
     */
    fun uiStopped() {
        visibleUi = (visibleUi - 1).coerceAtLeast(0)
        if (visibleUi > 0) return
        hideJob?.cancel()
        hideJob = scope.launch {
            // Поворот экрана и переход вкладка ⇄ полный экран укладываются в эту паузу.
            delay(1500)
            if (visibleUi == 0 && !_state.value.listen && exo?.isPlaying == true) {
                exo?.pause()
                NoxLog.event("player-paused-hidden")
            }
            save()
        }
    }

    // ------------------------------------------------------------------
    //  Субтитры
    // ------------------------------------------------------------------

    private fun loadSubtitles(m: MediaEntity) {
        subtitleOffset = m.subtitleOffsetMs
        if (m.subtitleId == 0L) { cues = emptyList(); cuesFor = 0; return }
        if (cuesFor == m.subtitleId && cues.isNotEmpty()) return
        scope.launch {
            val list = app.subtitles.cues(m.subtitleId)
            if (list == null) {
                NoxLog.event("subtitles-missing", "media" to m.id)
                cues = emptyList()
            } else cues = list
            cuesFor = m.subtitleId
        }
    }

    /** Выбрать дорожку (0 — выключить). Настройка хранится у видео. */
    fun selectSubtitle(subtitleId: Long) {
        val now = _state.value.now ?: return
        scope.launch {
            withContext(Dispatchers.IO) { app.db.media().setSubtitle(now.media.id, subtitleId) }
            val m = now.media.copy(subtitleId = subtitleId)
            _state.value = _state.value.copy(now = now.copy(media = m))
            cuesFor = 0
            loadSubtitles(m)
        }
    }

    /** Сдвиг синхронизации: + — субтитры позже, − — раньше. */
    fun setSubtitleOffset(offsetMs: Long) {
        val now = _state.value.now ?: return
        subtitleOffset = offsetMs.coerceIn(-600_000, 600_000)
        _state.value = _state.value.copy(now = now.copy(media = now.media.copy(subtitleOffsetMs = subtitleOffset)))
        scope.launch(Dispatchers.IO) { app.db.media().setSubtitleOffset(now.media.id, subtitleOffset) }
    }

    /** Реплика для PlayerView.subtitleView: одна и та же в портрете, на весь экран и в PiP. */
    fun subtitleCues(): List<Cue> {
        val t = _state.value.subtitleText
        return if (t.isBlank()) emptyList() else listOf(Cue.Builder().setText(t).build())
    }

    // ------------------------------------------------------------------
    //  Таймер сна и автопереход
    // ------------------------------------------------------------------

    fun setSleep(mode: SleepTimer.Mode?) {
        _state.value = _state.value.copy(sleep = mode, sleepRemainingMs = SleepTimer.remaining(mode, SystemClock.elapsedRealtime()))
        restoreVolumeIfNeeded()
        NoxLog.event("sleep-timer", "mode" to (mode?.label ?: "off"))
        ensureTicker()
    }

    fun sleepMinutes(minutes: Int) = setSleep(SleepTimer.Mode.At(SystemClock.elapsedRealtime() + minutes * 60_000L, minutes))

    private fun restoreVolumeIfNeeded() {
        if (restoreVolume || (exo?.volume ?: 1f) < 1f) { exo?.volume = 1f; restoreVolume = false }
    }

    private fun fireSleep() {
        val p = exo
        p?.pause()
        save()
        p?.volume = 1f
        _state.value = _state.value.copy(sleep = null, sleepRemainingMs = null, offer = null)
        NoxLog.event("sleep-fired")
    }

    private fun onEnded() {
        val s = _state.value
        val now = s.now ?: return
        // Позиция и отметки «просмотрено».
        markCompleted(now)
        val action = EndRule.decide(now.context, now.playable, s.sleep, settings.player.value.autoNext)
        NoxLog.event("player-ended", "media" to now.media.id, "chapter" to (now.segment?.id ?: 0),
            "action" to action.javaClass.simpleName)
        when (action) {
            EndRule.Action.SleepStop -> fireSleep()
            is EndRule.Action.Play -> open(action.next.mediaId, action.next.chapterId, now.context)
            is EndRule.Action.Offer -> startOffer(action.next, now.context!!)
            EndRule.Action.Nothing -> _state.value = _state.value.copy(ended = true, isPlaying = false)
        }
    }

    private fun startOffer(next: Playable, ctx: PlayContext) {
        offerJob?.cancel()
        offerJob = scope.launch {
            val title = withContext(Dispatchers.IO) { titleOf(next) }
            var left = settings.player.value.autoNextSeconds
            while (left > 0 && isActive) {
                _state.value = _state.value.copy(offer = Offer(next, title, left), ended = false)
                delay(1000)
                left--
            }
            if (isActive) {
                _state.value = _state.value.copy(offer = null)
                open(next.mediaId, next.chapterId, ctx)
            }
        }
    }

    fun acceptOffer() {
        val o = _state.value.offer ?: return
        val ctx = _state.value.now?.context
        cancelOffer()
        open(o.next.mediaId, o.next.chapterId, ctx)
    }

    fun cancelOffer() {
        offerJob?.cancel()
        offerJob = null
        if (_state.value.offer != null) _state.value = _state.value.copy(offer = null, ended = true)
    }

    private suspend fun titleOf(p: Playable): String {
        if (p.chapterId > 0) app.db.chapters().get(p.chapterId)?.let { return it.title }
        return app.db.media().get(p.mediaId)?.title ?: ""
    }

    // ------------------------------------------------------------------
    //  Позиции
    // ------------------------------------------------------------------

    private fun onReady() {
        val p = exo ?: return
        val now = _state.value.now ?: return
        // Настоящая длительность файла известна только плееру: запоминаем для разметки.
        if (now.segment == null) {
            val d = p.duration
            if (d > 0 && d != C.TIME_UNSET && d != now.durationMs) {
                _state.value = _state.value.copy(now = now.copy(durationMs = d), durationMs = d)
            }
        }
        updatePosition()
    }

    private fun updatePosition() {
        val p = exo ?: return
        val now = _state.value.now ?: return
        val rel = p.currentPosition.coerceAtLeast(0)
        val len = if (now.segment != null) now.segment.lengthMs else p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: now.durationMs
        val abs = (now.segment?.startMs ?: 0) + rel
        val text = if (cues.isEmpty()) "" else SubtitleParser.at(cues, abs, subtitleOffset).joinToString("\n") { it.text }
        _state.value = _state.value.copy(positionMs = rel, durationMs = len, absoluteMs = abs, subtitleText = text,
            sleepRemainingMs = SleepTimer.remaining(_state.value.sleep, SystemClock.elapsedRealtime()))
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val playing = exo?.isPlaying == true
                updatePosition()
                val sleep = _state.value.sleep
                if (sleep is SleepTimer.Mode.At) {
                    val left = sleep.endAtElapsed - SystemClock.elapsedRealtime()
                    if (left <= 0) fireSleep()
                    else if (playing) {
                        val v = SleepTimer.volume(left)
                        if (v < 1f) { exo?.volume = v; restoreVolume = true }
                    }
                }
                if (playing && SystemClock.elapsedRealtime() - lastSaveAt > SAVE_EVERY_MS) save()
                if (_state.value.now == null && sleep == null) break
                delay(if (playing) 200 else 500)
            }
        }
    }

    private data class Snapshot(val mediaId: Long, val seg: Segments.Segment?, val rel: Long, val fileDuration: Long)

    /** Снимок позиции (главный поток). */
    private fun snapshot(): Snapshot? {
        val p = exo ?: return null
        val now = _state.value.now ?: return null
        val fileDuration = now.durationMs.takeIf { it > 0 } ?: p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return null
        return Snapshot(now.media.id, now.segment, p.currentPosition.coerceAtLeast(0), fileDuration)
    }

    private suspend fun persist(s: Snapshot) {
        val seg = s.seg
        if (seg != null) {
            val done = s.rel >= seg.lengthMs - 5_000
            app.markup.saveProgress(seg.id, if (done) 0 else s.rel, done)
        }
        // Позиция всего файла — абсолютная. «Досмотрено» ставится только у конца файла:
        // одна серия марафона не отмечает весь файл просмотренным.
        val abs = (seg?.startMs ?: 0) + s.rel
        val prev = app.db.playback().get(s.mediaId)
        val fileDone = abs >= s.fileDuration - 5_000
        app.db.playback().upsert(PlaybackEntity(s.mediaId, if (fileDone) 0 else abs, s.fileDuration,
            System.currentTimeMillis(), fileDone || (prev?.completed == true && seg != null)))
    }

    /** Сохранить позицию сейчас (пауза, переход, уход). */
    fun save() {
        lastSaveAt = SystemClock.elapsedRealtime()
        val snap = snapshot() ?: return
        app.appScopeLaunch { persist(snap) }
    }

    private fun markCompleted(now: Now) {
        val seg = now.segment
        val fileDuration = now.durationMs
        app.appScopeLaunch {
            if (seg != null) app.markup.saveProgress(seg.id, 0, true)
            val lastSegment = seg == null || (fileDuration > 0 && seg.endMs >= fileDuration - 5_000)
            if (lastSegment) {
                app.db.playback().upsert(PlaybackEntity(now.media.id, 0, fileDuration.coerceAtLeast(1), System.currentTimeMillis(), true))
            }
        }
    }

    /** Перед установкой обновления: звук на паузе, позиция записана на диск до возврата. */
    suspend fun checkpointForUpdate() {
        val snap = withContext(Dispatchers.Main) { exo?.pause(); snapshot() } ?: return
        withContext(Dispatchers.IO) { runCatching { persist(snap) } }
        NoxLog.event("player-update-checkpoint", "media" to snap.mediaId)
    }

    companion object {
        const val SEEK_MS = 10_000L
        const val SAVE_EVERY_MS = 4_000L

        fun get(context: Context): PlaybackHub = NoxApp.get(context).playback
    }
}
