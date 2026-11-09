/*
 * Copyright (c) 2023 Auxio Project
 * AddToPlaylistDialog.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAddToPlaylistBinding
import org.oxycblt.auxio.list.ClickableListListener
import org.oxycblt.auxio.music.FavoritesManager
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.PlaylistDecision
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A bottom sheet dialog that allows the user to pick a specific playlist or favorites to add
 * song(s) to, styled after YouTube Music's playlist selection bottom sheet.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AddToPlaylistDialog :
    ViewBindingBottomSheetDialogFragment<DialogAddToPlaylistBinding>(),
    ClickableListListener<PlaylistChoice> {
    private val musicModel: MusicViewModel by activityViewModels()
    private val pickerModel: PlaylistPickerViewModel by viewModels()
    @Inject lateinit var favoritesManager: FavoritesManager

    private val args: AddToPlaylistDialogArgs by navArgs()
    private val choiceAdapter = PlaylistChoiceAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogAddToPlaylistBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogAddToPlaylistBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.choiceRecycler.apply {
            itemAnimator = null
            adapter = choiceAdapter
        }

        binding.dialogClose.setOnClickListener { dismiss() }
        binding.btnNewPlaylist.setOnClickListener { onNewPlaylist() }
        binding.itemLikedMusic.setOnClickListener { onToggleFavorites() }

        // --- VIEWMODEL SETUP ---
        pickerModel.setSongsToAdd(args.songUids)
        musicModel.playlistDecision.consume()
        collectImmediately(pickerModel.currentSongsToAdd, ::updatePendingSongs)
        collectImmediately(pickerModel.playlistAddChoices, ::updatePlaylistChoices)
        collectImmediately(favoritesManager.favoriteUids) { updateFavoritesUI() }
    }

    override fun onDestroyBinding(binding: DialogAddToPlaylistBinding) {
        super.onDestroyBinding(binding)
        binding.choiceRecycler.adapter = null
    }

    override fun onClick(item: PlaylistChoice, viewHolder: RecyclerView.ViewHolder) {
        val songs = pickerModel.currentSongsToAdd.value ?: return
        musicModel.addToPlaylist(songs, item.playlist)
        val playlistName = item.playlist.name.resolve(requireContext())
        Toast.makeText(
                requireContext(),
                getString(R.string.msg_added_to_playlist, playlistName),
                Toast.LENGTH_SHORT,
            )
            .show()
        dismiss()
    }

    private fun onNewPlaylist() {
        val songs = pickerModel.currentSongsToAdd.value ?: return
        findNavController()
            .navigateSafe(
                AddToPlaylistDialogDirections.newPlaylist(
                    songs.map { it.uid }.toTypedArray(),
                    null,
                    PlaylistDecision.New.Reason.ADD,
                )
            )
    }

    private fun onToggleFavorites() {
        val songs = pickerModel.currentSongsToAdd.value ?: return
        val context = requireContext()
        if (songs.size == 1) {
            val song = songs.first()
            val isFav = favoritesManager.toggleFavorite(song)
            val msg =
                if (isFav) {
                    getString(R.string.msg_added_to_liked_music)
                } else {
                    getString(R.string.msg_removed_from_liked_music)
                }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else {
            for (song in songs) {
                favoritesManager.setFavorite(song.uid, true)
            }
            Toast.makeText(
                    context,
                    getString(R.string.msg_added_to_liked_music),
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
        dismiss()
    }

    private fun updatePendingSongs(songs: List<Song>?) {
        if (songs == null) {
            L.d("No songs to show choices for, dismissing")
            dismiss()
            return
        }
        val count = songs.size
        val title =
            when (count) {
                1 -> getString(R.string.lbl_save_to_playlist_single)
                else -> getString(R.string.lbl_save_to_playlist_multiple, count)
            }
        binding?.dialogTitle?.text = title
        updateFavoritesUI()
    }

    private fun updateFavoritesUI() {
        val binding = binding ?: return
        val favCount = favoritesManager.favoriteUids.value.size
        val countStr =
            when (favCount) {
                0 -> getString(R.string.lbl_songs_count_zero)
                1 -> getString(R.string.lbl_songs_count_one)
                else -> getString(R.string.lbl_songs_count_many, favCount)
            }
        binding.likedMusicCount.text = countStr
        val songs = pickerModel.currentSongsToAdd.value
        val allFavorited =
            songs != null && songs.isNotEmpty() && songs.all { favoritesManager.isFavorite(it) }
        binding.likedMusicCheck.isVisible = allFavorited
    }

    private fun updatePlaylistChoices(choices: List<PlaylistChoice>) {
        choiceAdapter.update(choices, null)
    }
}
