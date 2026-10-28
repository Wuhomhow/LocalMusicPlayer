package com.example.localmusicplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localmusicplayer.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var musicService: MusicService? = null
    private var isBound = false

    private lateinit var discAnimator: ObjectAnimator

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            val s = binder.getService()
            musicService = s
            isBound = true

            s.onStateChangedListener = object : MusicService.OnStateChangedListener {
                override fun onTrackChanged(song: Song, index: Int) {
                    binding.tvSongTitle.text = song.title
                    binding.tvArtist.text = song.artist
                }

                override fun onPlayStateChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                        if (discAnimator.isPaused) discAnimator.resume() else if (!discAnimator.isStarted) discAnimator.start()
                    } else {
                        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                        discAnimator.pause()
                    }
                }

                override fun onProgressUpdate(currentMs: Int, totalMs: Int) {
                    binding.seekBar.max = totalMs
                    binding.seekBar.progress = currentMs
                    binding.tvCurrentTime.text = formatTime(currentMs)
                    binding.tvTotalTime.text = formatTime(totalMs)
                }

                override fun onPlaylistUpdated() {
                    Toast.makeText(this@MainActivity, "歌单已更新，共 ${s.playlist.size} 首歌曲", Toast.LENGTH_SHORT).show()
                }

                override fun onSleepTimerTick(remainingSeconds: Int) {
                    updateSleepTimerButton(remainingSeconds)
                }
            }

            s.getCurrentSong()?.let { currentSong ->
                binding.tvSongTitle.text = currentSong.title
                binding.tvArtist.text = currentSong.artist
            }
            val curPos = s.mediaPlayer?.currentPosition ?: 0
            val dur = s.mediaPlayer?.duration ?: 0
            binding.seekBar.max = dur
            binding.seekBar.progress = curPos
            binding.tvCurrentTime.text = formatTime(curPos)
            binding.tvTotalTime.text = formatTime(dur)

            if (s.isPlaying) {
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                if (!discAnimator.isStarted) discAnimator.start()
            }
            updateModeIcon(s.playMode)

            // 同步定时关闭按钮状态
            if (s.stopAfterCurrentTrack) {
                binding.btnSleepTimer.text = "播完停"
                binding.btnSleepTimer.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
            } else {
                updateSleepTimerButton(s.sleepRemainingSeconds)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 请求通知栏权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setupDiscAnimation()
        setupListeners()
        startAndBindService()
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupDiscAnimation() {
        discAnimator = ObjectAnimator.ofFloat(binding.ivVinylDisc, "rotation", 0f, 360f).apply {
            duration = 16000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        binding.btnPrevious.setOnClickListener {
            musicService?.playPrevious()
        }

        binding.btnNext.setOnClickListener {
            musicService?.playNext()
        }

        binding.btnMode.setOnClickListener {
            musicService?.let { s ->
                s.playMode = (s.playMode + 1) % 3
                updateModeIcon(s.playMode)
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    musicService?.seekTo(it.progress)
                }
            }
        })

        binding.btnPlaylist.setOnClickListener {
            showPlaylistBottomSheet()
        }

        binding.btnScanLocal.setOnClickListener {
            checkStoragePermissionAndScan()
        }

        // 定时关闭按钮点击弹出选项
        binding.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 分钟", "30 分钟", "45 分钟", "60 分钟", "播完当前歌曲后停止", "关闭定时")
        AlertDialog.Builder(this)
            .setTitle("睡眠定时关闭")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        musicService?.startSleepTimer(15)
                        Toast.makeText(this, "将在 15 分钟后停止播放", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        musicService?.startSleepTimer(30)
                        Toast.makeText(this, "将在 30 分钟后停止播放", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        musicService?.startSleepTimer(45)
                        Toast.makeText(this, "将在 45 分钟后停止播放", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        musicService?.startSleepTimer(60)
                        Toast.makeText(this, "将在 60 分钟后停止播放", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        musicService?.setStopAfterCurrent(true)
                        binding.btnSleepTimer.text = "播完停"
                        binding.btnSleepTimer.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                        Toast.makeText(this, "将在当前歌曲播放完毕后停止", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        musicService?.cancelSleepTimer()
                        binding.btnSleepTimer.text = "定时关闭"
                        binding.btnSleepTimer.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        Toast.makeText(this, "已取消定时关闭", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun updateSleepTimerButton(remainingSeconds: Int) {
        if (remainingSeconds > 0) {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            binding.btnSleepTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            binding.btnSleepTimer.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.btnSleepTimer.text = "定时关闭"
            binding.btnSleepTimer.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun checkStoragePermissionAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            scanDeviceAudioFiles()
        } else {
            requestPermissions(arrayOf(permission), 202)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanDeviceAudioFiles()
        }
    }

    private fun scanDeviceAudioFiles() {
        val localList = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.DURATION} >= 30000"

        try {
            contentResolver.query(uri, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "未知曲目"
                    val artist = cursor.getString(artistCol) ?: "本地音频"
                    val contentUri = ContentUris.withAppendedId(uri, id).toString()
                    localList.add(Song(id = id, title = title, artist = artist, uriString = contentUri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        if (localList.isEmpty()) {
            Toast.makeText(this, "未在手机存储中找到大于30秒的歌曲", Toast.LENGTH_SHORT).show()
        } else {
            musicService?.addLocalSongs(localList)
        }
    }

    private fun updateModeIcon(mode: Int) {
        when (mode) {
            0 -> {
                binding.btnMode.setImageResource(R.drawable.ic_repeat)
                Toast.makeText(this, "列表循环", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                binding.btnMode.setImageResource(R.drawable.ic_repeat_one)
                Toast.makeText(this, "单曲循环", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                binding.btnMode.setImageResource(R.drawable.ic_shuffle)
                Toast.makeText(this, "随机播放", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistBottomSheet() {
        val s = musicService ?: return
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_playlist, null)
        val rvPlaylist = dialogView.findViewById<RecyclerView>(R.id.rvPlaylist)

        val adapter = SongAdapter(s.playlist, s.currentTrackIndex) { selectedIndex ->
            s.loadTrack(selectedIndex, autoPlay = true)
            dialog.dismiss()
        }

        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = adapter

        dialog.setContentView(dialogView)
        dialog.show()
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            musicService?.onStateChangedListener = null
            unbindService(serviceConnection)
            isBound = false
        }
        discAnimator.cancel()
    }
}
