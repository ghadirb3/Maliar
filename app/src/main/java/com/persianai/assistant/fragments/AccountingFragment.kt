package com.persianai.assistant.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.persianai.assistant.R
import com.persianai.assistant.activities.AccountingAdvancedActivity
import com.google.android.material.button.MaterialButton

/**
 * Fragment حسابداری برای تب مالی
 */
class AccountingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_accounting, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Open AccountingAdvancedActivity directly without button click
        startActivity(Intent(requireContext(), AccountingAdvancedActivity::class.java))
    }
}
