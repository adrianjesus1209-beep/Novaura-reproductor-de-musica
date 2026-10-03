/*
 * Copyright (c) 2021 Auxio Project
 * HomeFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.detail.Show
import org.oxycblt.auxio.home.dashboard.MostPlayedTracksAdapter
import org.oxycblt.auxio.home.dashboard.TopArtistsAdapter
import org.oxycblt.auxio.home.list.AlbumListFragment
import org.oxycblt.auxio.home.list.ArtistListFragment
import org.oxycblt.auxio.home.list.GenreListFragment
import org.oxycblt.auxio.home.list.PlaylistListFragment
import org.oxycblt.auxio.home.list.SongListFragment
import org.oxycblt.auxio.home.tabs.NamedTabStrategy
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectionFragment
import org.oxycblt.auxio.list.menu.Menu
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.PlaylistDecision
import org.oxycblt.auxio.music.PlaylistMessage
import org.oxycblt.auxio.music.SongDecision
import org.oxycblt.auxio.music.SongMessage
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackDecision
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.stats.PlaybackStatsManager
import org.oxycblt.auxio.ui.FadingToolbarOffsetListener
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.IndexingProgress
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.pipeline.ScanReject
import org.oxycblt.musikr.pipeline.ScanReport
import org.oxycblt.musikr.playlist.m3u.M3U
import timber.log.Timber as L

/**
 * The starting [SelectionFragment] of Auxio. Shows the user's music library and enables navigation
 * to other views.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class HomeFragment : SelectionFragment<FragmentHomeBinding>() {
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    @Inject lateinit var statsManager: PlaybackStatsManager
    private var mostPlayedAdapter: MostPlayedTracksAdapter? = null
    private var topArtistsAdapter: TopArtistsAdapter? = null
    private var currentNavTab = NAV_TAB_HOME
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    private var getContentLauncher: ActivityResultLauncher<String>? = null
    private var pendingImportTarget: Playlist? = null
    private var hasRequestedPermission = false
    private var pendingPermissionRescan = false

    companion object {
        private const val NAV_TAB_HOME = 0
        private const val NAV_TAB_SONGS = 1
        private const val NAV_TAB_ALBUMS = 2
        private const val NAV_TAB_ARTISTS = 3
        private const val NAV_TAB_PLAYLISTS = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentHomeBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentHomeBinding) = binding.homeSelectionToolbar

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // Have to set up the permission launcher before the view is shown
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                pendingPermissionRescan = false
                if (isGranted) {
                    // The system callback confirmed the grant: update the app state reactively on
                    // the main thread and start scanning right away, without requiring a restart.
                    L.d("[PERMISSIONS_DEBUG] RESULT=GRANTED")
                    musicModel.rescan()
                    L.d("[PERMISSIONS_DEBUG] RESCAN triggered from grant callback")
                } else {
                    L.w("[PERMISSIONS_DEBUG] RESULT=DENIED")
                }
            }

        getContentLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) {
                    L.w("No URI returned from file picker")
                    return@registerForActivityResult
                }

                L.d("Received playlist URI $uri")
                musicModel.importPlaylist(uri, pendingImportTarget)
            }

        // --- UI SETUP ---

        binding.homeAppbar.addOnOffsetChangedListener(
            FadingToolbarOffsetListener(binding.homeToolbar, binding.homeContent)
        )
        binding.homeNormalToolbar.apply {
            setOnMenuItemClickListener(this@HomeFragment)
            MenuCompat.setGroupDividerEnabled(menu, true)
        }

        binding.homePager.apply {
            // Update HomeViewModel whenever the user swipes through the ViewPager.
            // This would be implemented in HomeFragment itself, but OnPageChangeCallback
            // is an object for some reason.
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        homeModel.synchronizeTabPosition(position)
                    }
                }
            )

            // ViewPager2 will nominally consume window insets, which will then break the window
            // insets applied to the indexing view before API 30. Fix this by overriding the
            // listener with a non-consuming listener.
            setOnApplyWindowInsetsListener { _, insets -> insets }

            // We know that there will only be a fixed amount of tabs, so we manually set this
            // limit to the maximum amount possible. This will prevent the tab ripple from
            // bugging out due to dynamically inflating each fragment, at the cost of slower
            // debug UI performance.
            offscreenPageLimit = Tab.MAX_SEQUENCE_IDX + 1

            dampen()
        }

        // Further initialization must be done in the function that also handles
        // re-creating the ViewPager.
        setupPager(binding)

        // --- VIEWMODEL SETUP ---
        collect(homeModel.recreateTabs.flow, ::handleRecreate)
        collect(homeModel.chooseMusicLocations.flow, ::handleChooseFolders)
        collect(homeModel.requestPermission.flow, ::handleRequestPermission)
        collectImmediately(homeModel.currentTabType, ::updateCurrentTab)
        collect(detailModel.toShow.flow, ::handleShow)
        collect(listModel.menu.flow, ::handleMenu)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(musicModel.indexingState, ::updateIndexerState)
        collect(musicModel.playlistDecision.flow, ::handlePlaylistDecision)
        collectImmediately(musicModel.playlistMessage.flow, ::handlePlaylistMessage)
        collect(musicModel.songDecision.flow, ::handleSongDecision)
        collectImmediately(musicModel.songMessage.flow, ::handleSongMessage)
        collect(playbackModel.playbackDecision.flow, ::handlePlaybackDecision)

        setupDashboard(binding)
        setupBottomBar(binding)

        collectImmediately(homeModel.songList) { songs ->
            updateDashboardSongs(binding, songs)
        }
        collectImmediately(homeModel.artistList) { artists ->
            updateDashboardArtists(binding, artists)
        }

        // Check and request storage permission automatically on launch
        if (!hasStoragePermission()) {
            L.d("[PERMISSIONS_DEBUG] REQUESTING permission=${storagePermission()}")
            storagePermissionLauncher?.launch(storagePermission())
        } else {
            L.d("[PERMISSIONS_DEBUG] CHECK=GRANTED, skipping request")
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = hasStoragePermission()
        L.d("[PERMISSIONS_DEBUG] onResume CHECK=${if (granted) "GRANTED" else "DENIED"}")
        // If the user granted the permission while the app was backgrounded (e.g. from the
        // system settings), scan the library immediately so it shows up without a restart.
        if (pendingPermissionRescan && granted) {
            pendingPermissionRescan = false
            L.d("[PERMISSIONS_DEBUG] RESCAN triggered on resume (grant in background)")
            musicModel.rescan()
        } else if (granted) {
            pendingPermissionRescan = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (!hasStoragePermission()) {
            pendingPermissionRescan = true
        }
    }

    override fun onDestroyBinding(binding: FragmentHomeBinding) {
        super.onDestroyBinding(binding)
        storagePermissionLauncher = null
        mostPlayedAdapter = null
        topArtistsAdapter = null
        binding.homeNormalToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) {
            return true
        }

        return when (item.itemId) {
            // Handle main actions (Search, Settings, About)
            R.id.action_search -> {
                L.d("Navigating to search")
                findNavController().navigateSafe(HomeFragmentDirections.search())
                true
            }
            R.id.action_settings -> {
                L.d("Navigating to preferences")
                homeModel.showSettings()
                true
            }
            R.id.action_about -> {
                L.d("Navigating to about")
                homeModel.showAbout()
                true
            }

            // Handle sort menu
            R.id.action_sort -> {
                // Junk click event when opening the menu
                val directions =
                    when (homeModel.currentTabType.value) {
                        MusicType.SONGS -> HomeFragmentDirections.sortSongs()
                        MusicType.ALBUMS -> HomeFragmentDirections.sortAlbums()
                        MusicType.ARTISTS -> HomeFragmentDirections.sortArtists()
                        MusicType.GENRES -> HomeFragmentDirections.sortGenres()
                        MusicType.PLAYLISTS -> HomeFragmentDirections.sortPlaylists()
                    }
                findNavController().navigateSafe(directions)
                true
            }
            else -> {
                L.w("Unexpected menu item selected")
                false
            }
        }
    }

    private fun setupDashboard(binding: FragmentHomeBinding) {
        val mostPlayed = MostPlayedTracksAdapter { song ->
            playbackModel.play(song, PlaySong.ByItself)
        }
        mostPlayedAdapter = mostPlayed
        binding.homeDashboard.rvMostPlayed.adapter = mostPlayed

        val topArtists = TopArtistsAdapter { artist ->
            detailModel.showArtist(artist)
        }
        topArtistsAdapter = topArtists
        binding.homeDashboard.rvTopArtists.adapter = topArtists

        binding.homeDashboard.dashboardSearchBar.setOnClickListener {
            findNavController().navigateSafe(HomeFragmentDirections.search())
        }
        binding.homeDashboard.btnFavorites.setOnClickListener {
            switchToTab(MusicType.SONGS)
        }
        binding.homeDashboard.btnSeeAllSongs.setOnClickListener {
            switchToTab(MusicType.SONGS)
        }
        binding.homeDashboard.btnRefreshStats.setOnClickListener {
            updateDashboardStats(binding)
        }

        updateDashboardStats(binding)
    }

    private fun setupBottomBar(binding: FragmentHomeBinding) {
        binding.homeBottomBar.navItemHome.setOnClickListener { selectNavTab(NAV_TAB_HOME) }
        binding.homeBottomBar.navItemSongs.setOnClickListener { selectNavTab(NAV_TAB_SONGS) }
        binding.homeBottomBar.navItemAlbums.setOnClickListener { selectNavTab(NAV_TAB_ALBUMS) }
        binding.homeBottomBar.navItemArtists.setOnClickListener { selectNavTab(NAV_TAB_ARTISTS) }
        binding.homeBottomBar.navItemPlaylists.setOnClickListener {
            selectNavTab(NAV_TAB_PLAYLISTS)
        }

        // Start on Home dashboard
        selectNavTab(NAV_TAB_HOME)
    }

    private fun switchToTab(type: MusicType) {
        val tabIndex =
            when (type) {
                MusicType.SONGS -> NAV_TAB_SONGS
                MusicType.ALBUMS -> NAV_TAB_ALBUMS
                MusicType.ARTISTS -> NAV_TAB_ARTISTS
                MusicType.PLAYLISTS -> NAV_TAB_PLAYLISTS
                else -> NAV_TAB_HOME
            }
        selectNavTab(tabIndex)
    }

    private fun selectNavTab(tabId: Int) {
        currentNavTab = tabId
        val binding = requireBinding()

        val isHome = tabId == NAV_TAB_HOME
        binding.homeDashboard.root.isVisible = isHome
        binding.homePager.isVisible = !isHome
        binding.homeTabs.isVisible = !isHome && homeModel.currentTabTypes.size > 1

        val primaryColor =
            requireContext().getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
        val inactiveColor = android.graphics.Color.parseColor("#88FFFFFF")

        fun updateNavItem(
            itemIcon: android.widget.ImageView,
            itemText: android.widget.TextView,
            selected: Boolean,
        ) {
            itemIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    if (selected) primaryColor else inactiveColor
                )
            itemText.isVisible = selected
        }

        updateNavItem(
            binding.homeBottomBar.navIconHome,
            binding.homeBottomBar.navTextHome,
            tabId == NAV_TAB_HOME,
        )
        updateNavItem(
            binding.homeBottomBar.navIconSongs,
            binding.homeBottomBar.navTextSongs,
            tabId == NAV_TAB_SONGS,
        )
        updateNavItem(
            binding.homeBottomBar.navIconAlbums,
            binding.homeBottomBar.navTextAlbums,
            tabId == NAV_TAB_ALBUMS,
        )
        updateNavItem(
            binding.homeBottomBar.navIconArtists,
            binding.homeBottomBar.navTextArtists,
            tabId == NAV_TAB_ARTISTS,
        )
        updateNavItem(
            binding.homeBottomBar.navIconPlaylists,
            binding.homeBottomBar.navTextPlaylists,
            tabId == NAV_TAB_PLAYLISTS,
        )

        if (!isHome) {
            val targetType =
                when (tabId) {
                    NAV_TAB_SONGS -> MusicType.SONGS
                    NAV_TAB_ALBUMS -> MusicType.ALBUMS
                    NAV_TAB_ARTISTS -> MusicType.ARTISTS
                    NAV_TAB_PLAYLISTS -> MusicType.PLAYLISTS
                    else -> MusicType.SONGS
                }
            val pageIndex = homeModel.currentTabTypes.indexOf(targetType)
            if (pageIndex >= 0) {
                binding.homePager.currentItem = pageIndex
            }
        }
    }

    private fun updateDashboardSongs(binding: FragmentHomeBinding, songs: List<Song>) {
        val topSongs = statsManager.getMostPlayedSongs(songs, 10)
        mostPlayedAdapter?.submitList(topSongs)
        updateDashboardStats(binding)
    }

    private fun updateDashboardArtists(binding: FragmentHomeBinding, artists: List<Artist>) {
        val topArtists = statsManager.getTopArtists(artists, 10)
        topArtistsAdapter?.submitList(topArtists)
    }

    private fun updateDashboardStats(binding: FragmentHomeBinding) {
        val stats7d = statsManager.getStats7Days()
        val statsLt = statsManager.getStatsLifetime()

        val (tracks7d, time7d) =
            if (stats7d.tracksPlayed > 0) {
                stats7d.tracksPlayed to statsManager.formatDuration(stats7d.listenedDurationMs)
            } else {
                0 to "0s"
            }

        val (tracksLt, timeLt) =
            if (statsLt.tracksPlayed > 0) {
                statsLt.tracksPlayed to statsManager.formatDuration(statsLt.listenedDurationMs)
            } else {
                0 to "0s"
            }

        binding.homeDashboard.tvStats7dTracks.text = tracks7d.toString()
        binding.homeDashboard.tvStats7dTime.text = time7d
        binding.homeDashboard.tvStatsLifetimeTracks.text = tracksLt.toString()
        binding.homeDashboard.tvStatsLifetimeTime.text = timeLt
    }

    private fun setupPager(binding: FragmentHomeBinding) {
        binding.homePager.adapter =
            HomePagerAdapter(homeModel.currentTabTypes, childFragmentManager, viewLifecycleOwner)

        val toolbarParams = binding.homeToolbar.layoutParams as AppBarLayout.LayoutParams
        if (homeModel.currentTabTypes.size == 1) {
            // A single tab makes the tab layout redundant, hide it and disable the collapsing
            // behavior.
            L.d("Single tab shown, disabling TabLayout")
            binding.homeTabs.isVisible = false
            binding.homeAppbar.setExpanded(true, false)
            toolbarParams.scrollFlags = 0
        } else {
            binding.homeTabs.isVisible = true
            toolbarParams.scrollFlags =
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
        }

        // Set up the mapping between the ViewPager and TabLayout.
        TabLayoutMediator(
                binding.homeTabs,
                binding.homePager,
                NamedTabStrategy(homeModel.currentTabTypes),
            )
            .attach()
    }

    private fun updateCurrentTab(tabType: MusicType) {
        val binding = requireBinding()

        // Update the scrolling view in AppBarLayout to align with the current tab's
        // scrolling state. This prevents the lift state from being confused as one
        // goes between different tabs.
        binding.homeAppbar.liftOnScrollTargetViewId =
            when (tabType) {
                MusicType.SONGS -> R.id.home_song_recycler
                MusicType.ALBUMS -> R.id.home_album_recycler
                MusicType.ARTISTS -> R.id.home_artist_recycler
                MusicType.GENRES -> R.id.home_genre_recycler
                MusicType.PLAYLISTS -> R.id.home_playlist_recycler
            }
    }

    private fun handleRecreate(recreate: Unit?) {
        if (recreate == null) return
        val binding = requireBinding()
        L.d("Recreating ViewPager")
        // Move back to position zero, as there must be a tab there.
        binding.homePager.currentItem = 0
        // Make sure tabs are set up to also follow the new ViewPager configuration.
        setupPager(binding)
        homeModel.recreateTabs.consume()
    }

    private fun handleChooseFolders(unit: Unit?) {
        if (unit == null) {
            return
        }
        findNavController().navigateSafe(HomeFragmentDirections.chooseLocations())
        homeModel.chooseMusicLocations.consume()
    }

    private fun handleRequestPermission(unit: Unit?) {
        if (unit == null) return
        if (!hasStoragePermission()) {
            val permission = storagePermission()
            if (hasRequestedPermission && !shouldShowRequestPermissionRationale(permission)) {
                L.d("[PERMISSIONS_DEBUG] PERMANENTLY_DENIED, opening app settings")
                requireContext().showToast(R.string.lng_permission_required_settings)
                openAppSettings()
            } else {
                hasRequestedPermission = true
                L.d("[PERMISSIONS_DEBUG] REQUESTING permission=$permission")
                storagePermissionLauncher?.launch(permission)
            }
        } else {
            L.d("[PERMISSIONS_DEBUG] Already granted, nothing to request")
        }
        homeModel.requestPermission.consume()
    }

    private fun openAppSettings() {
        val packageName = requireContext().packageName
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        runCatching { startActivity(intent) }
            .onFailure { L.e("Could not open app settings for $packageName", it) }
    }

    private fun storagePermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), storagePermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateIndexerState(state: IndexingState?) {
        val binding = requireBinding()
        when (state) {
            is IndexingState.Completed -> {
                // The card is GONE, not INVISIBLE, so a hidden card does not keep reserving its
                // corner of the screen. It still holds the error state in its place, so the retry
                // affordance is reachable.
                binding.homeIndexingProgress.isVisible = false
                val report = state.diagnostics
                when {
                    state.error != null -> {
                        binding.homeIndexingContainer.isVisible = true
                        binding.homeIndexingError.isVisible = true
                        binding.homeIndexingLabel.setText(R.string.err_index_failed)
                        binding.homeIndexingContainer.setOnClickListener {
                            findNavController()
                                .navigateSafe(HomeFragmentDirections.reportError(state.error))
                        }
                    }
                    // Completed without error but nothing to show: spend the card on explaining
                    // why, instead of leaving an empty screen that looks like a bug.
                    report != null && report.songs == 0 -> {
                        binding.homeIndexingContainer.isVisible = true
                        binding.homeIndexingError.isVisible = true
                        binding.homeIndexingLabel.text = buildEmptyLibraryMessage(report)
                        binding.homeIndexingContainer.setOnClickListener { musicModel.rescan() }
                    }
                    else -> {
                        binding.homeIndexingContainer.isVisible = false
                        binding.homeIndexingContainer.setOnClickListener(null)
                    }
                }
            }
            is IndexingState.Indexing -> {
                binding.homeIndexingContainer.isVisible = true
                // Clear any error affordance left over from a previous run, otherwise a stray
                // click can still open the error dialog for a finished index.
                binding.homeIndexingContainer.setOnClickListener(null)
                binding.homeIndexingError.isVisible = false
                binding.homeIndexingProgress.apply {
                    isVisible = true
                    when (state.progress) {
                        is IndexingProgress.Songs -> {
                            isIndeterminate = false
                            progress = state.progress.loaded
                            max = state.progress.explored
                            binding.homeIndexingLabel.text =
                                getString(
                                    R.string.fmt_scan_progress,
                                    state.progress.loaded,
                                    state.progress.explored,
                                )
                        }
                        is IndexingProgress.Indeterminate -> {
                            isIndeterminate = true
                            // Reset the determinate counters, otherwise the ring resumes from
                            // whatever the previous Songs update left behind.
                            progress = 0
                            max = 0
                            binding.homeIndexingLabel.setText(R.string.lng_scanning_music)
                        }
                    }
                }
            }
            null -> {
                binding.homeIndexingContainer.isVisible = false
            }
        }
    }

    /**
     * Turn a finished-but-empty run into a reason the user can act on.
     *
     * Distinguishes the two failure modes that look identical: the system reported nothing at all
     * (no permission yet, or nothing indexed), versus files that were reported but dropped.
     */
    private fun buildEmptyLibraryMessage(report: ScanReport): String {
        val context = requireContext()
        if (report.rows == 0) {
            return context.getString(R.string.lng_scan_empty_reason_no_rows)
        }
        return if (report.byReason.containsKey(ScanReject.PROVIDER_FAILED)) {
            context.getString(
                R.string.lng_scan_empty_reason_opened,
                report.rows,
                report.rejected,
            )
        } else {
            context.getString(
                R.string.lng_scan_empty_reason_rejected,
                report.rows,
                report.rejected,
            )
        }
    }

    private fun handlePlaylistDecision(decision: PlaylistDecision?) {
        if (decision == null) return
        val directions =
            when (decision) {
                is PlaylistDecision.New -> {
                    L.d("Creating new playlist")
                    HomeFragmentDirections.newPlaylist(
                        decision.songs.map { it.uid }.toTypedArray(),
                        decision.template,
                        decision.reason,
                    )
                }
                is PlaylistDecision.Import -> {
                    L.d("Importing playlist")
                    pendingImportTarget = decision.target
                    requireNotNull(getContentLauncher) {
                            "Content picker launcher was not available"
                        }
                        .launch(M3U.MIME_TYPE)
                    musicModel.playlistDecision.consume()
                    return
                }
                is PlaylistDecision.Rename -> {
                    L.d("Renaming ${decision.playlist}")
                    HomeFragmentDirections.renamePlaylist(
                        decision.playlist.uid,
                        decision.template,
                        decision.applySongs.map { it.uid }.toTypedArray(),
                        decision.reason,
                    )
                }
                is PlaylistDecision.Export -> {
                    L.d("Exporting ${decision.playlist}")
                    HomeFragmentDirections.exportPlaylist(decision.playlist.uid)
                }
                is PlaylistDecision.Delete -> {
                    L.d("Deleting ${decision.playlist}")
                    HomeFragmentDirections.deletePlaylist(decision.playlist.uid)
                }
                is PlaylistDecision.Add -> {
                    L.d("Adding ${decision.songs.size} to a playlist")
                    HomeFragmentDirections.addToPlaylist(
                        decision.songs.map { it.uid }.toTypedArray()
                    )
                }
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistMessage(message: PlaylistMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.playlistMessage.consume()
    }

    private fun handleSongDecision(decision: SongDecision?) {
        if (decision == null) return
        musicModel.songDecision.consume()
        val directions =
            when (decision) {
                is SongDecision.Delete -> {
                    L.d("Deleting ${decision.song}")
                    HomeFragmentDirections.deleteSong(decision.song.uid)
                }
            }
        findNavController().navigateSafe(directions)
    }

    private fun handleSongMessage(message: SongMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.songMessage.consume()
    }

    private fun handlePlaybackDecision(decision: PlaybackDecision?) {
        when (decision) {
            is PlaybackDecision.PlayFromArtist -> {
                findNavController()
                    .navigateSafe(HomeFragmentDirections.playFromArtist(decision.song.uid))
            }
            is PlaybackDecision.PlayFromGenre -> {
                findNavController()
                    .navigateSafe(HomeFragmentDirections.playFromGenre(decision.song.uid))
            }
            null -> {}
        }
    }

    private fun handleShow(show: Show?) {
        when (show) {
            is Show.SongDetails -> {
                L.d("Navigating to ${show.song}")
                findNavController().navigateSafe(HomeFragmentDirections.showSong(show.song.uid))
            }
            is Show.SongAlbumDetails -> {
                L.d("Navigating to the album of ${show.song}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showAlbum(show.song.album.uid))
            }
            is Show.AlbumDetails -> {
                L.d("Navigating to ${show.album}")
                findNavController().navigateSafe(HomeFragmentDirections.showAlbum(show.album.uid))
            }
            is Show.ArtistDetails -> {
                L.d("Navigating to ${show.artist}")
                findNavController().navigateSafe(HomeFragmentDirections.showArtist(show.artist.uid))
            }
            is Show.SongArtistDecision -> {
                L.d("Navigating to artist choices for ${show.song}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showArtistChoices(show.song.uid))
            }
            is Show.AlbumArtistDecision -> {
                L.d("Navigating to artist choices for ${show.album}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showArtistChoices(show.album.uid))
            }
            is Show.GenreDetails -> {
                L.d("Navigating to ${show.genre}")
                findNavController().navigateSafe(HomeFragmentDirections.showGenre(show.genre.uid))
            }
            is Show.PlaylistDetails -> {
                L.d("Navigating to ${show.playlist}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showPlaylist(show.playlist.uid))
            }
            null -> {}
        }
    }

    private fun handleMenu(menu: Menu?) {
        if (menu == null) return
        val directions =
            when (menu) {
                is Menu.ForSong -> HomeFragmentDirections.openSongMenu(menu.parcel)
                is Menu.ForAlbum -> HomeFragmentDirections.openAlbumMenu(menu.parcel)
                is Menu.ForArtist -> HomeFragmentDirections.openArtistMenu(menu.parcel)
                is Menu.ForGenre -> HomeFragmentDirections.openGenreMenu(menu.parcel)
                is Menu.ForPlaylist -> HomeFragmentDirections.openPlaylistMenu(menu.parcel)
                is Menu.ForSelection -> HomeFragmentDirections.openSelectionMenu(menu.parcel)
            }
        findNavController().navigateSafe(directions)
    }

    private fun updateSelection(selected: List<Music>) {
        val binding = requireBinding()
        if (selected.isNotEmpty()) {
            binding.homeSelectionToolbar.title = getString(R.string.fmt_selected, selected.size)
            if (binding.homeToolbar.setVisible(R.id.home_selection_toolbar)) {
                // New selection started, show the AppBarLayout to indicate the new state.
                L.d("Significant selection occurred, expanding AppBar")
                binding.homeAppbar.expandWithScrollingRecycler()
            }
        } else {
            binding.homeToolbar.setVisible(R.id.home_normal_toolbar)
        }
    }

    /**
     * [FragmentStateAdapter] implementation for the [HomeFragment]'s [ViewPager2] instance.
     *
     * @param tabs The current tab configuration. This will define the [Fragment]s created.
     * @param fragmentManager The [FragmentManager] required by [FragmentStateAdapter].
     * @param lifecycleOwner The [LifecycleOwner], whose Lifecycle is required by
     *   [FragmentStateAdapter].
     */
    private class HomePagerAdapter(
        private val tabs: List<MusicType>,
        fragmentManager: FragmentManager,
        lifecycleOwner: LifecycleOwner,
    ) : FragmentStateAdapter(fragmentManager, lifecycleOwner.lifecycle) {
        override fun getItemCount() = tabs.size

        override fun createFragment(position: Int): Fragment =
            when (tabs[position]) {
                MusicType.SONGS -> SongListFragment()
                MusicType.ALBUMS -> AlbumListFragment()
                MusicType.ARTISTS -> ArtistListFragment()
                MusicType.GENRES -> GenreListFragment()
                MusicType.PLAYLISTS -> PlaylistListFragment()
            }
    }
}
