package com.ryanvidal.barfriend.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.VolumeUnit
import com.ryanvidal.barfriend.core.platform.PlatformPaths
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

data class UserSettings(
    val serverUrl: String? = null,
    val activeBarId: Int? = null,
    val activeBarName: String? = null,
    val activeUserId: Int? = null,
    val preferredUnit: VolumeUnit = VolumeUnit.OZ,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
)

@SingleIn(AppScope::class)
@Inject
class AppPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            serverUrl = prefs[KEY_SERVER_URL],
            activeBarId = prefs[KEY_ACTIVE_BAR_ID],
            activeBarName = prefs[KEY_ACTIVE_BAR_NAME],
            activeUserId = prefs[KEY_USER_ID],
            preferredUnit = VolumeUnit.fromApiKeyOrNull(prefs[KEY_PREFERRED_UNIT]) ?: VolumeUnit.OZ,
            themeMode = prefs[KEY_THEME_MODE]?.let { raw ->
                AppThemeMode.entries.firstOrNull { it.name == raw }
            } ?: AppThemeMode.DARK,
        )
    }

    suspend fun currentSettings(): UserSettings = settings.first()

    suspend fun setServerConnection(serverUrl: String, barId: Int?, barName: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
            if (barId != null) {
                prefs[KEY_ACTIVE_BAR_ID] = barId
            } else {
                prefs.remove(KEY_ACTIVE_BAR_ID)
            }
            if (barName != null) {
                prefs[KEY_ACTIVE_BAR_NAME] = barName
            } else {
                prefs.remove(KEY_ACTIVE_BAR_NAME)
            }
        }
    }

    suspend fun setPreferredUnit(unit: VolumeUnit) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFERRED_UNIT] = unit.apiKey
        }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun setActiveUserId(userId: Int?) {
        dataStore.edit { prefs ->
            if (userId != null) {
                prefs[KEY_USER_ID] = userId
            } else {
                prefs.remove(KEY_USER_ID)
            }
        }
    }

    suspend fun clearSessionMetadata() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ACTIVE_BAR_ID)
            prefs.remove(KEY_ACTIVE_BAR_NAME)
            prefs.remove(KEY_USER_ID)
        }
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_ACTIVE_BAR_ID = intPreferencesKey("active_bar_id")
        val KEY_ACTIVE_BAR_NAME = stringPreferencesKey("active_bar_name")
        val KEY_USER_ID = intPreferencesKey("user_id")
        val KEY_PREFERRED_UNIT = stringPreferencesKey("preferred_unit")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

@ContributesTo(AppScope::class)
@BindingContainer
object DataBindings {
    @SingleIn(AppScope::class)
    @Provides
    fun providePreferencesDataStore(platformPaths: PlatformPaths): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { platformPaths.dataStorePath("bar_friend.preferences_pb").toPath() },
        )
}
