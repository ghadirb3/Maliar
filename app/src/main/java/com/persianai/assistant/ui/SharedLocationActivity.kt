package com.persianai.assistant.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * DISABLED: Navigation feature removed
 * This activity has been stubbed to remove navigation dependencies
 */
class SharedLocationActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "ویژگی مسیریابی موقتاً غیرفعال است", Toast.LENGTH_SHORT).show()
        finish()
    }
}
