/*
 * Copyright (c) 2026 Auxio Project
 * OnlinePlaylistAdapter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemOnlinePlaylistBinding

class OnlinePlaylistAdapter(
    private val onPlaylistClick: (OnlinePlaylist) -> Unit,
    private val onPlayClick: (OnlinePlaylist) -> Unit,
    private val onMenuClick: (OnlinePlaylist, View) -> Unit,
) : ListAdapter<OnlinePlaylist, OnlinePlaylistAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemOnlinePlaylistBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemOnlinePlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: OnlinePlaylist) {
            binding.playlistName.text = playlist.name
            val count = playlist.songs.size
            binding.playlistSongCount.text = if (count == 1) "1 canción" else "$count canciones"

            val firstCover =
                playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            if (firstCover.isNullOrEmpty()) {
                binding.playlistCover.setImageResource(R.drawable.ic_playlist_24)
            } else {
                ThumbnailLoader.load(binding.playlistCover, firstCover)
            }

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }

            binding.playlistPlayBtn.setOnClickListener {
                onPlayClick(playlist)
            }

            binding.playlistMenuBtn.setOnClickListener { view ->
                onMenuClick(playlist, view)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<OnlinePlaylist>() {
        override fun areItemsTheSame(oldItem: OnlinePlaylist, newItem: OnlinePlaylist): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OnlinePlaylist, newItem: OnlinePlaylist): Boolean =
            oldItem == newItem
    }
}
