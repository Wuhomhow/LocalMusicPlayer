package com.example.localmusicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val masterSongs: List<Song>,
    private var currentSongId: Long,
    private val onItemClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var displaySongs: MutableList<Song> = masterSongs.toMutableList()

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvItemArtist)
        val tvDuration: TextView = itemView.findViewById(R.id.tvItemDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = displaySongs[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist
        holder.tvDuration.text = if (song.rawResId != 0) "内置" else "本地"

        if (song.id == currentSongId) {
            holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.accent_green))
        } else {
            holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_primary))
        }

        holder.itemView.setOnClickListener {
            onItemClick(song)
        }
    }

    override fun getItemCount(): Int = displaySongs.size

    fun filter(query: String) {
        displaySongs = if (query.isBlank()) {
            masterSongs.toMutableList()
        } else {
            masterSongs.filter {
                it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }

    fun updateCurrentSongId(newId: Long) {
        currentSongId = newId
        notifyDataSetChanged()
    }
}
