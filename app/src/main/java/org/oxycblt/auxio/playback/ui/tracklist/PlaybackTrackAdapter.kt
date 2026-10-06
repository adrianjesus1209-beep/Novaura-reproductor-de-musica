/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackTrackAdapter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.tracklist

import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemPlaybackTrackBinding
import org.oxycblt.auxio.list.adapter.SimpleDiffCallback
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Song

/** Adapter for the integrated inline tracklist rendered on the playback panel. */
class PlaybackTrackAdapter(private val onTrackClick: (Int) -> Unit) :
    ListAdapter<Song, PlaybackTrackViewHolder>(DIFF_CALLBACK) {

    var currentIndex: Int = -1
        set(value) {
            val old = field
            field = value
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (value in 0 until itemCount) notifyItemChanged(value)
        }

    var isPlaying: Boolean = false
        set(value) {
            field = value
            if (currentIndex in 0 until itemCount) notifyItemChanged(currentIndex)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaybackTrackViewHolder {
        val binding = ItemPlaybackTrackBinding.inflate(parent.context.inflater, parent, false)
        return PlaybackTrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaybackTrackViewHolder, position: Int) {
        val song = getItem(position)
        val isCurrent = position == currentIndex
        holder.bind(song, position, isCurrent, isPlaying, onTrackClick)
    }

    companion object {
        private val DIFF_CALLBACK =
            object : SimpleDiffCallback<Song>() {
                override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
            }
    }
}

class PlaybackTrackViewHolder(private val binding: ItemPlaybackTrackBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(
        song: Song,
        position: Int,
        isCurrent: Boolean,
        isPlaying: Boolean,
        onTrackClick: (Int) -> Unit,
    ) {
        val context = binding.root.context
        val goldColor = ContextCompat.getColor(context, R.color.player_accent_gold)
        val mutedColor = 0x80FFFFFF.toInt()

        binding.trackName.text = song.name.resolve(context)
        binding.trackSubtitle.text = song.album.name.resolve(context)
        binding.trackDuration.text = song.durationMs.formatDurationMs(false)

        if (isCurrent) {
            binding.trackNumber.isVisible = false
            binding.trackEqualizerIcon.isVisible = true
            val anim = binding.trackEqualizerIcon.drawable as? AnimationDrawable
            if (isPlaying) {
                anim?.start()
            } else {
                anim?.stop()
            }
            binding.trackName.setTextColor(goldColor)
            binding.trackDuration.setTextColor(goldColor)
        } else {
            binding.trackNumber.isVisible = true
            binding.trackNumber.text = "${position + 1}."
            binding.trackEqualizerIcon.isVisible = false
            val anim = binding.trackEqualizerIcon.drawable as? AnimationDrawable
            anim?.stop()
            binding.trackName.setTextColor(Color.WHITE)
            binding.trackDuration.setTextColor(mutedColor)
        }

        binding.root.setOnClickListener {
            val adapterPos = bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onTrackClick(adapterPos)
            }
        }
    }
}
