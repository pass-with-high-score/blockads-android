package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import org.junit.rules.TemporaryFolder
import java.io.File

fun TemporaryFolder.newPreferencesDataStore(): DataStore<Preferences> {
    val file = File(newFolder(), "test.preferences_pb")
    return PreferenceDataStoreFactory.create { file }
}
