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

/**
 * A [ViewBindingMaterialDialogFragment] that asks the user to confirm the deletion of a [Song].
 */
@AndroidEntryPoint
class DeleteSongDialog : ViewBindingMaterialDialogFragment<DialogDeleteSongBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    // Information about what song to delete is initially within the navigation arguments as UIDs,
    // as that is the only safe way to parcel song information.
    private val args: DeleteSongDialogArgs by navArgs()

    private lateinit var songToDelete: Song

    private val mediaStoreRequestCode = 1
    private val permissionRequestCode = 2

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_confirm_delete_song)
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
        val song = musicModel.findSong(args.songUid)
        if (song == null) {
            L.d("No song to delete, navigating away")
            findNavController().navigateUp()
            return
        }
        songToDelete = song

        requireBinding().deletionInfo.text =
            getString(R.string.fmt_deletion_info, song.name.resolve(requireContext()))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == mediaStoreRequestCode) {
            if (resultCode == Activity.RESULT_OK) {
                performDelete(songToDelete)
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
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                performDelete(songToDelete)
            } else {
                showNoPermission()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun onConfirmDelete() {
        // Safely move past the currently playing/deleted song if it is the one being deleted.
        if (playbackModel.song.value?.uid == songToDelete.uid) {
            playbackModel.next()
        }

        val uri = songToDelete.uri
        // Songs added via SAF/All files access flow (ex: OTG drives, external SD cards) are deleted
        // through a tree document, which does not require any extra permissions.
        if (DocumentsContract.isDocumentUri(requireContext(), uri)) {
            performDelete(songToDelete)
            return
        }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+: Ask the user directly to delete the media file.
                val pendingIntent =
                    MediaStore.createDeleteRequest(
                        requireContext().contentResolver,
                        listOf(songToDelete.uri),
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
                // Android 10: Ask the user to grant write access to the media file.
                val pendingIntent =
                    MediaStore.createWriteRequest(
                        requireContext().contentResolver,
                        listOf(songToDelete.uri),
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

    private fun performDelete(song: Song) {
        musicModel.deleteSong(song, rude = true)
    }

    private fun showNoPermission() {
        requireContext().showToast(R.string.err_delete_failed)
    }
}