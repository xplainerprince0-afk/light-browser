package com.lightbrowser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lightbrowser.databinding.FragmentPlaceholderBinding

class PlaceholderFragment : Fragment() {
    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(title: String, desc: String, icon: String) = PlaceholderFragment().apply {
            arguments = Bundle().apply {
                putString("t", title)
                putString("d", desc)
                putString("i", icon)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.text = arguments?.getString("t") ?: "Placeholder"
        binding.desc.text = arguments?.getString("d") ?: ""
        binding.icon.text = arguments?.getString("i") ?: "⭐"
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
