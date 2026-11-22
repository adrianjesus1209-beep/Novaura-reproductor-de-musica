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
    private var smartKey: String? = null
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
        smartKey = arguments?.getString(ARG_SMART_KEY)
        currentSongs =
            when {
                playlistId != null ->
                    downloadModel.onlinePlaylists.value
                        .find { it.id == playlistId }
                        ?.songs
                        .orEmpty()
                smartKey == SMART_RECENT -> downloadModel.recentOnlineSongs.value
                smartKey == SMART_MOST_PLAYED -> downloadModel.getMostPlayedOnlineSongs()
                else ->
                    arguments
                        ?.let {
                            androidx.core.os.BundleCompat.getParcelableArrayList(
                                it,
                                ARG_SONGS,
                                OnlineSong::class.java,
                            )
                        }
                        ?.toList() ?: emptyList()
            }

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
            val isFavoritesPlaylist =
                playlistId == OnlinePlaylistManager.ID_FAVORITES ||
                    playlistTitle.equals(
                        OnlinePlaylistManager.FAVORITES_NAME,
                        ignoreCase = true,
                    ) ||
                    playlistTitle.equals(getString(R.string.lbl_favorites), ignoreCase = true)
            if (firstThumb != null) {
                binding.detailCover.background = null
                binding.detailCover.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.detailCover.setPadding(0, 0, 0, 0)
                binding.detailCover.imageTintList = null
                ThumbnailLoader.load(binding.detailCover, firstThumb)
            } else if (isFavoritesPlaylist) {
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

        val observedPlaylistId = playlistId
        val observedSmartKey = smartKey
        when {
            observedPlaylistId != null -> {
                collectImmediately(downloadModel.onlinePlaylists) { playlists ->
                    val found = playlists.find { it.id == observedPlaylistId }
                    if (found != null) {
                        playlistTitle = found.name
                        renderSongs(found.songs)
                    } else if (isAdded) {
                        dismissAllowingStateLoss()
                    }
                }
            }
            observedSmartKey == SMART_RECENT -> {
                collectImmediately(downloadModel.recentOnlineSongs) { songs ->
                    renderSongs(songs)
                }
            }
            observedSmartKey == SMART_MOST_PLAYED -> {
                collectImmediately(downloadModel.onlinePlayCounts) { _ ->
                    renderSongs(downloadModel.getMostPlayedOnlineSongs())
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
        androidx.recyclerview.widget.ListAdapter<OnlineSong, DetailSongAdapter.SongViewHolder>(
            DetailDiffCallback
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
            val itemBinding =
                ItemPlaybackTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SongViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(getItem(position), position)
        }

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
                    val adapterPos = bindingAdapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
                    downloadModel.playQueue(currentSongs, adapterPos, playlistTitle)
                    dismiss()
                    if (
                        parentFragmentManager.findFragmentByTag(OnlinePlayerBottomSheet.TAG) == null
                    ) {
                        OnlinePlayerBottomSheet()
                            .show(parentFragmentManager, OnlinePlayerBottomSheet.TAG)
                    }
                }

                val showRowMenu: (View) -> Unit = { anchor ->
                    val adapterPos = bindingAdapterPosition
                    if (adapterPos != RecyclerView.NO_POSITION) {
                        val currentSong = currentSongs.getOrNull(adapterPos) ?: song
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
                                    downloadModel.playQueue(currentSongs, adapterPos, playlistTitle)
                                    dismiss()
                                    true
                                }
                                2 -> {
                                    OnlineAddToPlaylistBottomSheet.show(
                                        parentFragmentManager,
                                        listOf(currentSong),
                                    )
                                    true
                                }
                                3 -> {
                                    downloadModel.download(currentSong)
                                    Toast.makeText(
                                            requireContext(),
                                            getString(
                                                R.string.lbl_download_started,
                                                currentSong.title,
                                            ),
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                    true
                                }
                                4 -> {
                                    playlistId?.let { id ->
                                        downloadModel.removeSongFromPlaylist(id, currentSong.webUrl)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        popup.show()
                    }
                }

                itemBinding.trackDragHandle.setOnClickListener { showRowMenu(it) }
                itemBinding.root.setOnLongClickListener {
                    showRowMenu(itemBinding.trackDragHandle)
                    true
                }
            }
        }
    }

    private object DetailDiffCallback :
        androidx.recyclerview.widget.DiffUtil.ItemCallback<OnlineSong>() {
        override fun areItemsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
            oldItem.webUrl == newItem.webUrl

        override fun areContentsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
            oldItem == newItem
    }

    companion object {
        const val TAG = BuildConfig.APPLICATION_ID + ".tag.ONLINE_PLAYLIST_DETAIL"
        const val SMART_RECENT = "smart_recent"
        const val SMART_MOST_PLAYED = "smart_most_played"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SONGS = "arg_songs"
        private const val ARG_PLAYLIST_ID = "arg_playlist_id"
        private const val ARG_SMART_KEY = "arg_smart_key"

        fun show(
            fragmentManager: FragmentManager,
            title: String,
            songs: List<OnlineSong> = emptyList(),
            playlistId: String? = null,
            smartKey: String? = null,
        ) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val bundle =
                if (playlistId != null || smartKey != null) {
                    bundleOf(
                        ARG_TITLE to title,
                        ARG_PLAYLIST_ID to playlistId,
                        ARG_SMART_KEY to smartKey,
                    )
                } else {
                    bundleOf(ARG_TITLE to title, ARG_SONGS to ArrayList(songs))
                }
            OnlinePlaylistDetailBottomSheet()
                .apply { arguments = bundle }
                .show(fragmentManager, TAG)
        }
    }
}
