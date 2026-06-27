package com.persianai.assistant.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.persianai.assistant.R
import com.persianai.assistant.activities.AdvancedRemindersActivity
import com.google.android.material.button.MaterialButton

/**
 * Fragment یادآوری برای تب یادآوری‌ها
 */
class RemindersFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_reminders, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Open AdvancedRemindersActivity directly without button click
        startActivity(Intent(requireContext(), AdvancedRemindersActivity::class.java))
    }
}
