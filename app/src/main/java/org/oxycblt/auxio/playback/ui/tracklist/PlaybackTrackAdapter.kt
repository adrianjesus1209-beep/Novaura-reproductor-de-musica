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

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemPlaybackTrackBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Song

/** Adapter for the integrated inline tracklist rendered on the playback panel. */
class PlaybackTrackAdapter(
    private val onTrackClick: (Int) -> Unit,
    private val onTrackLongClick: (Song) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<PlaybackTrackViewHolder>() {

    private var currentTracks: MutableList<Song> = mutableListOf()

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

    fun submitList(songs: List<Song>?) {
        val newTracks = songs?.toMutableList() ?: mutableListOf()
        if (currentTracks == newTracks) return
        val diffResult =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = currentTracks.size

                    override fun getNewListSize() = newTracks.size

                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        currentTracks[oldItemPosition] == newTracks[newItemPosition]

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        currentTracks[oldItemPosition] == newTracks[newItemPosition]
                }
            )
        currentTracks = newTracks
        diffResult.dispatchUpdatesTo(this)
    }

    fun moveItem(from: Int, to: Int) {
        if (from in currentTracks.indices && to in currentTracks.indices && from != to) {
            val item = currentTracks.removeAt(from)
            currentTracks.add(to, item)
            if (currentIndex == from) {
                currentIndex = to
            } else if (from < currentIndex && to >= currentIndex) {
                currentIndex--
            } else if (from > currentIndex && to <= currentIndex) {
                currentIndex++
            }
            notifyItemMoved(from, to)
        }
    }

    override fun getItemCount(): Int = currentTracks.size

    fun getItem(position: Int): Song = currentTracks[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaybackTrackViewHolder {
        val binding = ItemPlaybackTrackBinding.inflate(parent.context.inflater, parent, false)
        return PlaybackTrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaybackTrackViewHolder, position: Int) {
        val song = currentTracks[position]
        val isCurrent = position == currentIndex
        holder.bind(
            song,
            position,
            isCurrent,
            isPlaying,
            onTrackClick,
            onTrackLongClick,
            onStartDrag,
        )
    }
}

class PlaybackTrackViewHolder(private val binding: ItemPlaybackTrackBinding) :
    RecyclerView.ViewHolder(binding.root) {

    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        song: Song,
        position: Int,
        isCurrent: Boolean,
        isPlaying: Boolean,
        onTrackClick: (Int) -> Unit,
        onTrackLongClick: (Song) -> Unit,
        onStartDrag: (RecyclerView.ViewHolder) -> Unit,
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

        binding.root.setOnLongClickListener {
            onTrackLongClick(song)
            true
        }

        binding.trackDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(this)
            }
            false
        }
    }
}
