/*
 * Copyright (c) 2026 Auxio Project
 * DownloadAdapter.kt is part of Auxio.
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
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemDownloadSongBinding

class DownloadAdapter(
    private val onPlayClick: (OnlineSong) -> Unit,
    private val onDownloadClick: (OnlineSong) -> Unit,
    private val onAddToPlaylistClick: (OnlineSong) -> Unit,
) : ListAdapter<OnlineSong, DownloadAdapter.ViewHolder>(DiffCallback) {

    private var progressMap: Map<String, Int> = emptyMap()
    private val completedUrls = mutableSetOf<String>()
    private var currentPlayerState = OnlinePlayerState()
    private var currentPlayingUrl: String? = null
    private var currentIsPlaying: Boolean = false
    private var currentIsLoading: Boolean = false

    fun updateProgress(map: Map<String, Int>) {
        val oldMap = progressMap
        progressMap = map
        for ((url, prog) in map) {
            if (oldMap[url] != prog) {
                val idx = currentList.indexOfFirst { it.webUrl == url }
                if (idx != -1) notifyItemChanged(idx, PAYLOAD_DOWNLOAD_PROGRESS)
            }
        }
        for (url in oldMap.keys) {
            if (!map.containsKey(url)) {
                val idx = currentList.indexOfFirst { it.webUrl == url }
                if (idx != -1) notifyItemChanged(idx, PAYLOAD_DOWNLOAD_PROGRESS)
            }
        }
    }

    fun markCompleted(url: String) {
        completedUrls.add(url)
        val idx = currentList.indexOfFirst { it.webUrl == url }
        if (idx != -1) notifyItemChanged(idx, PAYLOAD_DOWNLOAD_PROGRESS)
    }

    fun updatePlayerState(state: OnlinePlayerState) {
        val newUrl = state.song?.webUrl
        val newIsPlaying = state.isPlaying
        val newIsLoading = state.isLoading

        val songChanged = currentPlayingUrl != newUrl
        val stateChanged = currentIsPlaying != newIsPlaying || currentIsLoading != newIsLoading

        if (!songChanged && !stateChanged) {
            // Only position changed - do NOT notify or rebind list items!
            return
        }

        val oldUrl = currentPlayingUrl
        currentPlayingUrl = newUrl
        currentIsPlaying = newIsPlaying
        currentIsLoading = newIsLoading
        currentPlayerState = state

        if (songChanged) {
            if (oldUrl != null) {
                val oldIdx = currentList.indexOfFirst { it.webUrl == oldUrl }
                if (oldIdx != -1) notifyItemChanged(oldIdx, PAYLOAD_PLAYBACK_STATE)
            }
            if (newUrl != null) {
                val newIdx = currentList.indexOfFirst { it.webUrl == newUrl }
                if (newIdx != -1) notifyItemChanged(newIdx, PAYLOAD_PLAYBACK_STATE)
            }
        } else if (newUrl != null) {
            val idx = currentList.indexOfFirst { it.webUrl == newUrl }
            if (idx != -1) notifyItemChanged(idx, PAYLOAD_PLAYBACK_STATE)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemDownloadSongBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val song = getItem(position)
            if (payloads.contains(PAYLOAD_PLAYBACK_STATE)) {
                holder.bindPlaybackState(song)
            }
            if (payloads.contains(PAYLOAD_DOWNLOAD_PROGRESS)) {
                holder.bindDownloadState(song)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(private val binding: ItemDownloadSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: OnlineSong) {
            binding.songTitle.text = song.title
            binding.songInfo.text = "${song.artist} • ${song.durationText}"
            ThumbnailLoader.load(binding.songThumbnail, song.thumbnailUrl)

            bindPlaybackState(song)
            bindDownloadState(song)

            binding.root.setOnClickListener {
                onPlayClick(song)
            }

            binding.songAddPlaylistButton.setOnClickListener {
                onAddToPlaylistClick(song)
            }
        }

        fun bindPlaybackState(song: OnlineSong) {
            val isCurrentSong = currentPlayingUrl == song.webUrl
            if (isCurrentSong) {
                binding.playbackOverlay.isVisible = true
                if (currentIsLoading) {
                    binding.playbackLoading.isVisible = true
                    binding.playbackIndicator.isVisible = false
                } else {
                    binding.playbackLoading.isVisible = false
                    binding.playbackIndicator.isVisible = true
                    binding.playbackIndicator.setImageResource(
                        if (currentIsPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
                    )
                }
            } else {
                binding.playbackOverlay.isVisible = false
                binding.playbackLoading.isVisible = false
                binding.playbackIndicator.isVisible = false
            }
        }

        fun bindDownloadState(song: OnlineSong) {
            val isDownloading = progressMap.containsKey(song.webUrl)
            val isCompleted = completedUrls.contains(song.webUrl)

            if (isCompleted) {
                binding.songDownloadProgress.visibility = View.GONE
                binding.songDownloadButton.visibility = View.VISIBLE
                binding.songDownloadButton.setIconResource(R.drawable.ic_check_24)
                binding.songDownloadButton.isEnabled = false
            } else if (isDownloading) {
                val progress = progressMap[song.webUrl] ?: 0
                binding.songDownloadButton.visibility = View.GONE
                binding.songDownloadProgress.visibility = View.VISIBLE
                if (progress > 0) {
                    binding.songDownloadProgress.isIndeterminate = false
                    binding.songDownloadProgress.progress = progress
                } else {
                    binding.songDownloadProgress.isIndeterminate = true
                }
            } else {
                binding.songDownloadProgress.visibility = View.GONE
                binding.songDownloadButton.visibility = View.VISIBLE
                binding.songDownloadButton.setIconResource(R.drawable.ic_download_24)
                binding.songDownloadButton.isEnabled = true
                binding.songDownloadButton.setOnClickListener {
                    onDownloadClick(song)
                }
            }
        }
    }

    companion object {
        private const val PAYLOAD_PLAYBACK_STATE = "payload_playback_state"
        private const val PAYLOAD_DOWNLOAD_PROGRESS = "payload_download_progress"
    }

    private object DiffCallback : DiffUtil.ItemCallback<OnlineSong>() {
        override fun areItemsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
            oldItem.webUrl == newItem.webUrl

        override fun areContentsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
            oldItem == newItem
    }
}
