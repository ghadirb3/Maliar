package com.persianai.assistant.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.persianai.assistant.R
import com.persianai.assistant.activities.DashboardActivity
import com.google.android.material.button.MaterialButton

/**
 * Fragment داشبورد برای تب اصلی
 */
class DashboardFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<MaterialButton>(R.id.openDashboardBtn).setOnClickListener {
            startActivity(Intent(requireContext(), DashboardActivity::class.java))
        }
    }
}
