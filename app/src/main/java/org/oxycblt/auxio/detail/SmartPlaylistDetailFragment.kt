/*
 * Copyright (c) 2026 Auxio Project
 * SmartPlaylistDetailFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.transition.MaterialSharedAxis
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDetailBinding
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.list.menu.Menu
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.PlaylistDecision
import org.oxycblt.auxio.music.PlaylistMessage
import org.oxycblt.auxio.music.SongDecision
import org.oxycblt.auxio.music.SongMessage
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackDecision
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.WallpaperHelper
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getDimenPixels
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Shared base class for smart playlist detail screens (Favorites, Most Played, Recently Added) with
 * unified header formatting, collapsing toolbar transitions, multi-select toolbar support, and
 * adaptive wallpaper scrims.
 */
abstract class SmartPlaylistDetailFragment :
    ListFragment<Song, FragmentDetailBinding>(),
    AppBarLayout.OnOffsetChangedListener,
    Toolbar.OnMenuItemClickListener {

    @Inject lateinit var uiSettings: UISettings
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    protected val detailModel: DetailViewModel by activityViewModels()

    private val songAdapter = SongAdapter(this)
    private var spacingSmall = 0

    @get:StringRes protected abstract val titleRes: Int

    @get:StringRes protected abstract val subheadRes: Int

    @get:StringRes protected abstract val emptyDescRes: Int

    @get:DrawableRes protected abstract val coverIconRes: Int

    protected abstract fun loadSongs(): List<Song>

    protected abstract fun observeSongUpdates()

    protected abstract fun openSongMenuDirections(parcel: Menu.ForSong.Parcel): NavDirections

    protected abstract fun openSelectionMenuDirections(
        parcel: Menu.ForSelection.Parcel
    ): NavDirections

    protected abstract fun deleteSongDirections(uids: Array<Music.UID>): NavDirections

    protected abstract fun showSongDirections(uid: Music.UID): NavDirections

    protected abstract fun showAlbumDirections(uid: Music.UID): NavDirections

    protected abstract fun showArtistDirections(uid: Music.UID): NavDirections

    protected abstract fun showArtistChoicesDirections(uid: Music.UID): NavDirections

    protected abstract fun addToPlaylistDirections(uids: Array<Music.UID>): NavDirections

    protected abstract fun playFromArtistDirections(uid: Music.UID): NavDirections

    protected abstract fun playFromGenreDirections(uid: Music.UID): NavDirections

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentDetailBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentDetailBinding) =
        binding.detailSelectionToolbar

    override fun onBindingCreated(binding: FragmentDetailBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        applyWallpaperBackground(binding)
        binding.detailAppbar?.addOnOffsetChangedListener(this)

        val pane = binding.detailPane
        if (pane != null) {
            binding.detailToolbar.setOnApplyWindowInsetsListener { view, insets ->
                view.updatePadding(top = insets.systemBarInsetsCompat.top)
                insets
            }
            binding.detailNormalToolbar.titleContainer.alpha = 0f
            pane.setOnApplyWindowInsetsListener { view, insets ->
                view.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
                insets
            }
        }

        binding.detailNormalToolbar.apply {
            title = getString(titleRes)
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(this@SmartPlaylistDetailFragment)
        }

        binding.detailRecycler.apply { adapter = songAdapter }

        spacingSmall = requireContext().getDimenPixels(R.dimen.spacing_small)

        observeSongUpdates()

        collectImmediately(playbackModel.song, playbackModel.isPlaying) { song, isPlaying ->
            songAdapter.setPlaying(song, isPlaying)
        }

        collectImmediately(listModel.selected, ::updateSelection)

        collect(detailModel.toShow.flow, ::handleShow)
        collect(listModel.menu.flow, ::handleMenu)
        collect(musicModel.playlistDecision.flow, ::handlePlaylistDecision)
        collect(musicModel.playlistMessage.flow, ::handlePlaylistMessage)
        collect(musicModel.songDecision.flow, ::handleSongDecision)
        collect(musicModel.songMessage.flow, ::handleSongMessage)
        collect(playbackModel.playbackDecision.flow, ::handlePlaybackDecision)
    }

    override fun onResume() {
        super.onResume()
        binding?.let(::applyWallpaperBackground)
    }

    override fun onDestroyBinding(binding: FragmentDetailBinding) {
        super.onDestroyBinding(binding)
        binding.detailAppbar?.removeOnOffsetChangedListener(this)
        binding.detailNormalToolbar.setOnMenuItemClickListener(null)
        binding.detailRecycler.adapter = null
    }

    protected fun refreshSmartSongs() {
        val binding = binding ?: return
        val songs = loadSongs()
        val context = requireContext()
        val title = getString(titleRes)

        binding.detailNormalToolbar.title = title
        binding.detailType.text = getString(R.string.lbl_playlist).uppercase()
        binding.detailName.text = title

        val totalDurationMs = songs.sumOf { it.durationMs }
        val durationFormatted = totalDurationMs.formatDurationMs(true)
        val songCountStr = context.getPlural(R.plurals.fmt_song_count, songs.size)
        binding.detailInfo.text = getString(R.string.fmt_two, songCountStr, durationFormatted)

        if (songs.isNotEmpty()) {
            binding.detailSubhead.text = getString(subheadRes)
            binding.detailCover.bind(songs, title, coverIconRes)
        } else {
            binding.detailSubhead.text = getString(emptyDescRes)
            binding.detailCover.bind(emptyList(), title, coverIconRes)
        }

        val hasSongs = songs.isNotEmpty()
        binding.detailNormalToolbar.apply {
            setMenuItemEnabled(R.id.action_play, hasSongs)
            setMenuItemEnabled(R.id.action_shuffle, hasSongs)
        }
        binding.detailPlayButton?.apply {
            isEnabled = hasSongs
            setOnClickListener { playbackModel.play(songs) }
        }
        binding.detailShuffleButton?.apply {
            isEnabled = hasSongs
            setOnClickListener { playbackModel.shuffle(songs) }
        }

        songAdapter.update(songs, UpdateInstructions.Diff)
    }

    override fun onRealClick(item: Song) {
        val songs = loadSongs()
        playbackModel.playFromSongList(item, songs)
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, PlaySong.FromAll)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) {
            return true
        }
        val songs = loadSongs()
        return when (item.itemId) {
            R.id.action_play -> {
                if (songs.isNotEmpty()) {
                    playbackModel.play(songs)
                }
                true
            }
            R.id.action_shuffle -> {
                if (songs.isNotEmpty()) {
                    playbackModel.shuffle(songs)
                }
                true
            }
            else -> false
        }
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        val binding = requireBinding()
        val range = appBarLayout.totalScrollRange
        if (range == 0) return
        val ratio = abs(verticalOffset.toFloat()) / range.toFloat()

        val outRatio = min(ratio * 2, 1f)
        val detailHeader = binding.detailHeader
        if (detailHeader != null) {
            detailHeader.scaleX = 1 - 0.2f * outRatio / (5f / 3f)
            detailHeader.scaleY = 1 - 0.2f * outRatio / (5f / 3f)
            detailHeader.alpha = 1 - outRatio
        }

        val inRatio = max(ratio - 0.5f, 0f) * 2
        animateToolbarView(binding.detailNormalToolbar.titleContainer, inRatio)
        animateToolbarActionButton(
            binding.detailNormalToolbar.getMenuButton(R.id.action_play),
            inRatio,
        )
        animateToolbarActionButton(
            binding.detailNormalToolbar.getMenuButton(R.id.action_shuffle),
            inRatio,
        )

        binding.detailRecycler.fastScrollingEnabled = ratio == 1f
    }

    private fun applyWallpaperBackground(binding: FragmentDetailBinding) {
        WallpaperHelper.applySecondaryWallpaperBackground(
            binding.detailCoordinator ?: binding.detailConstraint,
            uiSettings,
            binding.detailAppbar,
            binding.detailToolbar,
        )
    }

    private fun updateSelection(selected: List<Music>) {
        songAdapter.setSelected(selected.filterIsInstanceTo(mutableSetOf()))

        val binding = requireBinding()
        if (selected.isNotEmpty()) {
            binding.detailSelectionToolbar.title = getString(R.string.fmt_selected, selected.size)
            binding.detailToolbar.setVisible(R.id.detail_selection_toolbar)
        } else {
            binding.detailToolbar.setVisible(R.id.detail_normal_toolbar)
        }
    }

    private fun animateToolbarView(view: View?, ratio: Float) {
        if (view == null) return
        view.alpha = ratio
        view.translationY = spacingSmall * (1 - ratio)
    }

    private fun animateToolbarActionButton(view: View?, ratio: Float) {
        if (view == null) return

        if (ratio <= 0f) {
            view.alpha = 0f
            view.translationY = spacingSmall.toFloat()
            view.visibility = View.GONE
            return
        }

        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        view.alpha = ratio
        view.translationY = spacingSmall * (1 - ratio)
    }

    private fun handleMenu(menu: Menu?) {
        if (menu == null) return
        val directions =
            when (menu) {
                is Menu.ForSong -> openSongMenuDirections(menu.parcel)
                is Menu.ForSelection -> openSelectionMenuDirections(menu.parcel)
                else -> return
            }
        findNavController().navigateSafe(directions)
    }

    private fun handleSongDecision(decision: SongDecision?) {
        if (decision == null) return
        musicModel.songDecision.consume()
        val directions =
            when (decision) {
                is SongDecision.Delete -> {
                    L.d("Deleting ${decision.songs.size} songs")
                    deleteSongDirections(decision.songs.map { it.uid }.toTypedArray())
                }
            }
        findNavController().navigateSafe(directions)
    }

    private fun handleSongMessage(message: SongMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.songMessage.consume()
    }

    private fun handleShow(show: Show?) {
        if (show == null) return
        val directions =
            when (show) {
                is Show.SongDetails -> showSongDirections(show.song.uid)
                is Show.SongAlbumDetails -> showAlbumDirections(show.song.album.uid)
                is Show.AlbumDetails -> showAlbumDirections(show.album.uid)
                is Show.ArtistDetails -> showArtistDirections(show.artist.uid)
                is Show.SongArtistDecision -> showArtistChoicesDirections(show.song.uid)
                is Show.AlbumArtistDecision -> showArtistChoicesDirections(show.album.uid)
                else -> return
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistDecision(decision: PlaylistDecision?) {
        if (decision == null) return
        musicModel.playlistDecision.consume()
        val directions =
            when (decision) {
                is PlaylistDecision.Add ->
                    addToPlaylistDirections(decision.songs.map { it.uid }.toTypedArray())
                else -> return
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistMessage(message: PlaylistMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.playlistMessage.consume()
    }

    private fun handlePlaybackDecision(decision: PlaybackDecision?) {
        if (decision == null) return
        val directions =
            when (decision) {
                is PlaybackDecision.PlayFromArtist -> playFromArtistDirections(decision.song.uid)
                is PlaybackDecision.PlayFromGenre -> playFromGenreDirections(decision.song.uid)
            }
        findNavController().navigateSafe(directions)
    }

    private class SongAdapter(private val listener: SelectableListListener<Song>) :
        SelectionIndicatorAdapter<Song, SongViewHolder>(SongViewHolder.DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SongViewHolder.from(parent)

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
