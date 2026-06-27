package com.persianai.assistant.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.persianai.assistant.R
import com.persianai.assistant.databinding.ActivityMainTabbedBinding
import com.persianai.assistant.fragments.DashboardFragment
import com.persianai.assistant.fragments.ChatFragment
import com.persianai.assistant.fragments.AccountingFragment
import com.persianai.assistant.fragments.RemindersFragment
import com.persianai.assistant.fragments.SettingsFragment

/**
 * MainActivity با ناوبری تب‌مانند حرفه‌ای
 */
class MainActivityTabbed : AppCompatActivity() {

    private lateinit var binding: ActivityMainTabbedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainTabbedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupViewPager()
        setupTabs()
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
    }

    private fun setupTabs() {
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "داشبورد"
                1 -> "دستیار"
                2 -> "حسابداری"
                3 -> "یادآوری"
                4 -> "تنظیمات"
                else -> ""
            }
            tab.setIcon(when (position) {
                0 -> R.drawable.ic_report  // Dashboard
                1 -> R.drawable.ic_mic  // Chat
                2 -> R.drawable.ic_income  // Accounting
                3 -> R.drawable.ic_notification  // Reminders
                4 -> R.drawable.ic_settings  // Settings
                else -> 0
            })
        }.attach()
    }

    private inner class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> DashboardFragment()
                1 -> ChatFragment()
                2 -> AccountingFragment()
                3 -> RemindersFragment()
                4 -> SettingsFragment()
                else -> DashboardFragment()
            }
        }
    }
}
