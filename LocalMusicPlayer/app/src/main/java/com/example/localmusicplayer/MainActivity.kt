package com.example.localmusicplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

            // 监听服务状态回调
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
            }

            // 初始化界面状态
            val currentSong = s.getCurrentSong()
            binding.tvSongTitle.text = currentSong.title
            binding.tvArtist.text = currentSong.artist
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

        // 请求 Android 13+ 通知栏权限
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
        // 播放 / 暂停
        binding.btnPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        // 上一首
        binding.btnPrevious.setOnClickListener {
            musicService?.playPrevious()
        }

        // 下一首
        binding.btnNext.setOnClickListener {
            musicService?.playNext()
        }

        // 播放模式切换
        binding.btnMode.setOnClickListener {
            musicService?.let { s ->
                s.playMode = (s.playMode + 1) % 3
                updateModeIcon(s.playMode)
            }
        }

        // 进度条拖拽
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

        // 弹出歌单
        binding.btnPlaylist.setOnClickListener {
            showPlaylistBottomSheet()
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
