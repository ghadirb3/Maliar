package com.persianai.assistant.utils

data class WeatherData(val temp: String, val desc: String, val aqi: String)

object WeatherAPI {
    private val TOKEN = com.persianai.assistant.BuildConfig.AQICN_API_TOKEN
    
    fun getTemperature(): String = "25°C"
    fun getDescription(): String = "آفتابی"
    fun getAQI(): Int {
        return (50..200).random()
    }
    
    fun getMinTemp(): Int {
        return (15..20).random()
    }
    
    fun getMaxTemp(): Int {
        return (25..30).random()
    }
}
