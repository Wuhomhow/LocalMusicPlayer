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
    private val favoriteIds: Set<Long>,
    private val onItemClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var displaySongs: MutableList<Song> = masterSongs.toMutableList()
    private var currentQuery: String = ""
    private var showFavoritesOnly: Boolean = false

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
        val isFav = favoriteIds.contains(song.id)

        holder.tvTitle.text = if (isFav) "❤️ ${song.title}" else song.title
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
        currentQuery = query
        applyFilter()
    }

    fun setFilterFavorites(onlyFav: Boolean) {
        showFavoritesOnly = onlyFav
        applyFilter()
    }

    private fun applyFilter() {
        var list = masterSongs
        if (showFavoritesOnly) {
            list = list.filter { favoriteIds.contains(it.id) }
        }
        if (currentQuery.isNotBlank()) {
            list = list.filter {
                it.title.contains(currentQuery, ignoreCase = true) || it.artist.contains(currentQuery, ignoreCase = true)
            }
        }
        displaySongs = list.toMutableList()
        notifyDataSetChanged()
    }

    fun updateCurrentSongId(newId: Long) {
        currentSongId = newId
        notifyDataSetChanged()
    }
}
