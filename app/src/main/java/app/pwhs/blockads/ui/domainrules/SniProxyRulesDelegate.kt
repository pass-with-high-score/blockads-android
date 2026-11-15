package app.pwhs.blockads.ui.domainrules

import android.app.Application
import android.content.pm.ApplicationInfo
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.model.SniProxyRule
import app.pwhs.blockads.data.model.SniProxyRuleCodec
import app.pwhs.blockads.service.AdBlockVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InstalledAppOption(
    val packageName: String,
    val label: String
)

class SniProxyRulesDelegate(
    private val appPrefs: AppPreferences,
    private val application: Application,
    private val scope: CoroutineScope
) {
    val rules: StateFlow<List<SniProxyRule>> = appPrefs.sniProxyRulesJson
        .map { SniProxyRuleCodec.fromJsonList(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<InstalledAppOption>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppOption>> = _installedApps.asStateFlow()

    fun loadInstalledAppsIfNeeded() {
        if (_installedApps.value.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val pm = application.packageManager
            val apps = pm.getInstalledApplications(0)
                .asSequence()
                .filter { appInfo ->
                    appInfo.packageName != application.packageName &&
                        ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            pm.getLaunchIntentForPackage(appInfo.packageName) != null)
                }
                .map { appInfo ->
                    InstalledAppOption(
                        packageName = appInfo.packageName,
                        label = pm.getApplicationLabel(appInfo).toString()
                    )
                }
                .sortedWith(compareBy({ it.packageName != SniProxyRuleCodec.YOUTUBE_PACKAGE }, { it.label.lowercase() }))
                .toList()
            _installedApps.value = apps
        }
    }

    suspend fun saveRule(rule: SniProxyRule) {
        val current = SniProxyRuleCodec.fromJsonList(appPrefs.getSniProxyRulesJsonSnapshot())
        val idx = current.indexOfFirst { it.id == rule.id }
        val updated = if (idx >= 0) {
            current.toMutableList().apply { set(idx, rule) }
        } else {
            current + rule
        }
        persistAndApply(updated)
    }

    suspend fun toggleRule(ruleId: String) {
        val current = SniProxyRuleCodec.fromJsonList(appPrefs.getSniProxyRulesJsonSnapshot())
        val updated = current.map { if (it.id == ruleId) it.copy(enabled = !it.enabled) else it }
        persistAndApply(updated)
    }

    suspend fun deleteRule(ruleId: String) {
        val current = SniProxyRuleCodec.fromJsonList(appPrefs.getSniProxyRulesJsonSnapshot())
        val updated = current.filterNot { it.id == ruleId }
        persistAndApply(updated)
    }

    private suspend fun persistAndApply(updated: List<SniProxyRule>) {
        appPrefs.setSniProxyRulesJson(SniProxyRuleCodec.toJsonList(updated))
        AdBlockVpnService.activeTunnelAdapter?.setSniProxyRules(
            SniProxyRuleCodec.toEngineJson(updated, application.packageManager)
        )
    }
}
