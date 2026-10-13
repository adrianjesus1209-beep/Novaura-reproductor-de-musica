/*
 * Copyright (c) 2026 Auxio Project
 * FavoritesDetailFragment.kt is part of Auxio.
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
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
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
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.music.FavoritesManager
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getDimenPixels
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.Song

/**
 * A detail view presenting favorite songs as a dedicated album ("como un álbum pero apartado").
 * Features rich album artwork stack, count, play/shuffle actions, and track list.
 */
@AndroidEntryPoint
class FavoritesDetailFragment :
    ListFragment<Song, FragmentDetailBinding>(),
    AppBarLayout.OnOffsetChangedListener,
    Toolbar.OnMenuItemClickListener {

    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var favoritesManager: FavoritesManager

    private val songAdapter = SongAdapter(this)
    private var spacingSmall = 0

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
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(this@FavoritesDetailFragment)
        }

        binding.detailRecycler.apply {
            adapter = songAdapter
        }

        spacingSmall = requireContext().getDimenPixels(R.dimen.spacing_small)

        collectImmediately(favoritesManager.favoriteSongs) {
            updateFavorites(binding)
        }
        collectImmediately(favoritesManager.favoriteUids) {
            updateFavorites(binding)
        }

        collectImmediately(
            playbackModel.song,
            playbackModel.isPlaying,
        ) { song, isPlaying ->
            songAdapter.setPlaying(song, isPlaying)
        }

        collectImmediately(listModel.selected) { selection ->
            songAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
        }
    }

    override fun onDestroyBinding(binding: FragmentDetailBinding) {
        super.onDestroyBinding(binding)
        binding.detailAppbar?.removeOnOffsetChangedListener(this)
        binding.detailNormalToolbar.setOnMenuItemClickListener(null)
        binding.detailRecycler.adapter = null
    }

    private fun updateFavorites(binding: FragmentDetailBinding) {
        val songs = favoritesManager.getFavoriteSongs()
        val context = requireContext()

        binding.detailType.text = getString(R.string.lbl_album).uppercase()
        binding.detailName.text = getString(R.string.lbl_favorites)

        val totalDurationMs = songs.sumOf { it.durationMs }
        val durationFormatted = totalDurationMs.formatDurationMs(true)
        val songCountStr = context.getPlural(R.plurals.fmt_song_count, songs.size)
        binding.detailInfo.text = "$songCountStr • $durationFormatted"

        if (songs.isNotEmpty()) {
            binding.detailSubhead.text = getString(R.string.lbl_favorites_album_subhead)
            binding.detailCover.bind(
                songs,
                getString(R.string.lbl_favorites),
                R.drawable.ic_favorite_24,
            )
        } else {
            binding.detailSubhead.text = getString(R.string.lbl_favorites_empty_desc)
            binding.detailCover.bind(
                emptyList(),
                getString(R.string.lbl_favorites),
                R.drawable.ic_favorite_24,
            )
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

        songAdapter.update(songs, org.oxycblt.auxio.list.adapter.UpdateInstructions.Diff)
    }

    override fun onRealClick(item: Song) {
        val songs = favoritesManager.getFavoriteSongs()
        playbackModel.playFromSongList(item, songs)
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, PlaySong.FromAll)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val songs = favoritesManager.getFavoriteSongs()
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

    private class SongAdapter(private val listener: SelectableListListener<Song>) :
        SelectionIndicatorAdapter<Song, SongViewHolder>(SongViewHolder.DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SongViewHolder.from(parent)

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
