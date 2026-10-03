/*
 * Copyright (c) 2026 Auxio Project
 * MostPlayedTracksAdapter.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.home.dashboard

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemHomeMostPlayedBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.util.context
import org.oxycblt.musikr.Song

/** Horizontal list adapter for Most Played tracks in the Home dashboard. */
class MostPlayedTracksAdapter(private val onSongClick: (Song) -> Unit) :
    RecyclerView.Adapter<MostPlayedTracksAdapter.ViewHolder>() {

    private val songs = mutableListOf<Song>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<Song>) {
        songs.clear()
        songs.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemHomeMostPlayedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(songs[position])
    }

    override fun getItemCount(): Int = songs.size

    inner class ViewHolder(private val binding: ItemHomeMostPlayedBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song) {
            val context = binding.context
            binding.trackCover.bind(song)
            binding.trackTitle.text = song.name.resolve(context)
            binding.trackArtist.text = song.artists.resolveNames(context)
            binding.root.setOnClickListener { onSongClick(song) }
        }
    }
}
