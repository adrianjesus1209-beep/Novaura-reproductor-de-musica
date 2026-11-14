/*
 * Copyright (c) 2024 Auxio Project
 * OnlinePlaylistDetailBottomSheet.kt is part of Auxio.
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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogOnlinePlaylistDetailBinding
import org.oxycblt.auxio.databinding.ItemPlaybackTrackBinding
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collectImmediately

@AndroidEntryPoint
class OnlinePlaylistDetailBottomSheet :
    ViewBindingBottomSheetDialogFragment<DialogOnlinePlaylistDetailBinding>() {
    private val downloadModel: DownloadViewModel by activityViewModels()

    private var playlistTitle: String = ""
    private var playlistId: String? = null
    private var currentSongs: List<OnlineSong> = emptyList()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogOnlinePlaylistDetailBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogOnlinePlaylistDetailBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
        }

        playlistTitle = arguments?.getString(ARG_TITLE).orEmpty()
        playlistId = arguments?.getString(ARG_PLAYLIST_ID)
        @Suppress("DEPRECATION")
        val rawSongs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arguments?.getSerializable(ARG_SONGS, ArrayList::class.java)
            } else {
                arguments?.getSerializable(ARG_SONGS) as? ArrayList<*>
            }
        currentSongs = rawSongs?.filterIsInstance<OnlineSong>() ?: emptyList()

        binding.detailClose.setOnClickListener { dismiss() }

        val adapter = DetailSongAdapter()
        binding.detailRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.detailRecycler.adapter = adapter

        fun renderSongs(songs: List<OnlineSong>) {
            currentSongs = songs
            binding.detailTitle.text = playlistTitle
            binding.detailSubtitle.text =
                resources.getQuantityString(R.plurals.fmt_song_count, songs.size, songs.size)
            binding.detailEmpty.isVisible = songs.isEmpty()
            binding.detailRecycler.isVisible = songs.isNotEmpty()

            val firstThumb = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            if (firstThumb != null) {
                binding.detailCover.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.detailCover.setPadding(0, 0, 0, 0)
                binding.detailCover.imageTintList = null
                ThumbnailLoader.load(binding.detailCover, firstThumb)
            } else if (playlistTitle.equals("Favoritos", ignoreCase = true)) {
                val pad = (14 * resources.displayMetrics.density).toInt()
                binding.detailCover.setBackgroundResource(R.drawable.bg_liked_music_gradient)
                binding.detailCover.setImageResource(R.drawable.ic_thumb_up_24)
                binding.detailCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                binding.detailCover.setPadding(pad, pad, pad, pad)
                binding.detailCover.imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else {
                val pad = (14 * resources.displayMetrics.density).toInt()
                binding.detailCover.setBackgroundColor(0xFF2A2A2D.toInt())
                binding.detailCover.setImageResource(R.drawable.ic_playlist_24)
                binding.detailCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                binding.detailCover.setPadding(pad, pad, pad, pad)
                binding.detailCover.imageTintList = ColorStateList.valueOf(0xFF9E9EA4.toInt())
            }
            adapter.submitList(songs)
        }

        renderSongs(currentSongs)

        playlistId?.let { id ->
            collectImmediately(downloadModel.onlinePlaylists) { playlists ->
                val found = playlists.find { it.id == id }
                if (found != null) {
                    playlistTitle = found.name
                    renderSongs(found.songs)
                }
            }
        }

        binding.btnDetailPlay.setOnClickListener {
            if (currentSongs.isEmpty()) return@setOnClickListener
            downloadModel.playQueue(currentSongs, 0, playlistTitle)
            dismiss()
            if (parentFragmentManager.findFragmentByTag(OnlinePlayerBottomSheet.TAG) == null) {
                OnlinePlayerBottomSheet().show(parentFragmentManager, OnlinePlayerBottomSheet.TAG)
            }
        }

        binding.btnDetailShuffle.setOnClickListener {
            if (currentSongs.isEmpty()) return@setOnClickListener
            downloadModel.playQueue(currentSongs.shuffled(), 0, playlistTitle)
            dismiss()
            if (parentFragmentManager.findFragmentByTag(OnlinePlayerBottomSheet.TAG) == null) {
                OnlinePlayerBottomSheet().show(parentFragmentManager, OnlinePlayerBottomSheet.TAG)
            }
        }

        binding.btnDetailSave.setOnClickListener {
            if (currentSongs.isEmpty()) return@setOnClickListener
            OnlineAddToPlaylistBottomSheet.show(parentFragmentManager, currentSongs)
        }

        binding.btnDetailDownloadAll.setOnClickListener {
            if (currentSongs.isEmpty()) return@setOnClickListener
            currentSongs.forEach { song -> downloadModel.download(song) }
            Toast.makeText(
                    requireContext(),
                    getString(R.string.lbl_download_started, playlistTitle),
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    override fun onDestroyBinding(binding: DialogOnlinePlaylistDetailBinding) {
        super.onDestroyBinding(binding)
        binding.detailRecycler.adapter = null
    }

    private inner class DetailSongAdapter :
        RecyclerView.Adapter<DetailSongAdapter.SongViewHolder>() {
        private var items: List<OnlineSong> = emptyList()

        fun submitList(newItems: List<OnlineSong>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
            val itemBinding =
                ItemPlaybackTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SongViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class SongViewHolder(private val itemBinding: ItemPlaybackTrackBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(song: OnlineSong, position: Int) {
                itemBinding.trackCover.isVisible = false
                itemBinding.trackOnlineCover.isVisible = true
                ThumbnailLoader.load(itemBinding.trackOnlineCover, song.thumbnailUrl)

                itemBinding.trackName.text = song.title
                itemBinding.trackSubtitle.isVisible = true
                val subtitleText =
                    if (song.durationText.isNotBlank()) {
                        "${song.artist} • ${song.durationText}"
                    } else {
                        song.artist
                    }
                itemBinding.trackSubtitle.text = subtitleText
                itemBinding.trackNumber.isVisible = false
                itemBinding.trackDuration.isVisible = false
                itemBinding.trackPlayingScrim.isVisible = false
                itemBinding.trackEqualizerIcon.isVisible = false
                itemBinding.trackDragHandle.setImageResource(R.drawable.ic_more_vert_24)
                itemBinding.trackDragHandle.alpha = 0.7f

                itemBinding.root.setOnClickListener {
                    downloadModel.playQueue(currentSongs, position, playlistTitle)
                    dismiss()
                    if (
                        parentFragmentManager.findFragmentByTag(OnlinePlayerBottomSheet.TAG) == null
                    ) {
                        OnlinePlayerBottomSheet()
                            .show(parentFragmentManager, OnlinePlayerBottomSheet.TAG)
                    }
                }

                val showRowMenu: (View) -> Unit = { anchor ->
                    val popup = PopupMenu(anchor.context, anchor)
                    popup.menu.add(Menu.NONE, 1, 1, R.string.lbl_play)
                    popup.menu.add(Menu.NONE, 2, 2, R.string.lbl_save)
                    popup.menu.add(Menu.NONE, 3, 3, R.string.lbl_download)
                    if (playlistId != null) {
                        popup.menu.add(Menu.NONE, 4, 4, R.string.lbl_delete)
                    }
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> {
                                downloadModel.playQueue(currentSongs, position, playlistTitle)
                                dismiss()
                                true
                            }
                            2 -> {
                                OnlineAddToPlaylistBottomSheet.show(
                                    parentFragmentManager,
                                    listOf(song),
                                )
                                true
                            }
                            3 -> {
                                downloadModel.download(song)
                                Toast.makeText(
                                        requireContext(),
                                        getString(R.string.lbl_download_started, song.title),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                                true
                            }
                            4 -> {
                                playlistId?.let { id ->
                                    downloadModel.removeSongFromPlaylist(id, song.webUrl)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    popup.show()
                }

                itemBinding.trackDragHandle.setOnClickListener { showRowMenu(it) }
                itemBinding.root.setOnLongClickListener {
                    showRowMenu(itemBinding.trackDragHandle)
                    true
                }
            }
        }
    }

    companion object {
        const val TAG = BuildConfig.APPLICATION_ID + ".tag.ONLINE_PLAYLIST_DETAIL"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SONGS = "arg_songs"
        private const val ARG_PLAYLIST_ID = "arg_playlist_id"

        fun show(
            fragmentManager: FragmentManager,
            title: String,
            songs: List<OnlineSong>,
            playlistId: String? = null,
        ) {
            OnlinePlaylistDetailBottomSheet()
                .apply {
                    arguments =
                        bundleOf(
                            ARG_TITLE to title,
                            ARG_SONGS to ArrayList(songs),
                            ARG_PLAYLIST_ID to playlistId,
                        )
                }
                .show(fragmentManager, TAG)
        }
    }
}
