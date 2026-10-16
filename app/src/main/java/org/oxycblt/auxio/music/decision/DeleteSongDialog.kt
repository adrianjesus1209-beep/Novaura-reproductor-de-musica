/*
 * Copyright (c) 2023 Auxio Project
 * DeleteSongDialog.kt is part of Auxio.
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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogDeleteSongBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** A [ViewBindingMaterialDialogFragment] that asks the user to confirm the deletion of [Song]s. */
@AndroidEntryPoint
class DeleteSongDialog : ViewBindingMaterialDialogFragment<DialogDeleteSongBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    // Information about what songs to delete is initially within the navigation arguments as UIDs,
    // as that is the only safe way to parcel song information.
    private val args: DeleteSongDialogArgs by navArgs()

    private lateinit var songsToDelete: List<Song>
    private var pendingMediaStoreSongs: List<Song> = emptyList()

    private val mediaStoreRequestCode = 1
    private val permissionRequestCode = 2

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        val titleRes =
            if (args.songUids.size > 1) {
                R.string.lbl_confirm_delete_selection
            } else {
                R.string.lbl_confirm_delete_song
            }
        builder
            .setTitle(titleRes)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                // Normally the navigateUp will occur after this, which then collides with the
                // view's navigation. Forcefully navigate up to stop this.
                findNavController().navigateUp()
                onConfirmDelete()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: android.view.LayoutInflater) =
        DialogDeleteSongBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogDeleteSongBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- VIEWMODEL SETUP ---
        musicModel.songDecision.consume()
        val songs = args.songUids.mapNotNull { musicModel.findSong(it) }
        if (songs.isEmpty()) {
            L.d("No songs to delete, navigating away")
            findNavController().navigateUp()
            return
        }
        songsToDelete = songs

        if (songs.size > 1) {
            requireBinding().deletionInfo.text =
                getString(R.string.fmt_deletion_info_plural, songs.size)
        } else {
            val song = songs.first()
            requireBinding().deletionInfo.text =
                getString(R.string.fmt_deletion_info, song.name.resolve(requireContext()))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == mediaStoreRequestCode) {
            if (resultCode == Activity.RESULT_OK) {
                performDelete(pendingMediaStoreSongs)
            } else {
                showNoPermission()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == permissionRequestCode) {
            val granted =
                grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                performDelete(pendingMediaStoreSongs)
            } else {
                showNoPermission()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun onConfirmDelete() {
        val currentlyPlayingUid = playbackModel.song.value?.uid
        if (currentlyPlayingUid != null && songsToDelete.any { it.uid == currentlyPlayingUid }) {
            playbackModel.next()
        }

        // Separate songs into SAF tree uris and MediaStore uris
        val (safSongs, mediaStoreSongs) =
            songsToDelete.partition {
                DocumentsContract.isDocumentUri(requireContext(), it.uri)
            }

        // If there are SAF songs, delete them directly
        if (safSongs.isNotEmpty()) {
            performDelete(safSongs)
        }

        if (mediaStoreSongs.isEmpty()) {
            return
        }

        pendingMediaStoreSongs = mediaStoreSongs
        val mediaStoreUris = mediaStoreSongs.map { it.uri }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+: Ask the user directly to delete the media files.
                val pendingIntent =
                    MediaStore.createDeleteRequest(
                        requireContext().contentResolver,
                        mediaStoreUris,
                    )
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    mediaStoreRequestCode,
                    null,
                    0,
                    0,
                    0,
                    null,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10: Ask the user to grant write access to the media files.
                val pendingIntent =
                    MediaStore.createWriteRequest(
                        requireContext().contentResolver,
                        mediaStoreUris,
                    )
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    mediaStoreRequestCode,
                    null,
                    0,
                    0,
                    0,
                    null,
                )
            }
            else -> {
                // Android 9 and below: WRITE_EXTERNAL_STORAGE is required.
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    permissionRequestCode,
                )
            }
        }
    }

    private fun performDelete(songs: List<Song>) {
        musicModel.deleteSongs(songs, rude = true)
    }

    private fun showNoPermission() {
        requireContext().showToast(R.string.err_delete_failed)
    }
}
