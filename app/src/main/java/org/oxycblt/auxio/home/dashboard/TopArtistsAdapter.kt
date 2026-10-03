/*
 * Copyright (c) 2026 Auxio Project
 * TopArtistsAdapter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.dashboard

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemHomeTopArtistBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.util.context
import org.oxycblt.musikr.Artist

/** Horizontal list adapter for Top Artists in the Home dashboard. */
class TopArtistsAdapter(private val onArtistClick: (Artist) -> Unit) :
    RecyclerView.Adapter<TopArtistsAdapter.ViewHolder>() {

    private val artists = mutableListOf<Artist>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<Artist>) {
        artists.clear()
        artists.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemHomeTopArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(artists[position])
    }

    override fun getItemCount(): Int = artists.size

    inner class ViewHolder(private val binding: ItemHomeTopArtistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: Artist) {
            val context = binding.context
            binding.artistAvatar.bind(artist)
            binding.artistName.text = artist.name.resolve(context)
            binding.root.setOnClickListener { onArtistClick(artist) }
        }
    }
}
