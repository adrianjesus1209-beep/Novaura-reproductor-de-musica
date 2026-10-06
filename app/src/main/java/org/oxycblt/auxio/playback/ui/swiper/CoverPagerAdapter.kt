/*
 * Copyright (c) 2026 Auxio Project
 * CoverPagerAdapter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.swiper

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemCoverBinding
import org.oxycblt.auxio.list.adapter.FlexibleListAdapter
import org.oxycblt.auxio.list.adapter.SimpleDiffCallback
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.ui.stepper.StepperOverlay
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Song

/**
 * A [FlexibleListAdapter] that hosts [CoverViewHolder]s containing a [Song]'s cover and step
 * gesture overlays.
 *
 * @param listener The [StepperOverlay.Listener] that step gesture events will be forwarded to
 * @param audioLevelProvider Supplies the current audio level in 0..1, for the cover glow
 * @param audioReactivityProvider Supplies how strongly to react, from 0 to 1
 * @author Alexander Capehart (OxygenCobalt)
 */
class CoverPagerAdapter(
    private val listener: StepperOverlay.Listener,
    private val audioLevelProvider: () -> Float,
    private val audioReactivityProvider: () -> Float,
) : FlexibleListAdapter<Song, CoverViewHolder>(CoverViewHolder.DIFF_CALLBACK) {

    private var recyclerView: RecyclerView? = null
    private var selectedPosition = RecyclerView.NO_POSITION

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    /**
     * Tell the adapter which cover is on screen, so only that one animates its glow.
     *
     * Applied immediately to the attached holders rather than waiting for a rebind, otherwise the
     * glow would keep running on the page the user just swiped away from.
     */
    fun setSelectedPosition(position: Int) {
        if (position == selectedPosition) {
            return
        }
        selectedPosition = position
        val list = recyclerView ?: return
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? CoverViewHolder ?: continue
            holder.setGlowEnabled(holder.bindingAdapterPosition == position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, pos: Int) =
        CoverViewHolder.from(parent, audioLevelProvider, audioReactivityProvider)

    override fun onBindViewHolder(viewHolder: CoverViewHolder, pos: Int) {
        viewHolder.bind(currentList[pos], listener)
        viewHolder.setGlowEnabled(pos == selectedPosition)
    }
}

/**
 * A [RecyclerView.ViewHolder] that displays a [Song]'s cover and step gesture overlays.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class CoverViewHolder
private constructor(
    private val binding: ItemCoverBinding,
    private val audioLevelProvider: () -> Float,
    private val audioReactivityProvider: () -> Float,
) : RecyclerView.ViewHolder(binding.root) {
    /**
     * Bind new data to this instance.
     *
     * @param song The new [Song] to bind.
     * @param listener An [StepperOverlay.Listener] to bind fast seek interactions to.
     */
    fun bind(song: Song, listener: StepperOverlay.Listener) {
        binding.cover.bind(song)
        binding.coverFastSeekOverlay.listener = listener
        val context = binding.root.context
        binding.coverSongName.text = song.name.resolve(context)
        binding.coverArtistName.text = song.artists.resolveNames(context)
        binding.coverFavorite.setOnClickListener {
            val isFav = it.tag as? Boolean ?: false
            val newFav = !isFav
            it.tag = newFav
            binding.coverFavorite.setImageResource(
                if (newFav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
            )
            binding.coverFavorite.imageTintList =
                ColorStateList.valueOf(
                    if (newFav) context.getColor(R.color.player_accent_gold) else Color.WHITE
                )
        }
    }

    /**
     * Enable or disable the audio glow for this cover.
     *
     * Only the selected page should animate: each one runs its own ticker and paints a
     * full-viewport gradient, and the pager keeps the pages either side attached.
     */
    fun setGlowEnabled(enabled: Boolean) {
        binding.root.glowEnabled = enabled
    }

    init {
        binding.root.audioLevelProvider = audioLevelProvider
        binding.root.audioReactivityProvider = audioReactivityProvider
    }

    companion object {
        /**
         * Create a new instance.
         *
         * @param parent The parent to inflate this instance from.
         * @return A new instance.
         */
        fun from(
            parent: ViewGroup,
            audioLevelProvider: () -> Float,
            audioReactivityProvider: () -> Float,
        ) =
            CoverViewHolder(
                ItemCoverBinding.inflate(parent.context.inflater, parent, false),
                audioLevelProvider,
                audioReactivityProvider,
            )

        /** A comparator that can be used with DiffUtil. */
        val DIFF_CALLBACK =
            object : SimpleDiffCallback<Song>() {
                override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
            }
    }
}
