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
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import org.oxycblt.musikr.Music
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
    private var hasAutoLaunched = false

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                performDelete(pendingMediaStoreSongs)
            }
            findNavController().navigateUp()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                performDelete(pendingMediaStoreSongs)
            } else {
                showNoPermission()
            }
            findNavController().navigateUp()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAutoLaunched = savedInstanceState?.getBoolean(KEY_AUTO_LAUNCHED, false) ?: false
        val savedUids = savedInstanceState?.getStringArrayList(KEY_PENDING_MEDIA_STORE_SONGS)
        if (savedUids != null) {
            pendingMediaStoreSongs = savedUids.mapNotNull {
                Music.UID.fromString(it)?.let { uid -> musicModel.findSong(uid) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_LAUNCHED, hasAutoLaunched)
        if (pendingMediaStoreSongs.isNotEmpty()) {
            outState.putStringArrayList(
                KEY_PENDING_MEDIA_STORE_SONGS,
                ArrayList(pendingMediaStoreSongs.map { it.uid.toString() }),
            )
        }
    }

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        val titleRes =
            if (args.songUids.size > 1) {
                R.string.lbl_confirm_delete_selection
            } else {
                R.string.lbl_confirm_delete_song
            }
        builder
            .setTitle(titleRes)
            .setPositiveButton(R.string.lbl_delete, null)
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onStart() {
        super.onStart()
        val isAllMediaStoreOnR =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ::songsToDelete.isInitialized &&
                songsToDelete.isNotEmpty() &&
                songsToDelete.none { DocumentsContract.isDocumentUri(requireContext(), it.uri) }

        if (isAllMediaStoreOnR) {
            dialog?.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawableResource(android.R.color.transparent)
                decorView.visibility = View.INVISIBLE
            }
            if (!hasAutoLaunched) {
                hasAutoLaunched = true
                onConfirmDelete()
            }
        } else {
            (requireDialog() as? AlertDialog)?.let { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    onConfirmDelete()
                }
            }
        }
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

    private fun onConfirmDelete() {
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
            findNavController().navigateUp()
            return
        }

        pendingMediaStoreSongs = mediaStoreSongs

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+: Ask the user directly to delete the media files.
                try {
                    val mediaStoreUris = mediaStoreSongs.map { it.uri }
                    val pendingIntent =
                        MediaStore.createDeleteRequest(
                            requireContext().contentResolver,
                            mediaStoreUris,
                        )
                    val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    deleteLauncher.launch(request)
                } catch (e: Exception) {
                    L.e(e, "createDeleteRequest failed, attempting direct delete fallback")
                    performDelete(mediaStoreSongs)
                    findNavController().navigateUp()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10: Ask the user to grant write access to the media files.
                try {
                    val mediaStoreUris = mediaStoreSongs.map { it.uri }
                    val pendingIntent =
                        MediaStore.createWriteRequest(
                            requireContext().contentResolver,
                            mediaStoreUris,
                        )
                    val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    deleteLauncher.launch(request)
                } catch (e: Exception) {
                    L.e(e, "createWriteRequest failed on Android 10")
                    performDelete(mediaStoreSongs)
                    findNavController().navigateUp()
                }
            }
            else -> {
                // Android 9 and below: WRITE_EXTERNAL_STORAGE is required.
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun performDelete(songs: List<Song>) {
        val currentlyPlayingUid = playbackModel.song.value?.uid
        if (currentlyPlayingUid != null && songs.any { it.uid == currentlyPlayingUid }) {
            playbackModel.next()
        }
        musicModel.deleteSongs(songs, rude = true)
    }

    private fun showNoPermission() {
        requireContext().showToast(R.string.err_delete_failed)
    }

    private companion object {
        const val KEY_AUTO_LAUNCHED = "auto_launched"
        const val KEY_PENDING_MEDIA_STORE_SONGS = "pending_media_store_songs"
    }
}
