package com.example.localmusicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val songs: List<Song>,
    private var currentSongIndex: Int,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

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
        val song = songs[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist
        holder.tvDuration.text = "内置"

        if (position == currentSongIndex) {
            holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.accent_green))
        } else {
            holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_primary))
        }

        holder.itemView.setOnClickListener {
            onItemClick(position)
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateCurrentIndex(newIndex: Int) {
        currentSongIndex = newIndex
        notifyDataSetChanged()
    }
}
