/*
 * Copyright (c) 2026 Auxio Project
 * DownloadQueueAdapter.kt is part of Auxio.
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
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemDownloadQueueBinding

class DownloadQueueAdapter(
    private val onPauseResumeClick: (DownloadItem) -> Unit,
    private val onCancelClick: (DownloadItem) -> Unit,
) : ListAdapter<DownloadItem, DownloadQueueAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemDownloadQueueBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemDownloadQueueBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            val context = binding.root.context
            binding.queueSongTitle.text = item.song.title

            ThumbnailLoader.load(binding.queueSongCover, item.song.thumbnailUrl)

            binding.queueSongProgress.progress = item.progress

            when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    binding.queueSongStatus.text =
                        String.format(
                            "%d%% • %s",
                            item.progress,
                            context.getString(R.string.lbl_status_downloading),
                        )
                    binding.queueSongProgress.isVisible = true
                    binding.queueSongActionBtn.setIconResource(R.drawable.ic_pause_24)
                    binding.queueSongActionBtn.isVisible = true
                }
                DownloadStatus.PAUSED -> {
                    binding.queueSongStatus.text =
                        String.format(
                            "%d%% • %s",
                            item.progress,
                            context.getString(R.string.lbl_status_paused),
                        )
                    binding.queueSongProgress.isVisible = true
                    binding.queueSongActionBtn.setIconResource(R.drawable.ic_play_24)
                    binding.queueSongActionBtn.isVisible = true
                }
                DownloadStatus.COMPLETED -> {
                    binding.queueSongStatus.text = context.getString(R.string.lbl_status_completed)
                    binding.queueSongProgress.progress = 100
                    binding.queueSongProgress.isVisible = false
                    binding.queueSongActionBtn.isVisible = false
                }
                DownloadStatus.ERROR -> {
                    binding.queueSongStatus.text =
                        item.errorMessage ?: context.getString(R.string.lbl_status_error)
                    binding.queueSongProgress.isVisible = false
                    binding.queueSongActionBtn.setIconResource(R.drawable.ic_refresh_24)
                    binding.queueSongActionBtn.isVisible = true
                }
                DownloadStatus.QUEUED -> {
                    binding.queueSongStatus.text = context.getString(R.string.lbl_status_queued)
                    binding.queueSongProgress.isVisible = true
                    binding.queueSongActionBtn.setIconResource(R.drawable.ic_pause_24)
                    binding.queueSongActionBtn.isVisible = true
                }
                DownloadStatus.CANCELLED -> {
                    binding.queueSongStatus.text = context.getString(R.string.lbl_cancel)
                    binding.queueSongProgress.isVisible = false
                    binding.queueSongActionBtn.isVisible = false
                }
            }

            binding.queueSongActionBtn.setOnClickListener {
                onPauseResumeClick(item)
            }

            binding.queueSongCancelBtn.setOnClickListener {
                onCancelClick(item)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem.song.webUrl == newItem.song.webUrl

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem == newItem
    }
}
