/*
 * Copyright (c) 2023 Auxio Project
 * Settings.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import java.util.concurrent.CopyOnWriteArraySet
import timber.log.Timber as L

/**
 * Abstract user configuration information. This interface has no functionality whatsoever. Concrete
 * implementations should be preferred instead.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface Settings<Listener> {
    /**
     * Migrate any settings fields from older versions into their new counterparts.
     *
     * @throws NotImplementedError If there is nothing to migrate.
     */
    fun migrate() {
        throw NotImplementedError()
    }

    /**
     * Add a listener to monitor for settings updates. Will do nothing if
     *
     * @param listener The listener to add.
     */
    fun registerListener(listener: Listener)

    /**
     * Unregister a listener, preventing any further settings updates from being sent to it.
     *
     * @param listener The listener to unregister, must be the same as the current listener.
     */
    fun unregisterListener(listener: Listener)

    /**
     * A framework-backed [Settings] implementation.
     *
     * @param context [Context] required.
     */
    abstract class Impl<Listener>(private val context: Context) :
        Settings<Listener>, SharedPreferences.OnSharedPreferenceChangeListener {
        protected val sharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

        /** @see [Context.getString] */
        protected fun getString(@StringRes stringRes: Int) = context.getString(stringRes)

        private val listeners = CopyOnWriteArraySet<Listener>()

        override fun registerListener(listener: Listener) {
            synchronized(listeners) {
                val wasEmpty = listeners.isEmpty()
                listeners.add(listener)
                if (wasEmpty) {
                    L.d("Registering shared preference listener for ${this::class.simpleName}")
                    sharedPreferences.registerOnSharedPreferenceChangeListener(this)
                }
            }
            L.d("Registering listener $listener for ${this::class.simpleName}")
        }

        override fun unregisterListener(listener: Listener) {
            synchronized(listeners) {
                if (!listeners.remove(listener)) {
                    L.w("Given listener was not registered for ${this::class.simpleName}.")
                    return
                }
                L.d("Unregistering listener $listener")
                if (listeners.isEmpty()) {
                    sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
                }
            }
        }

        final override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences,
            key: String?,
        ) {
            if (key == null) return
            // FIXME: Settings initialization firing the listener.
            L.d("Dispatching settings change $key")
            for (listener in listeners) {
                onSettingChanged(key, listener)
            }
        }

        /**
         * Called when a setting entry with the given [key] has changed.
         *
         * @param key The key of the changed setting.
         * @param listener The implementation's listener that updates should be applied to.
         */
        protected open fun onSettingChanged(key: String, listener: Listener) {}
    }
}
