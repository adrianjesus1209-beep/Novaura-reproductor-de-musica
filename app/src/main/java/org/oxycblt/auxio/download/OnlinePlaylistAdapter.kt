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

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
            val context = binding.root.context
            binding.playlistName.text = playlist.name
            val count = playlist.songs.size
            binding.playlistSongCount.text =
                context.resources.getQuantityString(R.plurals.fmt_song_count, count, count)

            val firstCover =
                playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            if (firstCover.isNullOrEmpty()) {
                val pad = (14 * context.resources.displayMetrics.density).toInt()
                binding.playlistCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                binding.playlistCover.setPadding(pad, pad, pad, pad)
                binding.playlistCover.imageTintList = ColorStateList.valueOf(0xFF9E9EA4.toInt())
                binding.playlistCover.setImageResource(R.drawable.ic_playlist_24)
            } else {
                binding.playlistCover.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.playlistCover.setPadding(0, 0, 0, 0)
                binding.playlistCover.imageTintList = null
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
