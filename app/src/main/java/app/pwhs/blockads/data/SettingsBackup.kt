/*
 * BlockAds - Ad blocker for Android using local VPN-based DNS filtering
 * Copyright (C) 2025 The BlockAds Contributors
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
package app.pwhs.blockads.data

import kotlinx.serialization.Serializable

@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val upstreamDns: String = AppPreferences.DEFAULT_UPSTREAM_DNS,
    val fallbackDns: String = AppPreferences.DEFAULT_FALLBACK_DNS,
    val autoReconnect: Boolean = true,
    val themeMode: String = AppPreferences.THEME_SYSTEM,
    val appLanguage: String = AppPreferences.LANGUAGE_SYSTEM,
    val filterLists: List<FilterListBackup> = emptyList(),
    val whitelistDomains: List<String> = emptyList(),
    val whitelistedApps: List<String> = emptyList()
)

@Serializable
data class FilterListBackup(
    val name: String,
    val url: String,
    val isEnabled: Boolean = true
)
