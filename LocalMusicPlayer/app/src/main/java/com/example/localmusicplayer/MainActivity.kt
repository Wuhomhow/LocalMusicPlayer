package com.example.localmusicplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
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

    private var lyricEntries: List<LyricEntry> = emptyList()
    private lateinit var lyricAdapter: LyricAdapter
    private var currentLyricIndex = -1
    private var isLyricsVisible = false

    private val speedList = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

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
                    loadLyricsForSong(song.title)
                    updateFavoriteButton(s.isFavorite(song.id))
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
                    syncLyricProgress(currentMs.toLong())
                }

                override fun onPlaylistUpdated() {
                    Toast.makeText(this@MainActivity, "歌单已更新，共 ${s.playlist.size} 首歌曲", Toast.LENGTH_SHORT).show()
                }

                override fun onSleepTimerTick(remainingSeconds: Int) {
                    updateSleepBadge(remainingSeconds)
                }

                override fun onSpeedChanged(speed: Float) {}

                override fun onFavoriteStateChanged(isFavorite: Boolean) {
                    updateFavoriteButton(isFavorite)
                }
            }

            s.getCurrentSong()?.let { currentSong ->
                binding.tvSongTitle.text = currentSong.title
                binding.tvArtist.text = currentSong.artist
                loadLyricsForSong(currentSong.title)
                updateFavoriteButton(s.isFavorite(currentSong.id))
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
            updateSleepBadge(s.sleepRemainingSeconds)
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

        if (savedInstanceState != null) {
            isLyricsVisible = savedInstanceState.getBoolean("is_lyrics_visible", false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setupDiscAnimation()
        setupLyricsView()
        setupListeners()
        startAndBindService()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_START -> {
                musicService?.togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                musicService?.playNext()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                musicService?.playPrevious()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                musicService?.let { s ->
                    s.playMode = (s.playMode + 1) % 3
                    updateModeIcon(s.playMode)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                toggleDiscAndLyrics(!isLyricsVisible)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_lyrics_visible", isLyricsVisible)
    }

    private fun setupLyricsView() {
        lyricAdapter = LyricAdapter()
        binding.rvLyrics.layoutManager = LinearLayoutManager(this)
        binding.rvLyrics.adapter = lyricAdapter

        toggleDiscAndLyrics(isLyricsVisible)

        binding.ivVinylDisc.setOnClickListener {
            toggleDiscAndLyrics(showLyrics = true)
        }

        binding.rvLyrics.setOnClickListener {
            toggleDiscAndLyrics(showLyrics = false)
        }
    }

    private fun toggleDiscAndLyrics(showLyrics: Boolean) {
        isLyricsVisible = showLyrics
        if (showLyrics) {
            binding.ivVinylDisc.visibility = View.GONE
            binding.rvLyrics.visibility = View.VISIBLE
            if (currentLyricIndex >= 0) {
                binding.rvLyrics.scrollToPosition(currentLyricIndex)
            }
        } else {
            binding.rvLyrics.visibility = View.GONE
            binding.ivVinylDisc.visibility = View.VISIBLE
        }
    }

    private fun loadLyricsForSong(songTitle: String) {
        lyricEntries = LyricsManager.getLyrics(songTitle)
        currentLyricIndex = -1
        lyricAdapter.setEntries(lyricEntries)
    }

    private fun syncLyricProgress(currentMs: Long) {
        if (lyricEntries.isEmpty()) return
        var activeIndex = -1
        for (i in lyricEntries.indices) {
            if (currentMs >= lyricEntries[i].timeMs) {
                activeIndex = i
            } else {
                break
            }
        }
        if (activeIndex != currentLyricIndex && activeIndex >= 0) {
            currentLyricIndex = activeIndex
            lyricAdapter.highlightIndex(currentLyricIndex)
            if (isLyricsVisible) {
                binding.rvLyrics.smoothScrollToPosition(currentLyricIndex)
            }
        }
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

        binding.btnFavorite.setOnClickListener {
            val s = musicService ?: return@setOnClickListener
            val song = s.getCurrentSong() ?: return@setOnClickListener
            val isNowFav = s.toggleFavorite(song.id)

            val pvhX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.3f, 1.0f)
            val pvhY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.3f, 1.0f)
            ObjectAnimator.ofPropertyValuesHolder(binding.btnFavorite, pvhX, pvhY).apply {
                duration = 350
                interpolator = OvershootInterpolator()
                start()
            }

            if (isNowFav) {
                Toast.makeText(this, "已加入我喜欢 ❤️", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
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

        binding.btnSettings.setOnClickListener {
            showSettingsBottomSheet()
        }

        binding.tvSleepBadge.setOnClickListener {
            showSleepTimerDialog()
        }
    }

    private fun updateFavoriteButton(isFav: Boolean) {
        if (isFav) {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite)
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
        }
    }

    private fun showSettingsBottomSheet() {
        val s = musicService ?: return
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val tvScanSubtitle = dialogView.findViewById<TextView>(R.id.tvScanSubtitle)
        val tvSleepSubtitle = dialogView.findViewById<TextView>(R.id.tvSleepSubtitle)
        val tvSpeedSubtitle = dialogView.findViewById<TextView>(R.id.tvSpeedSubtitle)
        val tvEqualizerSubtitle = dialogView.findViewById<TextView>(R.id.tvEqualizerSubtitle)

        tvScanSubtitle.text = "已载入 ${s.playlist.size} 首歌曲（内置 + 本地）"
        tvSpeedSubtitle.text = "当前播放倍速: ${s.currentSpeed}x"

        val bassPct = s.currentBassStrength / 10
        val presetName = if (s.currentPresetIndex >= 0) {
            try {
                translatePresetName(s.equalizer?.getPresetName(s.currentPresetIndex) ?: "自定义")
            } catch (e: Exception) {
                "自定义"
            }
        } else {
            "标准原声"
        }
        tvEqualizerSubtitle.text = "当前: $presetName / 重低音: $bassPct%"

        if (s.stopAfterCurrentTrack) {
            tvSleepSubtitle.text = "已开启：当前歌曲播放完毕后停止"
        } else if (s.sleepRemainingSeconds > 0) {
            val m = s.sleepRemainingSeconds / 60
            val sec = s.sleepRemainingSeconds % 60
            tvSleepSubtitle.text = String.format(Locale.getDefault(), "剩余 %02d:%02d 后自动停止", m, sec)
        } else {
            tvSleepSubtitle.text = "未开启定时"
        }

        dialogView.findViewById<View>(R.id.menuScanLocal).setOnClickListener {
            dialog.dismiss()
            checkStoragePermissionAndScan()
        }

        dialogView.findViewById<View>(R.id.menuSleepTimer).setOnClickListener {
            dialog.dismiss()
            showSleepTimerDialog()
        }

        dialogView.findViewById<View>(R.id.menuSpeed).setOnClickListener {
            dialog.dismiss()
            showSpeedDialog()
        }

        dialogView.findViewById<View>(R.id.menuEqualizer).setOnClickListener {
            dialog.dismiss()
            showEqualizerBottomSheet()
        }

        dialog.setContentView(dialogView)
        dialog.show()
    }

    private fun showEqualizerBottomSheet() {
        val s = musicService ?: return
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_equalizer, null)

        val tvBassStrength = dialogView.findViewById<TextView>(R.id.tvBassStrength)
        val sbBassBoost = dialogView.findViewById<SeekBar>(R.id.sbBassBoost)
        val rgPresets = dialogView.findViewById<RadioGroup>(R.id.rgPresets)

        sbBassBoost.progress = s.currentBassStrength.toInt()
        tvBassStrength.text = "${s.currentBassStrength / 10}%"

        sbBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    s.setBassBoostStrength(progress.toShort())
                    tvBassStrength.text = "${progress / 10}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val eq = s.equalizer
        if (eq != null) {
            val numPresets = eq.numberOfPresets
            for (i in 0 until numPresets) {
                val rawName = try {
                    eq.getPresetName(i.toShort())
                } catch (e: Exception) {
                    "音效 $i"
                }
                val localizedName = translatePresetName(rawName)

                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = localizedName
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    isChecked = (i.toShort() == s.currentPresetIndex)
                    setOnClickListener {
                        s.setEqualizerPreset(i.toShort())
                        Toast.makeText(this@MainActivity, "音效已切换为: $localizedName", Toast.LENGTH_SHORT).show()
                    }
                }
                rgPresets.addView(rb)
            }
        }

        dialog.setContentView(dialogView)
        dialog.show()
    }

    private fun translatePresetName(name: String): String {
        return when (name.lowercase()) {
            "normal" -> "标准原声 (Normal)"
            "classical" -> "古典优雅 (Classical)"
            "dance" -> "舞曲动感 (Dance)"
            "flat" -> "原声平直 (Flat)"
            "folk" -> "清澈民谣 (Folk)"
            "heavy metal" -> "重金属 (Heavy Metal)"
            "hip hop" -> "说唱嘻哈 (Hip Hop)"
            "jazz" -> "爵士乐 (Jazz)"
            "pop" -> "流行人声 (Pop)"
            "rock" -> "摇滚震撼 (Rock)"
            else -> name
        }
    }

    private fun showSpeedDialog() {
        val s = musicService ?: return
        val speedLabels = arrayOf("0.75x", "1.0x (正常)", "1.25x", "1.5x", "2.0x")
        AlertDialog.Builder(this)
            .setTitle("播放倍速调节")
            .setItems(speedLabels) { _, which ->
                val selectedSpeed = speedList[which]
                s.setSpeed(selectedSpeed)
                Toast.makeText(this, "播放倍速已设为 ${selectedSpeed}x", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 分钟", "30 分钟", "45 分钟", "60 分钟", "播完当前歌曲后停止", "关闭定时")
        AlertDialog.Builder(this)
            .setTitle("睡眠定时关闭")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> musicService?.startSleepTimer(15)
                    1 -> musicService?.startSleepTimer(30)
                    2 -> musicService?.startSleepTimer(45)
                    3 -> musicService?.startSleepTimer(60)
                    4 -> {
                        musicService?.setStopAfterCurrent(true)
                        updateSleepBadge(0)
                        Toast.makeText(this, "将在当前歌曲播放完毕后停止", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        musicService?.cancelSleepTimer()
                        updateSleepBadge(0)
                        Toast.makeText(this, "已取消定时关闭", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun updateSleepBadge(remainingSeconds: Int) {
        val s = musicService
        if (s != null && s.stopAfterCurrentTrack) {
            binding.tvSleepBadge.visibility = View.VISIBLE
            binding.tvSleepBadge.text = "播完停"
            return
        }
        if (remainingSeconds > 0) {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            binding.tvSleepBadge.visibility = View.VISIBLE
            binding.tvSleepBadge.text = String.format(Locale.getDefault(), "⏱️ %02d:%02d", minutes, seconds)
        } else {
            binding.tvSleepBadge.visibility = View.GONE
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
            0 -> binding.btnMode.setImageResource(R.drawable.ic_repeat)
            1 -> binding.btnMode.setImageResource(R.drawable.ic_repeat_one)
            2 -> binding.btnMode.setImageResource(R.drawable.ic_shuffle)
        }
    }

    // 播放列表底栏（支持长按弹出确认移除对话框）
    private fun showPlaylistBottomSheet() {
        val s = musicService ?: return
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_playlist, null)
        val rvPlaylist = dialogView.findViewById<RecyclerView>(R.id.rvPlaylist)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchSong)
        val btnTabAll = dialogView.findViewById<Button>(R.id.btnTabAll)
        val btnTabFavorites = dialogView.findViewById<Button>(R.id.btnTabFavorites)

        val currentId = s.getCurrentSong()?.id ?: -1L
        lateinit var adapter: SongAdapter
        adapter = SongAdapter(
            masterSongs = s.playlist,
            currentSongId = currentId,
            favoriteIds = s.favoriteIds,
            onItemClick = { selectedSong ->
                val actualIdx = s.playlist.indexOfFirst { it.id == selectedSong.id }
                if (actualIdx >= 0) {
                    s.loadTrack(actualIdx, autoPlay = true)
                }
                dialog.dismiss()
            },
            onItemLongClick = { songToRemove ->
                AlertDialog.Builder(this)
                    .setTitle("从歌单中移除")
                    .setMessage("确定要将《${songToRemove.title}》从当前播放列表中移除吗？")
                    .setPositiveButton("确认移除") { _, _ ->
                        s.removeSong(songToRemove)
                        btnTabAll.text = "全部 (${s.playlist.size})"
                        btnTabFavorites.text = "❤️ 我喜欢 (${s.favoriteIds.size})"
                        adapter.filter(etSearch.text?.toString().orEmpty())
                        Toast.makeText(this, "已移除《${songToRemove.title}》", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )

        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = adapter

        btnTabAll.text = "全部 (${s.playlist.size})"
        btnTabFavorites.text = "❤️ 我喜欢 (${s.favoriteIds.size})"

        btnTabAll.setOnClickListener {
            adapter.setFilterFavorites(false)
            btnTabAll.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnTabFavorites.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        btnTabFavorites.setOnClickListener {
            adapter.setFilterFavorites(true)
            btnTabFavorites.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnTabAll.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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

    inner class LyricAdapter : RecyclerView.Adapter<LyricAdapter.LyricViewHolder>() {
        private var entries: List<LyricEntry> = emptyList()
        private var highlightedPosition = -1

        fun setEntries(newEntries: List<LyricEntry>) {
            this.entries = newEntries
            this.highlightedPosition = -1
            notifyDataSetChanged()
        }

        fun highlightIndex(index: Int) {
            val oldPos = highlightedPosition
            highlightedPosition = index
            if (oldPos >= 0) notifyItemChanged(oldPos)
            if (highlightedPosition >= 0) notifyItemChanged(highlightedPosition)
        }

        inner class LyricViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false) as TextView
            view.setOnClickListener {
                toggleDiscAndLyrics(showLyrics = false)
            }
            return LyricViewHolder(view)
        }

        override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
            val entry = entries[position]
            holder.textView.text = entry.text
            if (position == highlightedPosition) {
                holder.textView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                holder.textView.textSize = 18f
                holder.textView.typeface = Typeface.DEFAULT_BOLD
            } else {
                holder.textView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                holder.textView.textSize = 14f
                holder.textView.typeface = Typeface.DEFAULT
            }
        }

        override fun getItemCount(): Int = entries.size
    }
}
