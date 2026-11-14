/*
 * Copyright (c) 2026 Auxio Project
 * DownloadFragment.kt is part of Auxio.
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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDownloadBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.ui.WallpaperHelper
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getSystemServiceCompat
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import timber.log.Timber as L

@AndroidEntryPoint
class DownloadFragment : ViewBindingFragment<FragmentDownloadBinding>() {

    @Inject lateinit var uiSettings: UISettings

    private val downloadModel: DownloadViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()

    private lateinit var downloadAdapter: DownloadAdapter
    private lateinit var playlistAdapter: OnlinePlaylistAdapter
    private lateinit var queueAdapter: DownloadQueueAdapter

    private var imm: InputMethodManager? = null
    private var debounceJob: Job? = null
    private var launchedKeyboard = false

    private var lastMiniPlayerSongUrl: String? = null
    private var lastMiniPlayerIsLoading: Boolean? = null
    private var lastMiniPlayerIsPlaying: Boolean? = null
    private var lastMiniPlayerHasPrev: Boolean? = null
    private var lastMiniPlayerHasNext: Boolean? = null
    private var playlistSortMode: Int = 0

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            L.d("Notification permission granted: $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentDownloadBinding =
        FragmentDownloadBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentDownloadBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            val systemBars = insets.systemBarInsetsCompat
            this.binding?.onlinePlayerCard?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
            }
            insets
        }

        imm = binding.context.getSystemServiceCompat(InputMethodManager::class)

        // --- APPBAR SETUP ---
        binding.downloadAppbar.apply {
            isLiftOnScroll = false
            setLiftable(false)
            isLifted = false
            elevation = 0f
            stateListAnimator = null
        }

        // --- TOOLBAR ---
        binding.downloadToolbar.setNavigationOnClickListener {
            hideKeyboard()
            findNavController().navigateUp()
        }

        // --- TABS SETUP ---
        setupTabs(binding)

        // --- ADAPTERS SETUP ---
        setupAdapters(binding)

        // --- SEARCH INPUT & ACTIONS ---
        setupSearch(binding)

        // --- ONLINE PLAYLISTS BUTTONS ---
        binding.btnCreateOnlinePlaylist.setOnClickListener {
            showCreatePlaylistPrompt()
        }
        binding.btnOnlinePlaylistSort.setOnClickListener { view ->
            showPlaylistSortMenu(view, binding)
        }
        setupSmartPlaylistRows(binding)

        // --- DOWNLOAD QUEUE BUTTONS ---
        binding.btnPauseAll.setOnClickListener {
            downloadModel.pauseAllDownloads()
        }
        binding.btnClearCompleted.setOnClickListener {
            downloadModel.clearCompletedDownloads()
        }

        // --- MINI-PLAYER CONTROLS ---
        setupMiniPlayer(binding)

        // --- STATE OBSERVATIONS ---
        observeViewModel(binding)
        applyWallpaperBackground(binding)
    }

    override fun onResume() {
        super.onResume()
        binding?.let(::applyWallpaperBackground)
    }

    private fun applyWallpaperBackground(binding: FragmentDownloadBinding) {
        WallpaperHelper.applySecondaryWallpaperBackground(binding.root, uiSettings)
    }

    private fun setupTabs(binding: FragmentDownloadBinding) {
        binding.downloadTabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    when (tab?.position) {
                        0 -> {
                            binding.layoutTabSearch.isVisible = true
                            binding.layoutTabPlaylists.isVisible = false
                            binding.layoutTabQueue.isVisible = false
                        }
                        1 -> {
                            binding.layoutTabSearch.isVisible = false
                            binding.layoutTabPlaylists.isVisible = true
                            binding.layoutTabQueue.isVisible = false
                            hideKeyboard()
                        }
                        2 -> {
                            binding.layoutTabSearch.isVisible = false
                            binding.layoutTabPlaylists.isVisible = false
                            binding.layoutTabQueue.isVisible = true
                            hideKeyboard()
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
            }
        )
    }

    private fun setupAdapters(binding: FragmentDownloadBinding) {
        // Search Results Adapter
        downloadAdapter =
            DownloadAdapter(
                onPlayClick = { song ->
                    downloadModel.playOnline(song)
                },
                onDownloadClick = { song ->
                    checkNotificationPermission()
                    downloadModel.download(song)
                    context?.showToast(getString(R.string.lbl_download_started, song.title))
                },
                onAddToPlaylistClick = { song ->
                    showAddToPlaylistDialog(song)
                },
            )
        binding.downloadRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = downloadAdapter
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        // Online Playlists Adapter
        playlistAdapter =
            OnlinePlaylistAdapter(
                onPlaylistClick = { playlist ->
                    showPlaylistDetailsDialog(playlist)
                },
                onPlayClick = { playlist ->
                    if (playlist.songs.isNotEmpty()) {
                        downloadModel.playQueue(playlist.songs, 0, playlist.name)
                        context?.showToast(getString(R.string.lbl_streaming))
                    } else {
                        context?.showToast(getString(R.string.lbl_no_download_results))
                    }
                },
                onMenuClick = { playlist, anchorView ->
                    showPlaylistMenu(playlist, anchorView)
                },
            )
        binding.playlistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }

        // Download Queue Adapter
        queueAdapter =
            DownloadQueueAdapter(
                onPauseResumeClick = { item ->
                    if (item.status == DownloadStatus.DOWNLOADING) {
                        downloadModel.pauseDownload(item.song.webUrl)
                    } else {
                        downloadModel.resumeDownload(item.song.webUrl)
                    }
                },
                onCancelClick = { item ->
                    downloadModel.cancelDownload(item.song.webUrl)
                },
            )
        binding.downloadsQueueRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = queueAdapter
        }
    }

    private fun setupSearch(binding: FragmentDownloadBinding) {
        binding.downloadSearchInput.apply {
            addTextChangedListener { editable ->
                val query = editable?.toString()?.trim().orEmpty()
                binding.downloadSearchClear.isVisible = query.isNotEmpty()

                debounceJob?.cancel()
                if (query.isEmpty()) {
                    downloadModel.search("")
                } else {
                    debounceJob =
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(600)
                            downloadModel.search(query)
                        }
                }
            }

            setOnEditorActionListener { _, actionId, event ->
                if (
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN)
                ) {
                    performSearch(binding)
                    true
                } else {
                    false
                }
            }

            if (!launchedKeyboard) {
                showKeyboard(this)
                launchedKeyboard = true
            }
        }

        binding.downloadSearchClear.setOnClickListener {
            binding.downloadSearchInput.text?.clear()
            debounceJob?.cancel()
            downloadModel.search("")
            showKeyboard(binding.downloadSearchInput)
        }

        binding.downloadSearchBtn.setOnClickListener {
            performSearch(binding)
        }
    }

    private fun setupMiniPlayer(binding: FragmentDownloadBinding) {
        val openListener = View.OnClickListener { showOnlinePlayer() }
        binding.onlinePlayerCard.setOnClickListener(openListener)
        binding.onlinePlayerTouchArea.setOnClickListener(openListener)
        binding.onlinePlayerThumb.setOnClickListener(openListener)
        binding.onlinePlayerTitle.setOnClickListener(openListener)
        binding.onlinePlayerSubtitle.setOnClickListener(openListener)

        binding.onlinePlayerTitle.isSelected = true
        binding.onlinePlayerSubtitle.isSelected = true

        binding.onlinePlayerPrev.setOnClickListener {
            downloadModel.playPrevOnline()
        }

        binding.onlinePlayerPlayPause.setOnClickListener {
            downloadModel.togglePlayPauseOnline()
        }

        binding.onlinePlayerNext.setOnClickListener {
            downloadModel.playNextOnline()
        }

        binding.onlinePlayerClose.setOnClickListener {
            downloadModel.stopOnlinePlayback()
        }
    }

    private fun showOnlinePlayer() {
        if (!isAdded) return
        val existing = parentFragmentManager.findFragmentByTag(OnlinePlayerBottomSheet.TAG)
        if (existing == null) {
            OnlinePlayerBottomSheet().show(parentFragmentManager, OnlinePlayerBottomSheet.TAG)
        }
    }

    private fun observeViewModel(binding: FragmentDownloadBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    downloadModel.isSearching.collectLatest { searching ->
                        binding.downloadSearchLoading.isVisible = searching
                        val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
                        val count = downloadModel.displayedResults.value.size
                        updateUiState(
                            binding,
                            isSearching = searching,
                            resultsCount = count,
                            query = query,
                        )
                    }
                }

                launch {
                    downloadModel.displayedResults.collectLatest { results ->
                        downloadAdapter.submitList(results)
                        val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
                        val searching = downloadModel.isSearching.value
                        updateUiState(
                            binding,
                            isSearching = searching,
                            resultsCount = results.size,
                            query = query,
                        )
                    }
                }

                launch {
                    downloadModel.downloadProgress.collectLatest { progressMap ->
                        downloadAdapter.updateProgress(progressMap)
                    }
                }

                launch {
                    downloadModel.playerState.collectLatest { playerState ->
                        downloadAdapter.updatePlayerState(playerState)
                        updateMiniPlayerUi(binding, playerState)
                    }
                }

                launch {
                    downloadModel.playerError.collect { errorMsg ->
                        context?.showToast(errorMsg)
                    }
                }

                launch {
                    downloadModel.downloadItems.collectLatest { items ->
                        queueAdapter.submitList(items)
                        binding.downloadsEmptyState.isVisible = items.isEmpty()
                        binding.downloadsQueueRecycler.isVisible = items.isNotEmpty()

                        val activeCount = items.count { it.status == DownloadStatus.DOWNLOADING }
                        val queueTab = binding.downloadTabLayout.getTabAt(2)
                        if (queueTab != null) {
                            if (activeCount > 0) {
                                val badge = queueTab.orCreateBadge
                                badge.number = activeCount
                                badge.isVisible = true
                            } else {
                                queueTab.removeBadge()
                            }
                        }
                    }
                }

                launch {
                    downloadModel.onlinePlaylists.collectLatest { playlists ->
                        renderUserPlaylists(binding, playlists)
                        val favPlaylist = playlists.firstOrNull {
                            it.id == OnlinePlaylistManager.ID_FAVORITES ||
                                it.name.equals(
                                    OnlinePlaylistManager.FAVORITES_NAME,
                                    ignoreCase = true,
                                )
                        }
                        val favCount = favPlaylist?.songs?.size ?: 0
                        binding.txtOnlineFavoritesCount.text =
                            resources.getQuantityString(
                                R.plurals.fmt_song_count,
                                favCount,
                                favCount,
                            )
                    }
                }

                launch {
                    downloadModel.recentOnlineSongs.collectLatest { recent ->
                        val count = recent.size
                        binding.txtOnlineRecentCount.text =
                            resources.getQuantityString(R.plurals.fmt_song_count, count, count)
                        val mostPlayed = downloadModel.getMostPlayedOnlineSongs()
                        binding.txtOnlineMostPlayedCount.text =
                            resources.getQuantityString(
                                R.plurals.fmt_song_count,
                                mostPlayed.size,
                                mostPlayed.size,
                            )
                    }
                }

                launch {
                    downloadModel.onlinePlayCounts.collectLatest {
                        val mostPlayed = downloadModel.getMostPlayedOnlineSongs()
                        binding.txtOnlineMostPlayedCount.text =
                            resources.getQuantityString(
                                R.plurals.fmt_song_count,
                                mostPlayed.size,
                                mostPlayed.size,
                            )
                    }
                }

                launch {
                    downloadModel.events.collect { event ->
                        when (event) {
                            is DownloadEvent.Started -> {
                                context?.showToast(
                                    getString(R.string.lbl_download_started, event.title)
                                )
                            }
                            is DownloadEvent.Success -> {
                                context?.showToast(
                                    getString(R.string.msg_download_success, event.title)
                                )
                            }
                            is DownloadEvent.Error -> {
                                context?.showToast(
                                    getString(R.string.msg_download_error, event.title)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateMiniPlayerUi(
        binding: FragmentDownloadBinding,
        state: OnlinePlayerState,
    ) {
        val song = state.song
        if (song == null) {
            if (binding.onlinePlayerCard.isVisible) {
                binding.onlinePlayerCard.isVisible = false
            }
            lastMiniPlayerSongUrl = null
            lastMiniPlayerIsLoading = null
            lastMiniPlayerIsPlaying = null
            lastMiniPlayerHasPrev = null
            lastMiniPlayerHasNext = null
            return
        }

        if (!binding.onlinePlayerCard.isVisible) {
            binding.onlinePlayerCard.isVisible = true
        }

        // Only update song metadata and thumbnail when the song actually changes!
        if (lastMiniPlayerSongUrl != song.webUrl) {
            lastMiniPlayerSongUrl = song.webUrl
            if (binding.onlinePlayerTitle.text != song.title) {
                binding.onlinePlayerTitle.text = song.title
                binding.onlinePlayerTitle.isSelected = true
            }
            if (binding.onlinePlayerSubtitle.text != song.artist) {
                binding.onlinePlayerSubtitle.text = song.artist
                binding.onlinePlayerSubtitle.isSelected = true
            }
            ThumbnailLoader.load(binding.onlinePlayerThumb, song.thumbnailUrl)
        }

        // Only update buttons and loading state when their values actually change!
        if (lastMiniPlayerIsLoading != state.isLoading) {
            lastMiniPlayerIsLoading = state.isLoading
            binding.onlinePlayerLoading.isVisible = state.isLoading
            binding.onlinePlayerProgress.isIndeterminate = state.isLoading
        }

        if (lastMiniPlayerIsPlaying != state.isPlaying) {
            lastMiniPlayerIsPlaying = state.isPlaying
            binding.onlinePlayerPlayPause.isChecked = state.isPlaying
        }

        val hasPrev = state.hasPrevious || state.isRepeat || state.isShuffle
        if (lastMiniPlayerHasPrev != hasPrev) {
            lastMiniPlayerHasPrev = hasPrev
            binding.onlinePlayerPrev.isEnabled = hasPrev
            binding.onlinePlayerPrev.alpha = if (hasPrev) 1.0f else 0.38f
        }

        val hasNext = state.hasNext || state.isRepeat || state.isShuffle
        if (lastMiniPlayerHasNext != hasNext) {
            lastMiniPlayerHasNext = hasNext
            binding.onlinePlayerNext.isEnabled = hasNext
            binding.onlinePlayerNext.alpha = if (hasNext) 1.0f else 0.38f
        }

        // Only the progress bar tracks positionMs smoothly
        if (!state.isLoading) {
            if (state.durationMs > 0) {
                binding.onlinePlayerProgress.max = 1000
                val progress =
                    ((state.positionMs * 1000) / state.durationMs).toInt().coerceIn(0, 1000)
                binding.onlinePlayerProgress.progress = progress
            } else {
                binding.onlinePlayerProgress.progress = 0
            }
        }
    }

    private fun updateUiState(
        binding: FragmentDownloadBinding,
        isSearching: Boolean,
        resultsCount: Int,
        query: String,
    ) {
        when {
            isSearching -> {
                binding.downloadRecycler.isVisible = false
                binding.downloadSearchingState.isVisible = true
                binding.downloadNoResultsState.isVisible = false
                binding.downloadEmptyScroll.isVisible = false
            }
            resultsCount > 0 -> {
                binding.downloadRecycler.isVisible = true
                binding.downloadSearchingState.isVisible = false
                binding.downloadNoResultsState.isVisible = false
                binding.downloadEmptyScroll.isVisible = false
            }
            !isSearching && query.isNotEmpty() && resultsCount == 0 -> {
                binding.downloadRecycler.isVisible = false
                binding.downloadSearchingState.isVisible = false
                binding.downloadNoResultsState.isVisible = true
                binding.downloadEmptyScroll.isVisible = false
            }
            else -> {
                binding.downloadRecycler.isVisible = false
                binding.downloadSearchingState.isVisible = false
                binding.downloadNoResultsState.isVisible = false
                binding.downloadEmptyScroll.isVisible = true
            }
        }
    }

    private fun performSearch(binding: FragmentDownloadBinding) {
        val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty()) {
            debounceJob?.cancel()
            hideKeyboard()
            downloadModel.search(query)
        }
    }

    private fun setupSmartPlaylistRows(binding: FragmentDownloadBinding) {
        binding.itemOnlineRecentlyPlayed.setOnClickListener {
            val songs = downloadModel.recentOnlineSongs.value
            OnlinePlaylistDetailBottomSheet.show(
                parentFragmentManager,
                getString(R.string.chip_queue_recent),
                songs,
            )
        }
        binding.btnOnlineRecentMenu.setOnClickListener { anchor ->
            showSmartListMenu(
                anchor,
                getString(R.string.chip_queue_recent),
                downloadModel.recentOnlineSongs.value,
            )
        }

        binding.itemOnlineMostPlayed.setOnClickListener {
            val songs = downloadModel.getMostPlayedOnlineSongs()
            OnlinePlaylistDetailBottomSheet.show(
                parentFragmentManager,
                getString(R.string.lbl_most_played),
                songs,
            )
        }
        binding.btnOnlineMostPlayedMenu.setOnClickListener { anchor ->
            showSmartListMenu(
                anchor,
                getString(R.string.lbl_most_played),
                downloadModel.getMostPlayedOnlineSongs(),
            )
        }

        binding.itemOnlineFavorites.setOnClickListener {
            val fav = downloadModel.getFavoritesOnlinePlaylist()
            OnlinePlaylistDetailBottomSheet.show(
                parentFragmentManager,
                getString(R.string.lbl_favorites),
                fav?.songs ?: emptyList(),
                fav?.id,
            )
        }
        binding.btnOnlineFavoritesMenu.setOnClickListener { anchor ->
            val fav = downloadModel.getFavoritesOnlinePlaylist()
            showSmartListMenu(
                anchor,
                getString(R.string.lbl_favorites),
                fav?.songs ?: emptyList(),
            )
        }
    }

    private fun showSmartListMenu(anchorView: View, title: String, songs: List<OnlineSong>) {
        val popup = PopupMenu(requireContext(), anchorView)
        popup.menu.add(0, 1, 0, R.string.lbl_play)
        popup.menu.add(0, 2, 1, R.string.lbl_shuffle)
        popup.menu.add(0, 3, 2, R.string.lbl_save)
        popup.menu.add(0, 4, 3, R.string.lbl_download)

        popup.setOnMenuItemClickListener { item ->
            if (songs.isEmpty()) {
                context?.showToast(getString(R.string.lbl_no_download_results))
                return@setOnMenuItemClickListener true
            }
            when (item.itemId) {
                1 -> {
                    if (playbackModel.isPlaying.value) {
                        playbackModel.togglePlaying()
                    }
                    downloadModel.playQueue(songs, 0, title)
                    true
                }
                2 -> {
                    if (playbackModel.isPlaying.value) {
                        playbackModel.togglePlaying()
                    }
                    downloadModel.playQueue(songs.shuffled(), 0, title)
                    true
                }
                3 -> {
                    OnlineAddToPlaylistBottomSheet.show(parentFragmentManager, songs)
                    true
                }
                4 -> {
                    checkNotificationPermission()
                    songs.forEach { downloadModel.download(it) }
                    context?.showToast(getString(R.string.lbl_download_started, title))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showPlaylistSortMenu(anchorView: View, binding: FragmentDownloadBinding) {
        val popup = PopupMenu(requireContext(), anchorView)
        popup.menu.add(0, 0, 0, R.string.lbl_name)
        popup.menu.add(0, 1, 1, R.string.lbl_song_count)
        popup.setOnMenuItemClickListener { item ->
            playlistSortMode = item.itemId
            renderUserPlaylists(binding, downloadModel.onlinePlaylists.value)
            true
        }
        popup.show()
    }

    private fun renderUserPlaylists(
        binding: FragmentDownloadBinding,
        playlists: List<OnlinePlaylist>,
    ) {
        val userPlaylists = playlists.filterNot {
            it.name.equals(OnlinePlaylistManager.FAVORITES_NAME, ignoreCase = true)
        }
        val sorted =
            when (playlistSortMode) {
                1 -> userPlaylists.sortedByDescending { it.songs.size }
                else -> userPlaylists.sortedBy { it.name.lowercase(Locale.getDefault()) }
            }
        playlistAdapter.submitList(sorted)
        binding.playlistsEmptyState.isVisible = sorted.isEmpty()
        binding.playlistsRecycler.isVisible = sorted.isNotEmpty()
    }

    private fun showAddToPlaylistDialog(song: OnlineSong) {
        OnlineAddToPlaylistBottomSheet.show(parentFragmentManager, listOf(song))
    }

    private fun showCreatePlaylistPrompt(onCreated: ((OnlinePlaylist) -> Unit)? = null) {
        OnlineAddToPlaylistBottomSheet.showPlaylistNameDialog(
            context = requireContext(),
            inflater = layoutInflater,
            titleRes = R.string.lbl_new_playlist,
            initialName = "",
            hintText = getString(R.string.lbl_playlist_name_hint),
        ) { chosenName ->
            if (chosenName.isNotEmpty()) {
                val created = downloadModel.createOnlinePlaylist(chosenName)
                onCreated?.invoke(created)
            }
        }
    }

    private fun showPlaylistDetailsDialog(playlist: OnlinePlaylist) {
        OnlinePlaylistDetailBottomSheet.show(
            parentFragmentManager,
            playlist.name,
            playlist.songs,
            playlist.id,
        )
    }

    private fun showPlaylistMenu(playlist: OnlinePlaylist, anchorView: View) {
        val popup = PopupMenu(requireContext(), anchorView)
        popup.menu.add(0, 1, 0, R.string.lbl_play)
        popup.menu.add(0, 2, 1, R.string.lbl_shuffle)
        popup.menu.add(0, 3, 2, R.string.lbl_rename)
        popup.menu.add(0, 4, 3, R.string.lbl_download)
        popup.menu.add(0, 5, 4, R.string.lbl_delete_playlist)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    if (playlist.songs.isNotEmpty()) {
                        if (playbackModel.isPlaying.value) {
                            playbackModel.togglePlaying()
                        }
                        downloadModel.playQueue(playlist.songs, 0, playlist.name)
                    } else {
                        context?.showToast(getString(R.string.lbl_no_download_results))
                    }
                    true
                }
                2 -> {
                    if (playlist.songs.isNotEmpty()) {
                        if (playbackModel.isPlaying.value) {
                            playbackModel.togglePlaying()
                        }
                        downloadModel.playQueue(playlist.songs.shuffled(), 0, playlist.name)
                    } else {
                        context?.showToast(getString(R.string.lbl_no_download_results))
                    }
                    true
                }
                3 -> {
                    OnlineAddToPlaylistBottomSheet.showPlaylistNameDialog(
                        context = requireContext(),
                        inflater = layoutInflater,
                        titleRes = R.string.lbl_rename_playlist,
                        initialName = playlist.name,
                        hintText = getString(R.string.lbl_playlist_name_hint),
                    ) { newName ->
                        if (newName.isNotEmpty()) {
                            downloadModel.renameOnlinePlaylist(playlist.id, newName)
                        }
                    }
                    true
                }
                4 -> {
                    if (playlist.songs.isNotEmpty()) {
                        checkNotificationPermission()
                        playlist.songs.forEach { downloadModel.download(it) }
                        context?.showToast(getString(R.string.lbl_download_started, playlist.name))
                    } else {
                        context?.showToast(getString(R.string.lbl_no_download_results))
                    }
                    true
                }
                5 -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.lbl_delete_playlist)
                        .setMessage(getString(R.string.msg_confirm_delete_playlist, playlist.name))
                        .setPositiveButton(R.string.lbl_delete) { _, _ ->
                            downloadModel.deleteOnlinePlaylist(playlist.id)
                        }
                        .setNegativeButton(R.string.lbl_cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0) return "--:--"
        val m = durationSeconds / 60
        val s = durationSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    private fun showKeyboard(view: View) {
        view.apply {
            requestFocus()
            postDelayed(200) {
                if (isAdded && !isDetached) {
                    try {
                        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    } catch (e: Exception) {
                        L.w(e, "Could not show soft input")
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        try {
            val token = view?.windowToken ?: activity?.currentFocus?.windowToken
            if (token != null) {
                imm?.hideSoftInputFromWindow(token, InputMethodManager.HIDE_NOT_ALWAYS)
            }
        } catch (e: Exception) {
            L.w(e, "Could not hide soft input")
        }
    }

    override fun onDestroyBinding(binding: FragmentDownloadBinding) {
        super.onDestroyBinding(binding)
        binding.root.setOnApplyWindowInsetsListener(null)
        debounceJob?.cancel()
        debounceJob = null
        imm = null
    }
}
