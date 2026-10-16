package io.voxphilosophica

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Persistent chunk queue, offline WAV cache, foreground notification and playback. */
class NarrationService : Service() {
    companion object {
        const val EVENT = "io.voxphilosophica.PLAYBACK_STATUS"
        const val START = "vox.START"
        const val PAUSE = "vox.PAUSE"
        const val RESUME = "vox.RESUME"
        const val NEXT = "vox.NEXT"
        const val PREVIOUS = "vox.PREVIOUS"
        const val STOP = "vox.STOP"
        private const val CHANNEL = "vox_audiobooks"
        private const val NOTIFY_ID = 313
        fun dispatch(context: Context, action: String, extras: (Intent.() -> Unit)? = null) {
            val intent = Intent(context, NarrationService::class.java).apply { this.action = action; extras?.invoke(this) }
            if (action == START) context.startForegroundService(intent) else context.startService(intent)
        }
    }
    private data class Part(val chapter: Int, val text: String, val label: String)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicInteger(0)
    private var parts: List<Part> = emptyList()
    private var book: Book? = null
    private var index = 0
    private var paused = false
    private var busy = false
    private var media: MediaPlayer? = null
    private var session: MediaSession? = null
    private var engine: SpeechEngine? = null
    private var mode: String = "system"
    private var speaker: Int = 0
    private var speechSpeed: Float = 1f
    private var audioManager: AudioManager? = null
    private var audioFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            main.post { pause() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Audiobook playback", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        session = MediaSession(this, "VOX-philosophica").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { change(index + 1) }
                override fun onSkipToPrevious() { change((index - 1).coerceAtLeast(0)) }
                override fun onStop() { shutdown(); stopSelf() }
            }, main)
            isActive = true
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                startForegroundCompat(notification("Preparing audiobook…"))
                setup(intent)
            }
            PAUSE -> pause()
            RESUME -> resume()
            NEXT -> change(index + 1)
            PREVIOUS -> change((index - 1).coerceAtLeast(0))
            STOP -> { shutdown(); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun setup(intent: Intent) {
        shutdown()
        try {
            book = BookStore(this).load(intent.getStringExtra("bookId") ?: error("No book selected"))
            session?.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, book!!.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "VOX PHILOSOPHICA").build())
            mode = intent.getStringExtra("engine") ?: "system"
            speaker = intent.getIntExtra("speaker", 0)
            speechSpeed = intent.getFloatExtra("speed", 1f).coerceIn(0.7f, 1.5f)
            getSharedPreferences("vox_playback", MODE_PRIVATE).edit()
                .putString("book_id", book!!.id)
                .putString("engine", mode)
                .putInt("speaker", speaker)
                .putFloat("speed", speechSpeed)
                .putBoolean("active", true)
                .apply()
            parts = book!!.chapters.flatMapIndexed { ch, chapter ->
                Chunker.split(chapter.body).map { Part(ch, it, chapter.title) }
            }
            require(parts.isNotEmpty()) { "Book contains no readable sentences" }
            val startChapter = intent.getIntExtra("chapter", 0).coerceIn(0, book!!.chapters.lastIndex)
            index = parts.indexOfFirst { it.chapter == startChapter }.coerceAtLeast(0)
            paused = false
            showStatus("Loading ${if (mode.startsWith("kokoro")) "integrated Kokoro" else "Android TTS"}…")
            schedule(index)
        } catch (error: Exception) { showStatus("Cannot start: ${error.message}"); stopSelf() }
    }
    private fun audioFile(part: Part): File {
        val bk = book ?: error("No book")
        val parent = File(filesDir, "audio/${bk.id}/$mode/v${speaker}-r${(speechSpeed*100).toInt()}")
        parent.mkdirs()
        return File(parent, sha256(part.text) + ".wav")
    }
    private fun schedule(at: Int) {
        if (at !in parts.indices) { finishBook(); return }
        index = at
        val request = epoch.incrementAndGet()
        media?.release(); media = null
        busy = true
        val part = parts[at]
        val output = audioFile(part) // snapshot cache path before another book can replace playback
        val voiceId = speaker
        val speed = speechSpeed
        val selectedEngine = mode
        showStatus("Preparing · ${part.label} · ${at + 1}/${parts.size}", part.chapter)
        worker.execute {
            try {
                if (!output.exists() || output.length() < 44L) {
                    if (engine == null) {
                        if (selectedEngine == "system") {
                            engine = SystemSpeechEngine(this)
                        } else {
                            val which = if (selectedEngine == "kokoro-latest")
                                ModelInstaller.LATEST else ModelInstaller.ENGLISH
                            ModelInstaller(this).install(which) { progress ->
                                main.post {
                                    if (request == epoch.get()) showStatus(progress, part.chapter)
                                }
                            }
                            engine = KokoroSpeechEngine(this, which, voiceId)
                        }
                    }
                    val temporary = File(output.parentFile, output.name + ".tmp")
                    try {
                        engine!!.synthesize(part.text, voiceId, speed, temporary)
                        require(temporary.length() > 44) { "Empty WAV file" }
                        if (!temporary.renameTo(output)) {
                            temporary.copyTo(output, overwrite = true)
                            temporary.delete()
                        }
                    } finally { temporary.delete() }
                }
                main.post {
                    if (request != epoch.get()) return@post
                    busy = false
                    if (!paused) playFile(output, request)
                    else showStatus("Paused · ${part.label}", part.chapter)
                }
            } catch (error: Throwable) {
                main.post {
                    if (request == epoch.get()) {
                        busy = false
                        paused = true
                        showStatus("Narration error: ${error.message ?: error.javaClass.simpleName}", part.chapter)
                    }
                }
            }
        }
    }
    private fun playFile(file: File, request: Int) {
        if (request != epoch.get() || paused) return
        try {
            media?.release(); media = null
            if (!audioFocus) {
                @Suppress("DEPRECATION")
                audioFocus = audioManager?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            val player = MediaPlayer()
            media = player
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                if (request == epoch.get()) schedule(index + 1)
            }
            player.setOnErrorListener { _, what, extra ->
                if (request == epoch.get()) { paused = true; showStatus("Playback failed ($what / $extra)") }
                true
            }
            player.prepare(); player.start()
            showStatus("Playing · ${parts[index].label} · ${index + 1}/${parts.size}", parts[index].chapter)
        } catch (error: Exception) { paused = true; showStatus("Audio error: ${error.message}") }
    }
    private fun pause() {
        paused = true
        if (media?.isPlaying == true) media?.pause()
        showStatus("Paused")
    }
    private fun resume() {
        paused = false
        when {
            media != null -> { media?.start(); showStatus("Playing") }
            !busy && parts.isNotEmpty() -> schedule(index)
        }
    }
    private fun change(next: Int) {
        if (parts.isEmpty()) return
        paused = false
        schedule(next)
    }
    private fun finishBook() {
        paused = true
        showStatus("Book completed")
        shutdown()
        stopSelf()
    }
    private fun showStatus(message: String, chapter: Int = parts.getOrNull(index)?.chapter ?: 0) {
        if (book != null) {
            getSharedPreferences("vox_playback", MODE_PRIVATE).edit()
                .putString("book_id", book!!.id)
                .putString("message", message)
                .putInt("chapter", chapter)
                .putBoolean("paused", paused)
                .apply()
        }
        val state = if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(message))
        sendBroadcast(Intent(EVENT).setPackage(packageName)
            .putExtra("bookId", book?.id ?: "")
            .putExtra("message", message).putExtra("chunk", index)
            .putExtra("total", parts.size).putExtra("chapter", chapter)
            .putExtra("paused", paused)
            .putExtra("segment", parts.getOrNull(index)?.text ?: ""))
    }
    private fun pending(action: String, id: Int): PendingIntent {
        val i = Intent(this, NarrationService::class.java).setAction(action)
        return PendingIntent.getService(this, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun notification(description: String): Notification {
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(book?.title ?: "VOX PHILOSOPHICA")
            .setContentText(description).setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", pending(PREVIOUS, 4))
            .addAction(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Play" else "Pause", pending(if (paused) RESUME else PAUSE, 5))
            .addAction(android.R.drawable.ic_media_next, "Next", pending(NEXT, 6))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pending(STOP, 7))
            .build()
    }
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFY_ID, notification)
    }
    private fun shutdown() {
        epoch.incrementAndGet()
        media?.release(); media = null
        busy = false
        // Release native engine on the worker's thread, after any ongoing synthesis.
        worker.execute { runCatching { engine?.close() }; engine = null }
        if (audioFocus) {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(focusListener)
            audioFocus = false
        }
    }
    override fun onDestroy() {
        getSharedPreferences("vox_playback", MODE_PRIVATE).edit()
            .putBoolean("active", false).putBoolean("paused", true).apply()
        shutdown(); worker.shutdown()
        session?.release(); session = null
        super.onDestroy()
    }
}
