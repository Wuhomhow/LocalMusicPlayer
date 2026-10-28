package com.example.localmusicplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localmusicplayer.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackIndex = 0
    private var isPlaying = false

    // Playback Modes: 0 = Loop List, 1 = Single Loop, 2 = Shuffle
    private var playMode = 0

    // Vinyl Disc Rotation Animator
    private lateinit var discAnimator: ObjectAnimator

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (isPlaying) {
                    val currentPos = player.currentPosition
                    binding.seekBar.progress = currentPos
                    binding.tvCurrentTime.text = formatTime(currentPos)
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    // Built-in audio tracks list
    private val playlist = listOf(
        Song(1, "星空漫游 (Starlight)", "内置轻音乐", R.raw.track_starlight),
        Song(2, "微风轻抚 (Gentle Breeze)", "内置吉他旋律", R.raw.track_breeze),
        Song(3, "夏夜旋律 (Summer Night)", "内置放松音律", R.raw.track_summer)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDiscAnimation()
        setupListeners()
        loadTrack(currentTrackIndex, autoPlay = false)
    }

    private fun setupDiscAnimation() {
        discAnimator = ObjectAnimator.ofFloat(binding.ivVinylDisc, "rotation", 0f, 360f).apply {
            duration = 16000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun setupListeners() {
        // Play / Pause
        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        // Previous
        binding.btnPrevious.setOnClickListener {
            playPreviousTrack()
        }

        // Next
        binding.btnNext.setOnClickListener {
            playNextTrack()
        }

        // Mode switch
        binding.btnMode.setOnClickListener {
            playMode = (playMode + 1) % 3
            when (playMode) {
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

        // SeekBar dragging
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateProgressRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    mediaPlayer?.seekTo(it.progress)
                    if (isPlaying) {
                        handler.post(updateProgressRunnable)
                    }
                }
            }
        })

        // Playlist Dialog
        binding.btnPlaylist.setOnClickListener {
            showPlaylistBottomSheet()
        }
    }

    private fun loadTrack(index: Int, autoPlay: Boolean) {
        currentTrackIndex = index
        val song = playlist[currentTrackIndex]

        binding.tvSongTitle.text = song.title
        binding.tvArtist.text = song.artist

        // Release old player
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        // Initialize MediaPlayer with raw resource
        mediaPlayer = MediaPlayer.create(this, song.rawResId).apply {
            setOnCompletionListener {
                onTrackCompleted()
            }
        }

        mediaPlayer?.let { player ->
            val duration = player.duration
            binding.seekBar.max = duration
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = "00:00"
            binding.tvTotalTime.text = formatTime(duration)

            if (autoPlay) {
                startPlayback()
            } else {
                pausePlaybackUI()
            }
        }
    }

    private fun startPlayback() {
        mediaPlayer?.let { player ->
            player.start()
            isPlaying = true
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            if (discAnimator.isPaused) {
                discAnimator.resume()
            } else if (!discAnimator.isStarted) {
                discAnimator.start()
            }
            handler.post(updateProgressRunnable)
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            }
            pausePlaybackUI()
        }
    }

    private fun pausePlaybackUI() {
        isPlaying = false
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
        discAnimator.pause()
        handler.removeCallbacks(updateProgressRunnable)
    }

    private fun playNextTrack() {
        val nextIndex = when (playMode) {
            2 -> Random.nextInt(playlist.size)
            else -> (currentTrackIndex + 1) % playlist.size
        }
        loadTrack(nextIndex, autoPlay = true)
    }

    private fun playPreviousTrack() {
        val prevIndex = if (currentTrackIndex - 1 < 0) {
            playlist.size - 1
        } else {
            currentTrackIndex - 1
        }
        loadTrack(prevIndex, autoPlay = true)
    }

    private fun onTrackCompleted() {
        when (playMode) {
            1 -> {
                // Single loop: replay current song
                mediaPlayer?.seekTo(0)
                startPlayback()
            }
            2 -> {
                // Shuffle
                val randIndex = Random.nextInt(playlist.size)
                loadTrack(randIndex, autoPlay = true)
            }
            else -> {
                // Loop list
                playNextTrack()
            }
        }
    }

    private fun showPlaylistBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_playlist, null)
        val rvPlaylist = dialogView.findViewById<RecyclerView>(R.id.rvPlaylist)

        val adapter = SongAdapter(playlist, currentTrackIndex) { selectedIndex ->
            loadTrack(selectedIndex, autoPlay = true)
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
        handler.removeCallbacks(updateProgressRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
        discAnimator.cancel()
    }
}
