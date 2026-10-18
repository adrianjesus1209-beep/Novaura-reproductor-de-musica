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
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDownloadBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getSystemServiceCompat
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import timber.log.Timber as L

@AndroidEntryPoint
class DownloadFragment : ViewBindingFragment<FragmentDownloadBinding>() {

    private val downloadModel: DownloadViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()

    private lateinit var downloadAdapter: DownloadAdapter
    private lateinit var playlistAdapter: OnlinePlaylistAdapter
    private lateinit var queueAdapter: DownloadQueueAdapter

    private var imm: InputMethodManager? = null
    private var debounceJob: Job? = null
    private var launchedKeyboard = false

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
            binding.onlinePlayerCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
            }
            insets
        }

        imm = binding.context.getSystemServiceCompat(InputMethodManager::class)

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
                    if (playbackModel.isPlaying.value) {
                        playbackModel.togglePlaying()
                    }
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
        }

        // Online Playlists Adapter
        playlistAdapter =
            OnlinePlaylistAdapter(
                onPlaylistClick = { playlist ->
                    showPlaylistDetailsDialog(playlist)
                },
                onPlayClick = { playlist ->
                    if (playlist.songs.isNotEmpty()) {
                        if (playbackModel.isPlaying.value) {
                            playbackModel.togglePlaying()
                        }
                        downloadModel.playQueue(playlist.songs, 0)
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
            if (!downloadModel.playerState.value.isPlaying && playbackModel.isPlaying.value) {
                playbackModel.togglePlaying()
            }
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
        // Observe search loading
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.isSearching.collectLatest { searching ->
                binding.downloadSearchLoading.isVisible = searching
                val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
                val count = downloadModel.displayedResults.value.size
                updateUiState(binding, isSearching = searching, resultsCount = count, query = query)
            }
        }

        // Observe search results (filtered in real-time)
        viewLifecycleOwner.lifecycleScope.launch {
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

        // Observe download progress for search adapter
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.downloadProgress.collectLatest { progressMap ->
                downloadAdapter.updateProgress(progressMap)
            }
        }

        // Observe player state for search adapter and mini-player
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.playerState.collectLatest { playerState ->
                downloadAdapter.updatePlayerState(playerState)
                updateMiniPlayerUi(binding, playerState)
            }
        }

        // Observe download items for the Downloads Queue
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.downloadItems.collectLatest { items ->
                queueAdapter.submitList(items)
                binding.downloadsEmptyState.isVisible = items.isEmpty()
                binding.downloadsQueueRecycler.isVisible = items.isNotEmpty()

                // Update tab badge with active downloading count
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

        // Observe online playlists
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.onlinePlaylists.collectLatest { playlists ->
                playlistAdapter.submitList(playlists)
                binding.playlistsEmptyState.isVisible = playlists.isEmpty()
                binding.playlistsRecycler.isVisible = playlists.isNotEmpty()
            }
        }

        // Observe download events
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.events.collectLatest { event ->
                when (event) {
                    is DownloadEvent.Started -> {
                        context?.showToast(getString(R.string.lbl_download_started, event.title))
                    }
                    is DownloadEvent.Success -> {
                        context?.showToast(getString(R.string.msg_download_success, event.title))
                    }
                    is DownloadEvent.Error -> {
                        context?.showToast(getString(R.string.msg_download_error, event.title))
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
            binding.onlinePlayerCard.isVisible = false
            return
        }

        binding.onlinePlayerCard.isVisible = true
        binding.onlinePlayerTitle.text = song.title
        ThumbnailLoader.load(binding.onlinePlayerThumb, song.thumbnailUrl)

        binding.onlinePlayerLoading.isVisible = state.isLoading
        binding.onlinePlayerPlayPause.isVisible = !state.isLoading
        binding.onlinePlayerPlayPause.isChecked = state.isPlaying

        val hasPrev = state.queueIndex > 0
        val hasNext = state.queueIndex < state.queue.size - 1
        binding.onlinePlayerPrev.isEnabled = hasPrev
        binding.onlinePlayerPrev.alpha = if (hasPrev) 1.0f else 0.38f
        binding.onlinePlayerNext.isEnabled = hasNext
        binding.onlinePlayerNext.alpha = if (hasNext) 1.0f else 0.38f

        val curSec = state.positionMs / 1000
        val durSec = state.durationMs / 1000
        binding.onlinePlayerSubtitle.text =
            "${song.artist} • ${formatDuration(curSec)} / ${formatDuration(durSec)}"

        if (state.durationMs > 0) {
            binding.onlinePlayerProgress.max = 1000
            val progress = ((state.positionMs * 1000) / state.durationMs).toInt().coerceIn(0, 1000)
            binding.onlinePlayerProgress.progress = progress
        } else {
            binding.onlinePlayerProgress.progress = 0
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

    private fun showCreatePlaylistPrompt(onCreated: ((OnlinePlaylist) -> Unit)? = null) {
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
                    onCreated?.invoke(created)
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun showPlaylistDetailsDialog(playlist: OnlinePlaylist) {
        if (playlist.songs.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(playlist.name)
                .setMessage("Esta lista está vacía. Añade canciones desde el buscador.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val titles = playlist.songs.map { "${it.title} - ${it.artist}" }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(playlist.name)
            .setItems(titles) { _, which ->
                val song = playlist.songs[which]
                if (playbackModel.isPlaying.value) {
                    playbackModel.togglePlaying()
                }
                downloadModel.playQueue(playlist.songs, which)
            }
            .setPositiveButton(R.string.lbl_play_all) { _, _ ->
                if (playbackModel.isPlaying.value) {
                    playbackModel.togglePlaying()
                }
                downloadModel.playQueue(playlist.songs, 0)
            }
            .setNegativeButton(R.string.lbl_close, null)
            .show()
    }

    private fun showPlaylistMenu(playlist: OnlinePlaylist, anchorView: View) {
        val popup = PopupMenu(requireContext(), anchorView)
        popup.menu.add(0, 1, 0, R.string.lbl_delete_playlist)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
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
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideKeyboard() {
        val token = view?.windowToken ?: activity?.currentFocus?.windowToken
        if (token != null) {
            imm?.hideSoftInputFromWindow(token, InputMethodManager.HIDE_NOT_ALWAYS)
        }
    }

    override fun onDestroyBinding(binding: FragmentDownloadBinding) {
        super.onDestroyBinding(binding)
        debounceJob?.cancel()
        debounceJob = null
        imm = null
    }
}
