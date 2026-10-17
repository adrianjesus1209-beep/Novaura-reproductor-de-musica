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
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDownloadBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.showToast

@AndroidEntryPoint
class DownloadFragment : ViewBindingFragment<FragmentDownloadBinding>() {

    private val downloadModel: DownloadViewModel by viewModels()
    private lateinit var downloadAdapter: DownloadAdapter

    override fun onCreateBinding(inflater: LayoutInflater): FragmentDownloadBinding =
        FragmentDownloadBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentDownloadBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.downloadToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        downloadAdapter = DownloadAdapter { song ->
            downloadModel.download(song)
        }

        binding.downloadRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = downloadAdapter
        }

        binding.downloadSearchInput.setOnEditorActionListener { textView, actionId, _ ->
            if (
                actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            ) {
                val query = textView.text?.toString().orEmpty()
                downloadModel.search(query)
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.isSearching.collectLatest { searching ->
                binding.downloadSearchLoading.isVisible = searching
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            downloadModel.searchResults.collectLatest { results ->
                downloadAdapter.submitList(results)
                val query = binding.downloadSearchInput.text?.toString().orEmpty()
                if (results.isEmpty() && query.isNotBlank() && !downloadModel.isSearching.value) {
                    binding.downloadEmptyState.isVisible = true
                    binding.downloadEmptyText.setText(R.string.lbl_no_download_results)
                } else if (results.isEmpty()) {
                    binding.downloadEmptyState.isVisible = true
                    binding.downloadEmptyText.setText(R.string.lbl_download_empty_prompt)
                } else {
                    binding.downloadEmptyState.isVisible = false
                }
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
}
