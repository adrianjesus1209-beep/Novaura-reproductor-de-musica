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

import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogOnlinePlayerBinding
import org.oxycblt.auxio.databinding.ItemPlaybackTrackBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.util.getDimenPixels
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat

@AndroidEntryPoint
class OnlinePlayerBottomSheet : DialogFragment(), StyledSeekBar.Listener {

    private var _binding: DialogOnlinePlayerBinding? = null
    private val binding
        get() = _binding!!

    private val downloadModel: DownloadViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()

    @Inject lateinit var imageLoader: ImageLoader

    private var lastSongUrl: String? = null
    private var lastIsPlaying: Boolean? = null
    private var lastQueueIndex: Int? = null
    private var activeQueueFilter: Int = FILTER_ALL
    private var displayedQueueSongs: List<OnlineSong> = emptyList()

    private val trackAdapter = OnlineTrackAdapter { clickedSong ->
        val state = downloadModel.playerState.value
        val idxInQueue = state.queue.indexOfFirst { it.webUrl == clickedSong.webUrl }
        if (idxInQueue >= 0) {
            downloadModel.playQueueIndex(idxInQueue)
        } else {
            downloadModel.playOnline(clickedSong)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Auxio_Playback_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogOnlinePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // System Bars Insets matching PlaybackPanelFragment
        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.systemBarInsetsCompat
            binding.onlinePlayerToolbar.updatePadding(top = bars.top)
            val spacingLarge = v.context.getDimenPixels(R.dimen.spacing_large)
            binding.onlinePlayerTracklist.updatePadding(bottom = bars.bottom + (spacingLarge * 3))
            insets
        }

        binding.onlinePlayerSeekBar.listener = this
        binding.onlinePlayerTracklist.adapter = trackAdapter

        // --- TOOLBAR NAVIGATION & MENU ---
        binding.onlinePlayerToolbar.apply {
            title = getString(R.string.lbl_playback)
            subtitle = getString(R.string.lbl_all_songs)
            setNavigationOnClickListener {
                dismiss()
            }
            setOnMenuItemClickListener { menuItem ->
                val song = downloadModel.playerState.value.song
                when (menuItem.itemId) {
                    R.id.action_search -> {
                        dismiss()
                        true
                    }
                    R.id.action_close_playback -> {
                        downloadModel.stopOnlinePlayback()
                        dismiss()
                        true
                    }
                    R.id.action_online_download -> {
                        if (song != null) {
                            downloadModel.download(song)
                            context?.showToast(getString(R.string.lbl_download_started, song.title))
                        }
                        true
                    }
                    R.id.action_online_playlist -> {
                        if (song != null) {
                            showAddToPlaylistDialog(song)
                        }
                        true
                    }
                    R.id.action_online_info -> {
                        if (song != null) {
                            showTrackInfoDialog(song)
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        // --- FAVORITE HEART BUTTON ON COVER ---
        binding.onlinePlayerFavorite.setOnClickListener {
            val song = downloadModel.playerState.value.song ?: return@setOnClickListener
            val isFav = downloadModel.toggleOnlineFavorites(listOf(song))
            updateFavoriteIcon(isFav)
            context?.showToast(
                getString(if (isFav) R.string.lng_favorite_added else R.string.lng_favorite_removed)
            )
        }

        // --- YOUTUBE MUSIC SAVE BUTTONS & FILTER CHIPS ---
        binding.btnOnlinePlaybackSave.setOnClickListener {
            val song = downloadModel.playerState.value.song ?: return@setOnClickListener
            showAddToPlaylistDialog(song)
        }

        binding.btnOnlineQueueSave.setOnClickListener {
            val state = downloadModel.playerState.value
            val songsToSave = displayedQueueSongs.ifEmpty {
                downloadModel.recentOnlineSongs.value.ifEmpty { state.queue }
            }
            if (songsToSave.isNotEmpty()) {
                OnlineAddToPlaylistBottomSheet.show(parentFragmentManager, songsToSave)
            }
        }

        setupQueueFilterChips()

        // --- PLAYBACK CONTROLS ---
        binding.onlinePlayerPlayPauseBtn.setOnClickListener {
            if (!downloadModel.playerState.value.isPlaying && playbackModel.isPlaying.value) {
                playbackModel.togglePlaying()
            }
            downloadModel.togglePlayPauseOnline()
        }

        binding.onlinePlayerSkipNextBtn.setOnClickListener {
            downloadModel.playNextOnline()
        }

        binding.onlinePlayerSkipPrevBtn.setOnClickListener {
            downloadModel.playPrevOnline()
        }

        binding.onlinePlayerShuffleBtn.setOnClickListener {
            downloadModel.toggleShuffle()
        }

        binding.onlinePlayerRepeatBtn.setOnClickListener {
            downloadModel.toggleRepeat()
        }

        // --- OBSERVE PLAYER STATE ---
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.playerState.collectLatest { state ->
                val song = state.song ?: return@collectLatest

                if (lastSongUrl != song.webUrl) {
                    lastSongUrl = song.webUrl

                    // Subtitle in Toolbar
                    binding.onlinePlayerToolbar.title = getString(R.string.lbl_playback)
                    binding.onlinePlayerToolbar.subtitle =
                        state.sourceTitle?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.lbl_all_songs)

                    // Playing From Mix Title
                    binding.onlinePlayingFromTitle.text =
                        downloadModel.generateOnlineMixTitle(song, state.sourceTitle)

                    // Song Title & Artist in Cover Badge
                    binding.onlinePlayerFullTitle.text = song.title
                    binding.onlinePlayerFullArtist.text = song.artist
                    binding.onlinePlayerFullTitle.isSelected = true

                    // Favorite icon state
                    updateFavoriteIcon(downloadModel.isOnlineFavorite(song))

                    // Large Artwork and Ambient Background Blur
                    ThumbnailLoader.load(
                        binding.onlinePlayerLargeCover,
                        binding.playbackBlurBackground,
                        song.thumbnailUrl,
                    )

                    // Deterministic waveform seed for StyledSeekBar
                    binding.onlinePlayerSeekBar.setSongSeed(song.webUrl.hashCode().toLong())
                }

                if (lastQueueIndex != state.queueIndex || lastIsPlaying != state.isPlaying) {
                    lastQueueIndex = state.queueIndex
                    lastIsPlaying = state.isPlaying
                    refreshDisplayedQueue(state)
                }

                // Playback states & icons
                binding.onlinePlayerLargeLoading.isVisible = state.isLoading
                binding.onlinePlayerPlayPauseBtn.isChecked = state.isPlaying
                binding.onlinePlayerSeekBar.setWaveEnabled(state.isPlaying)

                // Shuffle & Repeat states
                binding.onlinePlayerShuffleBtn.isChecked = state.isShuffle
                binding.onlinePlayerRepeatBtn.isChecked = state.isRepeat
                binding.onlinePlayerRepeatBtn.setIconResource(
                    if (state.isRepeat) R.drawable.ic_repeat_on_24 else R.drawable.ic_repeat_off_24
                )

                // Previous & Next navigation controls
                binding.onlinePlayerSkipNextBtn.alpha =
                    if (state.hasNext || state.isRepeat) 1.0f else 0.4f
                binding.onlinePlayerSkipPrevBtn.alpha =
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

    private fun setupQueueFilterChips() {
        val chips =
            listOf(
                FILTER_ALL to binding.chipOnlineAll,
                FILTER_RECENT to binding.chipOnlineRecent,
                FILTER_POPULAR to binding.chipOnlinePopular,
                FILTER_DISCOVER to binding.chipOnlineDiscover,
            )

        fun updateChipStyles() {
            chips.forEach { (filterId, chipView) ->
                val selected = activeQueueFilter == filterId
                chipView.setBackgroundResource(
                    if (selected) R.drawable.bg_ytm_chip_selected
                    else R.drawable.bg_ytm_chip_unselected
                )
                chipView.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            }
        }

        chips.forEach { (filterId, chipView) ->
            chipView.setOnClickListener {
                activeQueueFilter = filterId
                updateChipStyles()
                refreshDisplayedQueue(downloadModel.playerState.value)
            }
        }
        updateChipStyles()
    }

    private fun refreshDisplayedQueue(state: OnlinePlayerState) {
        val baseQueue = state.queue
        val recent = downloadModel.recentOnlineSongs.value
        val filtered =
            when (activeQueueFilter) {
                FILTER_RECENT -> recent.ifEmpty { baseQueue }
                FILTER_POPULAR ->
                    baseQueue
                        .ifEmpty { recent }
                        .sortedByDescending { song ->
                            downloadModel.getOnlinePlayCount(song) * 1_000_000L + song.viewCount
                        }
                FILTER_DISCOVER ->
                    baseQueue
                        .filter { downloadModel.getOnlinePlayCount(it) <= 1 }
                        .ifEmpty { baseQueue.shuffled() }
                else -> baseQueue
            }
        displayedQueueSongs = filtered
        trackAdapter.submitQueue(filtered, state.song?.webUrl, state.isPlaying)
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.onlinePlayerFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
        )
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
        OnlineAddToPlaylistBottomSheet.show(parentFragmentManager, listOf(song))
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
        private const val FILTER_ALL = 0
        private const val FILTER_RECENT = 1
        private const val FILTER_POPULAR = 2
        private const val FILTER_DISCOVER = 3
    }

    /**
     * An adapter that renders online queue songs using the exact item_playback_track layout from
     * offline mode with YouTube Music-style thumbnails and active row highlight.
     */
    private class OnlineTrackAdapter(private val onItemClick: (OnlineSong) -> Unit) :
        ListAdapter<OnlineSong, OnlineTrackAdapter.ViewHolder>(DiffCallback) {

        private var activeSongUrl: String? = null
        private var isPlaying = false

        fun submitQueue(queue: List<OnlineSong>, currentSongUrl: String?, playing: Boolean) {
            val notifyNeeded = activeSongUrl != currentSongUrl || isPlaying != playing
            activeSongUrl = currentSongUrl
            isPlaying = playing
            submitList(queue) {
                if (notifyNeeded) {
                    notifyDataSetChanged()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemPlaybackTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val song = getItem(position)
            val isCurrent = song.webUrl == activeSongUrl

            holder.binding.apply {
                trackCover.isVisible = false
                trackOnlineCover.isVisible = true
                ThumbnailLoader.load(trackOnlineCover, song.thumbnailUrl)

                trackName.text = song.title
                trackSubtitle.isVisible = true
                trackSubtitle.text =
                    if (song.durationText.isNotBlank()) {
                        "${song.artist} • ${song.durationText}"
                    } else {
                        song.artist
                    }
                trackNumber.isVisible = false
                trackDuration.isVisible = false
                trackDragHandle.isVisible = true

                val anim = trackEqualizerIcon.drawable as? AnimationDrawable

                if (isCurrent) {
                    root.setBackgroundColor(0x1FFFFFFF)
                    trackPlayingScrim.isVisible = true
                    trackEqualizerIcon.isVisible = true
                    if (isPlaying) {
                        trackEqualizerIcon.setImageResource(R.drawable.ic_playing_indicator_24)
                        val playingAnim = trackEqualizerIcon.drawable as? AnimationDrawable
                        playingAnim?.start()
                    } else {
                        anim?.stop()
                        trackEqualizerIcon.setImageResource(R.drawable.ic_paused_indicator_24)
                    }
                    trackName.setTextColor(Color.WHITE)
                    trackSubtitle.setTextColor(0xCCFFFFFF.toInt())
                } else {
                    root.setBackgroundColor(Color.TRANSPARENT)
                    anim?.stop()
                    trackPlayingScrim.isVisible = false
                    trackEqualizerIcon.isVisible = false
                    trackName.setTextColor(Color.WHITE)
                    trackSubtitle.setTextColor(0x99FFFFFF.toInt())
                }

                root.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(getItem(pos))
                    }
                }
            }
        }

        class ViewHolder(val binding: ItemPlaybackTrackBinding) :
            RecyclerView.ViewHolder(binding.root)

        object DiffCallback : DiffUtil.ItemCallback<OnlineSong>() {
            override fun areItemsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
                oldItem.webUrl == newItem.webUrl

            override fun areContentsTheSame(oldItem: OnlineSong, newItem: OnlineSong): Boolean =
                oldItem == newItem
        }
    }
}
