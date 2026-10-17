/*
 * Copyright (c) 2026 Auxio Project
 * OnlinePlayerBottomSheet.kt is part of Auxio.
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

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogOnlinePlayerBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.util.showToast

@AndroidEntryPoint
class OnlinePlayerBottomSheet : BottomSheetDialogFragment(), StyledSeekBar.Listener {

    private var _binding: DialogOnlinePlayerBinding? = null
    private val binding
        get() = _binding!!

    private val downloadModel: DownloadViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet =
                dialog.findViewById<FrameLayout>(
                    com.google.android.material.R.id.design_bottom_sheet
                )
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogOnlinePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.onlinePlayerSeekBar.listener = this

        binding.onlinePlayerCloseBtn.setOnClickListener {
            dismiss()
        }

        binding.onlinePlayerStopBtn.setOnClickListener {
            downloadModel.stopOnlinePlayback()
            dismiss()
        }

        binding.onlinePlayerPlayPauseFab.setOnClickListener {
            if (!downloadModel.playerState.value.isPlaying && playbackModel.isPlaying.value) {
                playbackModel.togglePlaying()
            }
            downloadModel.togglePlayPauseOnline()
        }

        binding.onlinePlayerNextBtn.setOnClickListener {
            downloadModel.playNextOnline()
        }

        binding.onlinePlayerPrevBtn.setOnClickListener {
            downloadModel.playPrevOnline()
        }

        binding.onlinePlayerShuffleBtn.setOnClickListener {
            val isShuffle = downloadModel.toggleShuffle()
            updateShuffleState(isShuffle)
        }

        binding.onlinePlayerRepeatBtn.setOnClickListener {
            val isRepeat = downloadModel.toggleRepeat()
            updateRepeatState(isRepeat)
        }

        binding.onlinePlayerFullDownloadBtn.setOnClickListener {
            val song = downloadModel.playerState.value.song
            if (song != null) {
                downloadModel.download(song)
                context?.showToast(getString(R.string.lbl_download_started, song.title))
            }
        }

        binding.onlinePlayerFullPlaylistBtn.setOnClickListener {
            val song = downloadModel.playerState.value.song
            if (song != null) {
                showAddToPlaylistDialog(song)
            }
        }

        binding.onlinePlayerInfoBtn.setOnClickListener {
            val song = downloadModel.playerState.value.song
            if (song != null) {
                showTrackInfoDialog(song)
            }
        }

        // Observe player state
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.playerState.collectLatest { state ->
                val song = state.song
                if (song == null) {
                    dismiss()
                    return@collectLatest
                }

                // Title & Artist on artwork pill
                binding.onlinePlayerFullTitle.text = song.title
                binding.onlinePlayerFullArtist.text = song.artist

                // Bottom preview bar
                binding.onlinePlayerBottomTitle.text = song.title
                binding.onlinePlayerBottomArtist.text = "${song.artist} • ${song.durationText}"

                // Artwork and ambient blur
                ThumbnailLoader.load(binding.onlinePlayerLargeCover, song.thumbnailUrl)
                ThumbnailLoader.load(binding.onlinePlayerBlurBg, song.thumbnailUrl)

                // Deterministic waveform pattern seed for this song
                binding.onlinePlayerSeekBar.setSongSeed(song.webUrl.hashCode().toLong())

                // Playback states & icons
                binding.onlinePlayerLargeLoading.isVisible = state.isLoading
                binding.onlinePlayerPlayPauseFab.setImageResource(
                    if (state.isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
                )

                // Shuffle & Repeat states
                updateShuffleState(state.isShuffle)
                updateRepeatState(state.isRepeat)

                // Previous & Next navigation controls
                binding.onlinePlayerNextBtn.alpha =
                    if (state.hasNext || state.isRepeat) 1.0f else 0.4f
                binding.onlinePlayerPrevBtn.alpha =
                    if (state.hasPrevious || state.isRepeat || state.positionMs > 3000L) 1.0f
                    else 0.4f

                // Duration & Position safely updated in deci-seconds (1/10s)
                val durationMs = state.durationMs.coerceAtLeast(1000L)
                val positionMs = state.positionMs.coerceIn(0L, durationMs)

                binding.onlinePlayerSeekBar.durationDs = durationMs / 100L
                binding.onlinePlayerSeekBar.positionDs = positionMs / 100L
            }
        }
    }

    override fun onSeekConfirmed(positionDs: Long) {
        val positionMs = positionDs * 100L
        downloadModel.seekOnline(positionMs)
    }

    private fun updateShuffleState(isShuffle: Boolean) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#88FFFFFF")
        binding.onlinePlayerShuffleBtn.setImageResource(
            if (isShuffle) R.drawable.ic_shuffle_on_24 else R.drawable.ic_shuffle_off_24
        )
        binding.onlinePlayerShuffleBtn.setColorFilter(if (isShuffle) activeColor else inactiveColor)
    }

    private fun updateRepeatState(isRepeat: Boolean) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#88FFFFFF")
        binding.onlinePlayerRepeatBtn.setImageResource(
            if (isRepeat) R.drawable.ic_repeat_on_24 else R.drawable.ic_repeat_off_24
        )
        binding.onlinePlayerRepeatBtn.setColorFilter(if (isRepeat) activeColor else inactiveColor)
    }

    private fun showTrackInfoDialog(song: OnlineSong) {
        val durationFormatted = formatTime(song.durationSeconds * 1000L)
        val details =
            "${getString(R.string.lbl_song_info)}\n\n" +
                "• Título: ${song.title}\n" +
                "• Artista / Canal: ${song.artist}\n" +
                "• Duración: $durationFormatted\n" +
                "• Formato: M4A / AAC (Audio HQ)\n" +
                "• Ubicación al descargar: Música/Novaura/${song.artist} - ${song.title}/\n\n" +
                "Enlace de origen:\n${song.webUrl}"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_details)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAddToPlaylistDialog(song: OnlineSong) {
        val playlists = downloadModel.onlinePlaylists.value

        val items = mutableListOf<String>()
        items.add("➕ ${getString(R.string.lbl_create_online_playlist)}")
        items.addAll(playlists.map { it.name })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_add_to_online_playlist)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistPrompt { newPlaylist ->
                        downloadModel.addSongToPlaylist(newPlaylist.id, song)
                        context?.showToast(
                            getString(R.string.lbl_song_added_to_playlist, newPlaylist.name)
                        )
                    }
                } else {
                    val target = playlists[which - 1]
                    val added = downloadModel.addSongToPlaylist(target.id, song)
                    if (added) {
                        context?.showToast(
                            getString(R.string.lbl_song_added_to_playlist, target.name)
                        )
                    } else {
                        context?.showToast(getString(R.string.lbl_song_already_in_playlist))
                    }
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun showCreatePlaylistPrompt(onCreated: (OnlinePlaylist) -> Unit) {
        val input =
            EditText(requireContext()).apply {
                hint = getString(R.string.lbl_playlist_name_hint)
                setSingleLine()
            }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_create_online_playlist)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val created = downloadModel.createOnlinePlaylist(name)
                    onCreated(created)
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OnlinePlayerBottomSheet"
    }
}
