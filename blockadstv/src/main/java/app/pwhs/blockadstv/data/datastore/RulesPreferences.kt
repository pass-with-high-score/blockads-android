package app.pwhs.blockadstv.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rulesDataStore: DataStore<Preferences> by preferencesDataStore(name = "rules_preferences")

class RulesPreferences(private val context: Context) {

    companion object {
        private val KEY_RULE_MANIFEST_ENABLED = booleanPreferencesKey("rule_manifest_enabled")
        private val KEY_MODULES_ENABLED = booleanPreferencesKey("modules_enabled")
        private val KEY_RULESET_ENABLED = booleanPreferencesKey("ruleset_enabled")
        private val KEY_LAST_RULES_SYNC = longPreferencesKey("last_rules_sync")
    }

    val ruleManifestEnabled: Flow<Boolean> = context.rulesDataStore.data.map { prefs ->
        prefs[KEY_RULE_MANIFEST_ENABLED] ?: true
    }

    val modulesEnabled: Flow<Boolean> = context.rulesDataStore.data.map { prefs ->
        prefs[KEY_MODULES_ENABLED] ?: true
    }

    val rulesetEnabled: Flow<Boolean> = context.rulesDataStore.data.map { prefs ->
        prefs[KEY_RULESET_ENABLED] ?: true
    }

    val lastRulesSync: Flow<Long> = context.rulesDataStore.data.map { prefs ->
        prefs[KEY_LAST_RULES_SYNC] ?: 0L
    }

    suspend fun setRuleManifestEnabled(enabled: Boolean) {
        context.rulesDataStore.edit { it[KEY_RULE_MANIFEST_ENABLED] = enabled }
    }

    suspend fun setModulesEnabled(enabled: Boolean) {
        context.rulesDataStore.edit { it[KEY_MODULES_ENABLED] = enabled }
    }

    suspend fun setRulesetEnabled(enabled: Boolean) {
        context.rulesDataStore.edit { it[KEY_RULESET_ENABLED] = enabled }
    }

    suspend fun setLastRulesSync(timestamp: Long) {
        context.rulesDataStore.edit { it[KEY_LAST_RULES_SYNC] = timestamp }
    }
}
