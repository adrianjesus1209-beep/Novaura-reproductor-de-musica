/*
 * Copyright (c) 2026 Auxio Project
 * DownloadFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.download

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDownloadBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getSystemServiceCompat
import org.oxycblt.auxio.util.showToast

@AndroidEntryPoint
class DownloadFragment : ViewBindingFragment<FragmentDownloadBinding>() {

    private val downloadModel: DownloadViewModel by viewModels()
    private lateinit var downloadAdapter: DownloadAdapter
    private var imm: InputMethodManager? = null
    private var debounceJob: Job? = null
    private var launchedKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentDownloadBinding =
        FragmentDownloadBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentDownloadBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        imm = binding.context.getSystemServiceCompat(InputMethodManager::class)

        // --- NAVIGATION ---
        binding.downloadToolbar.setNavigationOnClickListener {
            hideKeyboard()
            findNavController().navigateUp()
        }

        // --- ADAPTER ---
        downloadAdapter = DownloadAdapter { song ->
            downloadModel.download(song)
        }

        binding.downloadRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = downloadAdapter
        }

        // --- SEARCH INPUT & ACTIONS ---
        binding.downloadSearchInput.apply {
            addTextChangedListener { editable ->
                val query = editable?.toString()?.trim().orEmpty()
                binding.downloadSearchClear.isVisible = query.isNotEmpty()

                debounceJob?.cancel()
                if (query.isBlank()) {
                    downloadModel.search("")
                    updateUiState(binding, isSearching = false, resultsCount = 0, query = "")
                } else {
                    debounceJob =
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(500)
                            downloadModel.search(query)
                        }
                }
            }

            setOnEditorActionListener { _, actionId, event ->
                val isSearchAction =
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_ACTION_GO ||
                        (event != null &&
                            event.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN)

                if (isSearchAction) {
                    performSearch(binding)
                    true
                } else {
                    false
                }
            }

            if (!launchedKeyboard) {
                showKeyboard(this)
                launchedKeyboard = true
            }
        }

        binding.downloadSearchClear.setOnClickListener {
            binding.downloadSearchInput.text?.clear()
            debounceJob?.cancel()
            downloadModel.search("")
            showKeyboard(binding.downloadSearchInput)
        }

        binding.downloadSearchBtn.setOnClickListener {
            performSearch(binding)
        }

        // --- SUGGESTION CHIPS ---
        setupSuggestionChips(binding)

        // --- STATE OBSERVATIONS ---
        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.isSearching.collectLatest { searching ->
                binding.downloadSearchLoading.isVisible = searching
                val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
                val count = downloadModel.searchResults.value.size
                updateUiState(binding, isSearching = searching, resultsCount = count, query = query)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.searchResults.collectLatest { results ->
                downloadAdapter.submitList(results)
                val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
                val searching = downloadModel.isSearching.value
                updateUiState(
                    binding,
                    isSearching = searching,
                    resultsCount = results.size,
                    query = query,
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.downloadProgress.collectLatest { progressMap ->
                downloadAdapter.updateProgress(progressMap)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.events.collectLatest { event ->
                when (event) {
                    is DownloadEvent.Started -> {
                        requireContext()
                            .showToast(getString(R.string.lbl_download_started, event.title))
                    }
                    is DownloadEvent.Success -> {
                        requireContext()
                            .showToast(getString(R.string.msg_download_success, event.title))
                    }
                    is DownloadEvent.Error -> {
                        requireContext()
                            .showToast(getString(R.string.msg_download_error, event.title))
                    }
                }
            }
        }
    }

    private fun performSearch(binding: FragmentDownloadBinding) {
        val query = binding.downloadSearchInput.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty()) {
            debounceJob?.cancel()
            hideKeyboard()
            downloadModel.search(query)
        }
    }

    private fun setupSuggestionChips(binding: FragmentDownloadBinding) {
        for (i in 0 until binding.downloadSuggestionsGroup.childCount) {
            val child = binding.downloadSuggestionsGroup.getChildAt(i)
            if (child is Chip) {
                child.setOnClickListener {
                    val query = child.text.toString()
                    binding.downloadSearchInput.setText(query)
                    binding.downloadSearchInput.setSelection(query.length)
                    debounceJob?.cancel()
                    hideKeyboard()
                    downloadModel.search(query)
                }
            }
        }
    }

    private fun updateUiState(
        binding: FragmentDownloadBinding,
        isSearching: Boolean,
        resultsCount: Int,
        query: String,
    ) {
        when {
            // Actively searching with no current results: show spinner
            isSearching && resultsCount == 0 -> {
                binding.downloadRecycler.isVisible = false
                binding.downloadSearchingState.isVisible = true
                binding.downloadNoResultsState.isVisible = false
                binding.downloadEmptyScroll.isVisible = false
            }
            // Results available: show list
            resultsCount > 0 -> {
                binding.downloadRecycler.isVisible = true
                binding.downloadSearchingState.isVisible = false
                binding.downloadNoResultsState.isVisible = false
                binding.downloadEmptyScroll.isVisible = false
            }
            // Finished searching, query entered, but 0 results: show no results view
            !isSearching && query.isNotEmpty() && resultsCount == 0 -> {
                binding.downloadRecycler.isVisible = false
                binding.downloadSearchingState.isVisible = false
                binding.downloadNoResultsState.isVisible = true
                binding.downloadEmptyScroll.isVisible = false
            }
            // Idle / empty search input: show suggestions and initial empty prompt
            else -> {
                binding.downloadRecycler.isVisible = false
                binding.downloadSearchingState.isVisible = false
                binding.downloadNoResultsState.isVisible = false
                binding.downloadEmptyScroll.isVisible = true
            }
        }
    }

    private fun showKeyboard(view: View) {
        view.apply {
            requestFocus()
            postDelayed(200) {
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideKeyboard() {
        val token = view?.windowToken ?: activity?.currentFocus?.windowToken
        if (token != null) {
            imm?.hideSoftInputFromWindow(token, InputMethodManager.HIDE_NOT_ALWAYS)
        }
    }
}
