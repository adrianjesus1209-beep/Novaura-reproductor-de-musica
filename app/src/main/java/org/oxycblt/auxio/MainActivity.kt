/*
 * Copyright (c) 2021 Auxio Project
 * MainActivity.kt is part of Auxio.
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
 
package org.oxycblt.auxio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.databinding.ActivityMainBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.systemBarInsetsCompat
import timber.log.Timber as L

/**
 * Auxio's single [AppCompatActivity].
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Add error screens
 * TODO: Use proper material attributes (Not the weird dimen attributes I currently have)
 * TODO: Migrate to material animation system
 * TODO: Unit testing
 * TODO: Fix UID naming
 * TODO: Leverage FlexibleListAdapter more in dialogs (Disable item anims)
 * TODO: Improve multi-threading support in shared objects
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val playbackModel: PlaybackViewModel by viewModels()
    private val musicModel: MusicViewModel by viewModels()
    @Inject lateinit var uiSettings: UISettings

    private val appPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val audioPermission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            val audioGranted =
                results[audioPermission] == true ||
                    ContextCompat.checkSelfPermission(this, audioPermission) ==
                        PackageManager.PERMISSION_GRANTED
            val notifGranted = results[Manifest.permission.POST_NOTIFICATIONS] ?: true
            L.d("App permissions result: audioGranted=$audioGranted, notifGranted=$notifGranted")
            if (audioGranted) {
                musicModel.refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiSettings.applyCurrentLanguage()
        setupTheme()
        // Inflate the views after setting up the theme so that the theme attributes are applied.
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)

        requestAppPermissions()

        L.d("Activity created")
    }

    private fun requestAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 1. Audio / Music storage permission (mandatory to discover songs)
        val audioPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        if (
            ContextCompat.checkSelfPermission(this, audioPermission) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(audioPermission)
        }

        // On Android 9 and below, write storage permission was needed for deleting songs
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // 2. Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            L.d("Requesting startup permissions: $permissionsToRequest")
            appPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()

        startService(
            Intent(this, AuxioService::class.java)
                .setAction(AuxioService.ACTION_START)
                .putExtra(AuxioService.INTENT_KEY_START_ID, IntegerTable.START_ID_ACTIVITY)
        )

        if (!startIntentAction(intent)) {
            // No intent action to do, just restore the previously saved state.
            playbackModel.playDeferred(DeferredPlayback.RestoreState(false))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startIntentAction(intent)
    }

    private fun setupTheme() {
        // Apply the theme configuration.
        AppCompatDelegate.setDefaultNightMode(uiSettings.theme)
        // Apply the color scheme. The black theme requires it's own set of themes since
        // it's not possible to modify the themes at run-time.
        if (isNight && uiSettings.useBlackTheme) {
            L.d("Applying black theme [accent ${uiSettings.accent}]")
            setTheme(uiSettings.accent.blackTheme)
        } else {
            L.d("Applying normal theme [accent ${uiSettings.accent}]")
            setTheme(uiSettings.accent.theme)
        }
    }

    private fun setupEdgeToEdge(contentView: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        contentView.setOnApplyWindowInsetsListener { view, insets ->
            // Automatically inset the view to the left/right, as component support for
            // these insets are highly lacking.
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    /**
     * Transform an [Intent] given to [MainActivity] into a [DeferredPlayback] that can be used in
     * the playback system.
     *
     * @param intent The (new) [Intent] given to this [MainActivity], or null if there is no intent.
     * @return true If the analogous [DeferredPlayback] to the given [Intent] was started, false
     *   otherwise.
     */
    private fun startIntentAction(intent: Intent?): Boolean {
        if (intent == null) {
            // Nothing to do.
            L.d("No intent to handle")
            return false
        }

        if (intent.getBooleanExtra(KEY_INTENT_USED, false)) {
            // Don't commit the action, but also return that the intent was applied.
            // This is because onStart can run multiple times, and thus we really don't
            // want to return false and override the original delayed action with a
            // RestoreState action.
            L.d("Already used this intent")
            return true
        }
        intent.putExtra(KEY_INTENT_USED, true)

        val action =
            when (intent.action) {
                Intent.ACTION_VIEW -> DeferredPlayback.Open(intent.data ?: return false)
                Auxio.INTENT_KEY_SHORTCUT_SHUFFLE -> DeferredPlayback.ShuffleAll
                else -> {
                    L.w("Unexpected intent ${intent.action}")
                    return false
                }
            }
        L.d("Translated intent to $action")
        playbackModel.playDeferred(action)
        return true
    }

    private companion object {
        const val KEY_INTENT_USED = BuildConfig.APPLICATION_ID + ".key.FILE_INTENT_USED"
    }
}
