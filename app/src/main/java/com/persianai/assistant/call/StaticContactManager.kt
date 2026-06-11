package com.persianai.assistant.call

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.models.Contact
import com.persianai.assistant.utils.PreferencesManager

/**
 * StaticContactManager: load a small user-provided contact list from prefs or assets
 * Format: JSON array of { "name": "...", "phone": "..." }
 */
class StaticContactManager(private val context: Context) {
    private val TAG = "StaticContactManager"
    private val prefs = PreferencesManager(context)
    private val gson = Gson()

    private data class RawContact(val name: String?, val phone: String?)

    fun loadStaticContacts(): List<Contact> {
        try {
            // 1) Try prefs JSON
            val prefsJson = prefs.getStaticContactsJson()
            val json = prefsJson ?: run {
                // 2) Try assets/static_contacts.json
                try {
                    context.assets.open("static_contacts.json").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            } ?: return emptyList()

            val type = object : TypeToken<List<RawContact>>() {}.type
            val rawList: List<RawContact> = try {
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse static contacts JSON", e)
                emptyList()
            }

            val contacts = rawList.mapIndexedNotNull { idx, rc ->
                val name = rc.name?.trim()
                val phone = rc.phone?.trim()
                if (name.isNullOrBlank() || phone.isNullOrBlank()) return@mapIndexedNotNull null
                Contact(id = "static_$idx", name = name, phoneNumber = phone)
            }

            Log.d(TAG, "Loaded ${contacts.size} static contacts")
            return contacts
        } catch (e: Exception) {
            Log.e(TAG, "Error loading static contacts", e)
            return emptyList()
        }
    }

    fun searchStaticContacts(query: String, maxResults: Int = 5): List<Contact> {
        val all = loadStaticContacts()
        if (all.isEmpty()) return emptyList()

        val q = query.lowercase().trim()
        val scored = all.map { contact ->
            val score = contact.calculateMatchScore(q)
            contact.copy(score = score)
        }.filter { it.score > 0f }

        return scored.sortedByDescending { it.score }.take(maxResults)
    }
}
