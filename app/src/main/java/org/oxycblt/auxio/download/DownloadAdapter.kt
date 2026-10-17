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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemDownloadSongBinding

class DownloadAdapter(private val onDownloadClick: (OnlineSong) -> Unit) :
    ListAdapter<OnlineSong, DownloadAdapter.ViewHolder>(DiffCallback) {

    private var progressMap: Map<String, Int> = emptyMap()
    private val completedUrls = mutableSetOf<String>()

    fun updateProgress(map: Map<String, Int>) {
        progressMap = map
        notifyDataSetChanged()
    }

    fun markCompleted(url: String) {
        completedUrls.add(url)
        notifyDataSetChanged()
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

    inner class ViewHolder(private val binding: ItemDownloadSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: OnlineSong) {
            binding.songTitle.text = song.title
            binding.songInfo.text = "${song.artist} • ${song.durationText}"
            ThumbnailLoader.load(binding.songThumbnail, song.thumbnailUrl)

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

    private object DiffCallback : DiffUtil.ItemCallback<OnlineSong>() {
        override fun areItemsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
            oldItem.webUrl == newItem.webUrl

        override fun areContentsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
            oldItem == newItem
    }
}
