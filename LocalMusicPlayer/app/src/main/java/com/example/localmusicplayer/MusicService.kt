package com.example.localmusicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class MusicService : Service() {

    private val binder = MusicBinder()
    var mediaPlayer: MediaPlayer? = null
    var currentTrackIndex = 0
    var isPlaying = false
    var playMode = 0 // 0: 列表循环, 1: 单曲循环, 2: 随机播放
    var currentSpeed = 1.0f

    // 睡眠定时器相关状态
    var sleepRemainingSeconds: Int = 0
    var stopAfterCurrentTrack: Boolean = false

    // 洗牌随机播放队列
    private val shuffleList = mutableListOf<Int>()
    private var shufflePointer = -1

    var onStateChangedListener: OnStateChangedListener? = null

    interface OnStateChangedListener {
        fun onTrackChanged(song: Song, index: Int)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgressUpdate(currentMs: Int, totalMs: Int)
        fun onPlaylistUpdated()
        fun onSleepTimerTick(remainingSeconds: Int)
        fun onSpeedChanged(speed: Float)
    }

    // 10 首基础内置歌曲
    val playlist = mutableListOf(
        Song(1, "爱很美", "SARA & 刘佳", rawResId = R.raw.track_ai_hen_mei),
        Song(2, "大火", "李佳薇", rawResId = R.raw.track_da_huo),
        Song(3, "可能否", "木小雅", rawResId = R.raw.track_ke_neng_fou),
        Song(4, "宁夏", "梁静茹", rawResId = R.raw.track_ning_xia),
        Song(5, "世界这么大还是遇见你", "程响", rawResId = R.raw.track_shi_jie_zhe_me_da_hai_shi_yu_jian_ni),
        Song(6, "听妈妈的话", "周杰伦", rawResId = R.raw.track_ting_ma_ma_de_hua),
        Song(7, "童话镇", "陈一发儿", rawResId = R.raw.track_tong_hua_zhen),
        Song(8, "我是如此相信", "周杰伦", rawResId = R.raw.track_wo_shi_ru_ci_xiang_xin),
        Song(9, "想见你想见你想见你", "八三夭", rawResId = R.raw.track_xiang_jian_ni),
        Song(10, "这就是爱吗？", "容祖儿", rawResId = R.raw.track_zhe_jiu_shi_ai_ma)
    )

    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (isPlaying) {
                    val cur = player.currentPosition
                    val dur = player.duration
                    onStateChangedListener?.onProgressUpdate(cur, dur)
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            if (sleepRemainingSeconds > 0) {
                sleepRemainingSeconds--
                onStateChangedListener?.onSleepTimerTick(sleepRemainingSeconds)
                if (sleepRemainingSeconds <= 0) {
                    pause()
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 启动时自动从手机本地存储中恢复上一次扫描到的歌曲列表
        loadSavedLocalSongs()

        // 恢复上一次播放的歌曲位置
        loadLastPlaybackPosition()

        resetShuffleList()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREV -> playPrevious()
            ACTION_NEXT -> playNext()
            ACTION_TOGGLE -> togglePlayPause()
        }
        return START_NOT_STICKY
    }

    fun resetShuffleList() {
        if (playlist.isEmpty()) {
            shuffleList.clear()
            shufflePointer = -1
            return
        }
        val pool = playlist.indices.toMutableList()
        pool.shuffle()
        pool.remove(currentTrackIndex)
        shuffleList.clear()
        shuffleList.add(currentTrackIndex)
        shuffleList.addAll(pool)
        shufflePointer = 0
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer?.let { player ->
                val playing = player.isPlaying
                player.playbackParams = player.playbackParams.setSpeed(currentSpeed)
                if (!playing) player.pause()
            }
        }
        onStateChangedListener?.onSpeedChanged(currentSpeed)
    }

    fun startSleepTimer(minutes: Int) {
        stopAfterCurrentTrack = false
        handler.removeCallbacks(sleepTimerRunnable)
        sleepRemainingSeconds = minutes * 60
        onStateChangedListener?.onSleepTimerTick(sleepRemainingSeconds)
        handler.post(sleepTimerRunnable)
    }

    fun setStopAfterCurrent(enable: Boolean) {
        handler.removeCallbacks(sleepTimerRunnable)
        sleepRemainingSeconds = 0
        stopAfterCurrentTrack = enable
    }

    fun cancelSleepTimer() {
        stopAfterCurrentTrack = false
        handler.removeCallbacks(sleepTimerRunnable)
        sleepRemainingSeconds = 0
        onStateChangedListener?.onSleepTimerTick(0)
    }

    fun loadTrack(index: Int, autoPlay: Boolean) {
        if (playlist.isEmpty()) return
        currentTrackIndex = (index + playlist.size) % playlist.size
        val song = playlist[currentTrackIndex]

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        try {
            if (song.uriString != null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@MusicService, Uri.parse(song.uriString))
                    prepare()
                    setOnCompletionListener { onTrackCompletion() }
                }
            } else {
                mediaPlayer = MediaPlayer.create(this, song.rawResId).apply {
                    setOnCompletionListener { onTrackCompletion() }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentSpeed != 1.0f) {
                mediaPlayer?.let { it.playbackParams = it.playbackParams.setSpeed(currentSpeed) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // 保存最后一次播放的曲目索引
        saveLastPlaybackPosition(currentTrackIndex)

        val totalMs = mediaPlayer?.duration ?: 0
        onStateChangedListener?.onTrackChanged(song, currentTrackIndex)
        onStateChangedListener?.onProgressUpdate(0, totalMs)

        if (autoPlay) {
            play()
        } else {
            pause()
        }
    }

    fun play() {
        if (playlist.isEmpty()) return
        if (mediaPlayer == null) {
            loadTrack(currentTrackIndex, autoPlay = true)
            return
        }
        mediaPlayer?.start()
        isPlaying = true
        onStateChangedListener?.onPlayStateChanged(true)
        handler.post(progressRunnable)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
        onStateChangedListener?.onPlayStateChanged(false)
        handler.removeCallbacks(progressRunnable)
        updateNotification()
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun playNext() {
        if (playlist.isEmpty()) return

        val nextIndex = when (playMode) {
            2 -> {
                if (shuffleList.size != playlist.size) {
                    resetShuffleList()
                }
                shufflePointer++
                if (shufflePointer >= shuffleList.size) {
                    resetShuffleList()
                    if (shuffleList.size > 1 && shuffleList[0] == currentTrackIndex) {
                        shufflePointer = 1
                    } else {
                        shufflePointer = 0
                    }
                }
                shuffleList[shufflePointer]
            }
            else -> (currentTrackIndex + 1) % playlist.size
        }
        loadTrack(nextIndex, autoPlay = true)
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return

        val prevIndex = when (playMode) {
            2 -> {
                if (shuffleList.size != playlist.size) {
                    resetShuffleList()
                }
                if (shufflePointer > 0) {
                    shufflePointer--
                    shuffleList[shufflePointer]
                } else {
                    shufflePointer = shuffleList.size - 1
                    shuffleList[shufflePointer]
                }
            }
            else -> if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1
        }
        loadTrack(prevIndex, autoPlay = true)
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
    }

    fun getCurrentSong(): Song? = if (playlist.isNotEmpty()) playlist[currentTrackIndex] else null

    fun addLocalSongs(newSongs: List<Song>) {
        val existingUris = playlist.mapNotNull { it.uriString }.toSet()
        val toAdd = newSongs.filter { it.uriString !in existingUris }
        if (toAdd.isNotEmpty()) {
            playlist.addAll(toAdd)
            resetShuffleList()
            // 写入本地持久化存储，下次启动不丢失
            saveLocalSongsToStorage()
            onStateChangedListener?.onPlaylistUpdated()
        }
    }

    // --- 持久化存储相关逻辑 ---
    private fun saveLocalSongsToStorage() {
        try {
            val prefs = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            playlist.filter { it.uriString != null }.forEach { song ->
                val obj = JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("uri", song.uriString)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("saved_local_songs", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSavedLocalSongs() {
        try {
            val prefs = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_local_songs", null) ?: return
            val jsonArray = JSONArray(jsonStr)
            val loaded = mutableListOf<Song>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                loaded.add(
                    Song(
                        id = obj.getLong("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        uriString = obj.getString("uri")
                    )
                )
            }
            val existingUris = playlist.mapNotNull { it.uriString }.toSet()
            val toAdd = loaded.filter { it.uriString !in existingUris }
            playlist.addAll(toAdd)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveLastPlaybackPosition(index: Int) {
        val prefs = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("last_track_index", index).apply()
    }

    private fun loadLastPlaybackPosition() {
        val prefs = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val lastIdx = prefs.getInt("last_track_index", 0)
        currentTrackIndex = if (lastIdx in playlist.indices) lastIdx else 0
    }

    private fun onTrackCompletion() {
        if (stopAfterCurrentTrack) {
            stopAfterCurrentTrack = false
            pause()
            onStateChangedListener?.onSleepTimerTick(0)
            return
        }

        when (playMode) {
            1 -> {
                mediaPlayer?.seekTo(0)
                play()
            }
            else -> {
                playNext()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "通知栏快捷控制"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = getCurrentSong() ?: Song(0, "本地音乐播放器", "无曲目")

        val openActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenActivity = PendingIntent.getActivity(
            this, 0, openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pPrev = PendingIntent.getService(
            this, 1, Intent(this, MusicService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pToggle = PendingIntent.getService(
            this, 2, Intent(this, MusicService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pNext = PendingIntent.getService(
            this, 3, Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setContentIntent(pOpenActivity)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_skip_previous, "上一首", pPrev)
            .addAction(playPauseIcon, if (isPlaying) "暂停" else "播放", pToggle)
            .addAction(R.drawable.ic_skip_next, "下一首", pNext)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(sleepTimerRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        const val CHANNEL_ID = "channel_music_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.localmusicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.localmusicplayer.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.example.localmusicplayer.ACTION_TOGGLE"
        const val ACTION_PREV = "com.example.localmusicplayer.ACTION_PREV"
        const val ACTION_NEXT = "com.example.localmusicplayer.ACTION_NEXT"
    }
}
