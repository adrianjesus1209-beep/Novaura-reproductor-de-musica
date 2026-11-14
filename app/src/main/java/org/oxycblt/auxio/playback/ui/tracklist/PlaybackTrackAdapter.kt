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
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemPlaybackTrackBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/** Adapter for the integrated inline tracklist rendered on the playback panel. */
class PlaybackTrackAdapter(
    private val onTrackClick: (Int, Song) -> Unit,
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

    var currentSongUid: Music.UID? = null
        set(value) {
            if (field == value) return
            val oldUid = field
            field = value
            for (i in currentTracks.indices) {
                val uid = currentTracks[i].uid
                if (uid == oldUid || uid == value) {
                    notifyItemChanged(i)
                }
            }
        }

    var isPlaying: Boolean = false
        set(value) {
            field = value
            for (i in currentTracks.indices) {
                if (i == currentIndex || currentTracks[i].uid == currentSongUid) {
                    notifyItemChanged(i)
                }
            }
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
        val isCurrent =
            if (
                currentIndex in currentTracks.indices &&
                    (currentSongUid == null || currentTracks[currentIndex].uid == currentSongUid)
            ) {
                position == currentIndex
            } else {
                currentSongUid != null && song.uid == currentSongUid
            }
        holder.bind(
            song,
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

    private val selectableBgResId: Int by lazy {
        val typedValue = TypedValue()
        binding.root.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            typedValue,
            true,
        )
        typedValue.resourceId
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        song: Song,
        isCurrent: Boolean,
        isPlaying: Boolean,
        onTrackClick: (Int, Song) -> Unit,
        onTrackLongClick: (Song) -> Unit,
        onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    ) {
        val context = binding.root.context

        binding.trackCover.isVisible = true
        binding.trackOnlineCover.isVisible = false
        binding.trackCover.bind(song)
        binding.trackNumber.isVisible = false
        binding.trackDuration.isVisible = false

        binding.trackName.text = song.name.resolve(context)
        binding.trackSubtitle.text =
            "${song.artists.resolveNames(context)} • ${song.durationMs.formatDurationMs(false)}"

        val currentAnim = binding.trackEqualizerIcon.drawable as? AnimationDrawable
        if (isCurrent) {
            binding.root.setBackgroundColor(0x1FFFFFFF)
            binding.trackPlayingScrim.isVisible = true
            binding.trackEqualizerIcon.isVisible = true
            if (isPlaying) {
                binding.trackEqualizerIcon.setImageResource(R.drawable.ic_playing_indicator_24)
                val playingAnim = binding.trackEqualizerIcon.drawable as? AnimationDrawable
                playingAnim?.start()
            } else {
                currentAnim?.stop()
                binding.trackEqualizerIcon.setImageResource(R.drawable.ic_paused_indicator_24)
            }
            binding.trackName.setTextColor(Color.WHITE)
        } else {
            if (selectableBgResId != 0) {
                binding.root.setBackgroundResource(selectableBgResId)
            } else {
                binding.root.setBackgroundColor(Color.TRANSPARENT)
            }
            binding.trackPlayingScrim.isVisible = false
            binding.trackEqualizerIcon.isVisible = false
            currentAnim?.stop()
            binding.trackName.setTextColor(Color.WHITE)
        }

        binding.root.setOnClickListener {
            val adapterPos = bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onTrackClick(adapterPos, song)
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
