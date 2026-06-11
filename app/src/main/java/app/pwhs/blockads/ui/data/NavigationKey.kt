package app.pwhs.blockads.ui.data

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object SplashKey : NavKey

@Serializable
data object OnboardingKey : NavKey

@Serializable
data object HomeAppKey : NavKey

@Serializable
data object HomeKey : NavKey

@Serializable
data object FilterKey : NavKey

@Serializable
data object FireWallKey : NavKey

@Serializable
data object DnsProviderKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object StatisticsKey : NavKey

@Serializable
data object LogsKey : NavKey

@Serializable
data object ProfileKey : NavKey

@Serializable
data class FilterDetailKey(val filterId: Long) : NavKey

@Serializable
data object AboutKey : NavKey

@Serializable
data object WhiteListAppKey : NavKey

@Serializable
data object AppManagementKey : NavKey

@Serializable
data object AppearanceKey : NavKey

@Serializable
data object DomainRulesKey : NavKey


@Serializable
data object CustomRuleKey : NavKey

@Serializable
data object WireGuardImportKey : NavKey

@Serializable
data class WireGuardEditKey(val profileId: String) : NavKey

@Serializable
data object HttpsFilteringKey : NavKey