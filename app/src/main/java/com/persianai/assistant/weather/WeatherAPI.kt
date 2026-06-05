package com.persianai.assistant.weather

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * هواشناسی ساده با OpenWeatherMap
 */
class WeatherAPI {
    
    companion object {
        private const val TAG = "WeatherAPI"
        private val API_KEY = com.persianai.assistant.BuildConfig.OPENWEATHER_API_KEY
        private const val BASE_URL = "https://api.openweathermap.org/data/2.5/weather"
    }
    
    data class WeatherData(
        val temperature: Double,
        val description: String,
        val humidity: Int,
        val windSpeed: Double,
        val cityName: String,
        val icon: String
    )
    
    fun getCurrentWeather(lat: Double, lon: Double, callback: (WeatherData?) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL?lat=$lat&lon=$lon&appid=$API_KEY&units=metric&lang=fa"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    
                    val main = json.getJSONObject("main")
                    val weather = json.getJSONArray("weather").getJSONObject(0)
                    val wind = json.getJSONObject("wind")
                    
                    val data = WeatherData(
                        temperature = main.getDouble("temp"),
                        description = weather.getString("description"),
                        humidity = main.getInt("humidity"),
                        windSpeed = wind.getDouble("speed"),
                        cityName = json.getString("name"),
                        icon = weather.getString("icon")
                    )
                    
                    Log.d(TAG, "✅ Weather: ${data.temperature}°C, ${data.description}")
                    callback(data)
                } else {
                    Log.e(TAG, "❌ Error: ${connection.responseCode}")
                    callback(null)
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception", e)
                callback(null)
            }
        }.start()
    }
    
    fun getWeatherForCity(cityName: String, callback: (WeatherData?) -> Unit) {
        Thread {
            try {
                val encodedCity = URLEncoder.encode(cityName, "UTF-8")
                val url = "$BASE_URL?q=$encodedCity&appid=$API_KEY&units=metric&lang=fa"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    
                    val main = json.getJSONObject("main")
                    val weather = json.getJSONArray("weather").getJSONObject(0)
                    val wind = json.getJSONObject("wind")
                    
                    val data = WeatherData(
                        temperature = main.getDouble("temp"),
                        description = weather.getString("description"),
                        humidity = main.getInt("humidity"),
                        windSpeed = wind.getDouble("speed"),
                        cityName = json.getString("name"),
                        icon = weather.getString("icon")
                    )
                    
                    callback(data)
                } else {
                    callback(null)
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception", e)
                callback(null)
            }
        }.start()
    }
}
