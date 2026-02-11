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
package app.pwhs.blockads.ui.filter

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.FilterList
import app.pwhs.blockads.data.FilterListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilterSetupViewModel(
    private val filterRepo: FilterListRepository,
    private val context: Context,
) : ViewModel() {

    private val filterListDao = AppDatabase.getInstance(context).filterListDao()

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUpdatingFilter = MutableStateFlow(false)
    val isUpdatingFilter: StateFlow<Boolean> = _isUpdatingFilter.asStateFlow()

    init {
        viewModelScope.launch {
            filterRepo.seedDefaultsIfNeeded()
        }
    }

    fun toggleFilterList(filter: FilterList) {
        viewModelScope.launch {
            filterListDao.setEnabled(filter.id, !filter.isEnabled)
        }
    }

    fun addFilterList(name: String, url: String) {
        viewModelScope.launch {
            filterListDao.insert(
                FilterList(name = name, url = url, isEnabled = true, isBuiltIn = false)
            )
            Toast.makeText(
                context,
                context.getString(R.string.settings_add) + ": $name",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun deleteFilterList(filter: FilterList) {
        if (filter.isBuiltIn) return
        viewModelScope.launch {
            filterListDao.delete(filter)
        }
    }

    fun updateAllFilters() {
        viewModelScope.launch {
            _isUpdatingFilter.value = true
            val result = filterRepo.loadAllEnabledFilters()
            _isUpdatingFilter.value = false

            result.fold(
                onSuccess = { count ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.filter_updated, count),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.filter_update_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}
