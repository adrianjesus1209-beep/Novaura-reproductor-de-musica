/*
 * Copyright (c) 2023 Auxio Project
 * PlaylistChoiceAdapter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.decision

import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemYtmPlaylistChoiceBinding
import org.oxycblt.auxio.list.ClickableListListener
import org.oxycblt.auxio.list.adapter.FlexibleListAdapter
import org.oxycblt.auxio.list.adapter.SimpleDiffCallback
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.inflater

/**
 * A [FlexibleListAdapter] that displays a list of [PlaylistChoice] options to select from in
 * [AddToPlaylistDialog].
 *
 * @param listener [ClickableListListener] to bind interactions to.
 */
class PlaylistChoiceAdapter(val listener: ClickableListListener<PlaylistChoice>) :
    FlexibleListAdapter<PlaylistChoice, PlaylistChoiceViewHolder>(
        PlaylistChoiceViewHolder.DIFF_CALLBACK
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PlaylistChoiceViewHolder.from(parent)

    override fun onBindViewHolder(holder: PlaylistChoiceViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }
}

/**
 * A [RecyclerView.ViewHolder] that displays an individual playlist choice. Use [from] to create an
 * instance.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class PlaylistChoiceViewHolder
private constructor(private val binding: ItemYtmPlaylistChoiceBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(choice: PlaylistChoice, listener: ClickableListListener<PlaylistChoice>) {
        listener.bind(choice, this)
        val context = binding.context
        binding.choiceCover.apply {
            bind(choice.playlist)
            isActivated = choice.alreadyAdded
        }
        binding.choiceTitle.text = choice.playlist.name.resolve(context)
        val count = choice.playlist.songs.size
        binding.choiceTrackCount.text =
            when (count) {
                0 -> context.getString(R.string.lbl_tracks_count_zero)
                1 -> context.getString(R.string.lbl_tracks_count_one)
                else -> context.getString(R.string.lbl_tracks_count_many, count)
            }
        binding.choiceCheck.isVisible = choice.alreadyAdded
    }

    companion object {
        /**
         * Create a new instance.
         *
         * @param parent The parent to inflate this instance from.
         * @return A new instance.
         */
        fun from(parent: ViewGroup) =
            PlaylistChoiceViewHolder(
                ItemYtmPlaylistChoiceBinding.inflate(parent.context.inflater, parent, false)
            )

        /** A comparator that can be used with DiffUtil. */
        val DIFF_CALLBACK =
            object : SimpleDiffCallback<PlaylistChoice>() {
                override fun areContentsTheSame(oldItem: PlaylistChoice, newItem: PlaylistChoice) =
                    oldItem.playlist.name == newItem.playlist.name &&
                        oldItem.alreadyAdded == newItem.alreadyAdded &&
                        oldItem.playlist.songs.size == newItem.playlist.songs.size
            }
    }
}
