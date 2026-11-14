/*
 * Copyright (c) 2026 Auxio Project
 * OnlineAddToPlaylistBottomSheet.kt is part of Auxio.
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

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAddToPlaylistBinding
import org.oxycblt.auxio.databinding.DialogPlaylistNameBinding
import org.oxycblt.auxio.databinding.ItemYtmPlaylistChoiceBinding
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * YouTube Music-style bottom sheet for saving one or more [OnlineSong]s to an [OnlinePlaylist] or
 * Favorites, matching the visual design and workflow of the local [AddToPlaylistDialog].
 */
@AndroidEntryPoint
class OnlineAddToPlaylistBottomSheet :
    ViewBindingBottomSheetDialogFragment<DialogAddToPlaylistBinding>() {

    private val downloadModel: DownloadViewModel by activityViewModels()
    private var songsToSave: List<OnlineSong> = emptyList()

    private val choiceAdapter = OnlinePlaylistChoiceAdapter { playlist ->
        onPlaylistSelected(playlist)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogAddToPlaylistBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogAddToPlaylistBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        songsToSave = extractSongsArgument()
        if (songsToSave.isEmpty()) {
            dismiss()
            return
        }

        val count = songsToSave.size
        binding.dialogTitle.text =
            if (count == 1) {
                getString(R.string.lbl_save_to_playlist_single)
            } else {
                getString(R.string.lbl_save_to_playlist_multiple, count)
            }

        binding.choiceRecycler.apply {
            itemAnimator = null
            adapter = choiceAdapter
        }

        binding.dialogClose.setOnClickListener { dismiss() }
        binding.btnNewPlaylist.setOnClickListener { onNewPlaylistClicked() }
        binding.itemLikedMusic.setOnClickListener { onToggleFavoritesClicked() }

        collectImmediately(downloadModel.onlinePlaylists) { playlists ->
            updateUI(playlists)
        }
    }

    override fun onDestroyBinding(binding: DialogAddToPlaylistBinding) {
        super.onDestroyBinding(binding)
        binding.choiceRecycler.adapter = null
    }

    private fun updateUI(playlists: List<OnlinePlaylist>) {
        val binding = binding ?: return
        val favPlaylist = playlists.firstOrNull {
            it.name.equals(OnlinePlaylistManager.FAVORITES_NAME, ignoreCase = true)
        }
        val favCount = favPlaylist?.songs?.size ?: 0
        binding.likedMusicCount.text =
            when (favCount) {
                0 -> getString(R.string.lbl_songs_count_zero)
                1 -> getString(R.string.lbl_songs_count_one)
                else -> getString(R.string.lbl_songs_count_many, favCount)
            }

        val favUrls = favPlaylist?.songs?.map { it.webUrl }?.toHashSet() ?: emptySet()
        val allFavorited = songsToSave.isNotEmpty() && songsToSave.all { it.webUrl in favUrls }
        binding.likedMusicCheck.isVisible = allFavorited

        val userPlaylists = playlists.filterNot {
            it.name.equals(OnlinePlaylistManager.FAVORITES_NAME, ignoreCase = true)
        }
        val targetUrls = songsToSave.map { it.webUrl }.toHashSet()
        val items = userPlaylists.map { playlist ->
            val playlistUrls = playlist.songs.map { it.webUrl }.toHashSet()
            val alreadyAdded = targetUrls.isNotEmpty() && targetUrls.all { it in playlistUrls }
            OnlinePlaylistChoiceItem(playlist, alreadyAdded)
        }
        choiceAdapter.submitList(items)
    }

    private fun onPlaylistSelected(playlist: OnlinePlaylist) {
        if (songsToSave.isEmpty()) return
        val appContext = requireContext().applicationContext
        downloadModel.addSongsToPlaylist(playlist.id, songsToSave)
        Toast.makeText(
                appContext,
                appContext.getString(R.string.msg_added_to_playlist, playlist.name),
                Toast.LENGTH_SHORT,
            )
            .show()
        dismiss()
    }

    private fun onToggleFavoritesClicked() {
        if (songsToSave.isEmpty()) return
        val appContext = requireContext().applicationContext
        val added = downloadModel.toggleOnlineFavorites(songsToSave)
        val msgRes =
            if (added) {
                R.string.msg_added_to_liked_music
            } else {
                R.string.msg_removed_from_liked_music
            }
        Toast.makeText(appContext, appContext.getString(msgRes), Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun onNewPlaylistClicked() {
        val pendingSongs = songsToSave
        if (pendingSongs.isEmpty()) return
        val suggested =
            if (pendingSongs.size > 1) {
                downloadModel.generateOnlineMixTitle(pendingSongs.firstOrNull(), null)
            } else {
                ""
            }
        showPlaylistNameDialog(
            context = requireContext(),
            inflater = layoutInflater,
            titleRes = R.string.lbl_new_playlist,
            initialName = "",
            hintText = suggested.ifBlank { getString(R.string.lbl_playlist_name_hint) },
        ) { chosenName ->
            val finalName = chosenName.ifBlank { suggested }
            if (finalName.isNotBlank()) {
                val appContext = requireContext().applicationContext
                val created = downloadModel.createOnlinePlaylist(finalName)
                downloadModel.addSongsToPlaylist(created.id, pendingSongs)
                Toast.makeText(
                        appContext,
                        appContext.getString(R.string.msg_added_to_playlist, created.name),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                dismiss()
            }
        }
    }

    private fun extractSongsArgument(): List<OnlineSong> {
        val args = arguments ?: return emptyList()
        return androidx.core.os.BundleCompat.getParcelableArrayList(
                args,
                ARG_SONGS,
                OnlineSong::class.java,
            )
            ?.toList() ?: emptyList()
    }

    private data class OnlinePlaylistChoiceItem(
        val playlist: OnlinePlaylist,
        val isAdded: Boolean,
    )

    private class OnlinePlaylistChoiceAdapter(private val onClick: (OnlinePlaylist) -> Unit) :
        ListAdapter<OnlinePlaylistChoiceItem, OnlinePlaylistChoiceAdapter.ViewHolder>(
            DiffCallback
        ) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemYtmPlaylistChoiceBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }

        class ViewHolder(private val binding: ItemYtmPlaylistChoiceBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: OnlinePlaylistChoiceItem, onClick: (OnlinePlaylist) -> Unit) {
                val context = binding.root.context
                val playlist = item.playlist

                binding.choiceCover.isVisible = false
                binding.choiceOnlineCover.isVisible = true

                val firstThumb =
                    playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                if (firstThumb.isNullOrEmpty()) {
                    binding.choiceOnlineCover.tag = null
                    binding.choiceOnlineCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.choiceOnlineCover.setImageResource(R.drawable.ic_playlist_24)
                } else {
                    binding.choiceOnlineCover.scaleType = ImageView.ScaleType.CENTER_CROP
                    ThumbnailLoader.load(binding.choiceOnlineCover, firstThumb)
                }

                binding.choiceTitle.text = playlist.name
                val count = playlist.songs.size
                binding.choiceTrackCount.text =
                    when (count) {
                        0 -> context.getString(R.string.lbl_tracks_count_zero)
                        1 -> context.getString(R.string.lbl_tracks_count_one)
                        else -> context.getString(R.string.lbl_tracks_count_many, count)
                    }
                binding.choiceCheck.isVisible = item.isAdded

                binding.root.setOnClickListener {
                    onClick(playlist)
                }
            }
        }

        object DiffCallback : DiffUtil.ItemCallback<OnlinePlaylistChoiceItem>() {
            override fun areItemsTheSame(
                oldItem: OnlinePlaylistChoiceItem,
                newItem: OnlinePlaylistChoiceItem,
            ): Boolean = oldItem.playlist.id == newItem.playlist.id

            override fun areContentsTheSame(
                oldItem: OnlinePlaylistChoiceItem,
                newItem: OnlinePlaylistChoiceItem,
            ): Boolean = oldItem == newItem
        }
    }

    companion object {
        const val TAG = "OnlineAddToPlaylistBottomSheet"
        private const val ARG_SONGS = "arg_online_songs"

        fun show(fragmentManager: FragmentManager, songs: List<OnlineSong>) {
            if (songs.isEmpty()) return
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null) return
            OnlineAddToPlaylistBottomSheet()
                .apply {
                    arguments = bundleOf(ARG_SONGS to ArrayList(songs))
                }
                .show(fragmentManager, TAG)
        }

        fun showPlaylistNameDialog(
            context: Context,
            inflater: LayoutInflater,
            titleRes: Int,
            initialName: String = "",
            hintText: String = "",
            onConfirm: (String) -> Unit,
        ) {
            val dialogBinding = DialogPlaylistNameBinding.inflate(inflater)
            dialogBinding.playlistName.apply {
                hint = hintText.ifBlank { context.getString(R.string.lbl_playlist_name_hint) }
                if (initialName.isNotEmpty()) {
                    setText(initialName)
                    setSelection(initialName.length)
                }
            }
            MaterialAlertDialogBuilder(context, R.style.Theme_Auxio_Dialog)
                .setTitle(titleRes)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.lbl_ok) { _, _ ->
                    val entered = dialogBinding.playlistName.text?.toString()?.trim().orEmpty()
                    onConfirm(entered)
                }
                .setNegativeButton(R.string.lbl_cancel, null)
                .show()
        }
    }
}
