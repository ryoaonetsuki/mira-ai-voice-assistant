package io.github.ryoaonetsuki.mira.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class WeatherData(
    val temperature: String,
    val humidity: String,
    val condition: String
)

class WeatherClient {

    companion object {
        const val DHAKA_LAT = 23.8103
        const val DHAKA_LON = 90.4125
        const val NYC_LAT = 40.7128
        const val NYC_LON = -74.0060

        fun defaultCoordinates(timeZoneId: String = TimeZone.getDefault().id): Pair<Double, Double> {
            return if (timeZoneId == "Asia/Dhaka" || timeZoneId == "Asia/Dacca") {
                Pair(DHAKA_LAT, DHAKA_LON)
            } else {
                Pair(NYC_LAT, NYC_LON)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getCurrentWeather(
        latitude: Double = defaultCoordinates().first,
        longitude: Double = defaultCoordinates().second
    ): WeatherData = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,relative_humidity_2m,weather_code"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val current = json.getJSONObject("current")
                    val temp = current.optDouble("temperature_2m", 22.0)
                    val humidity = current.optInt("relative_humidity_2m", 60)
                    val code = current.optInt("weather_code", 0)

                    val condition = when (code) {
                        0 -> "Clear"
                        1, 2, 3 -> "Partly Cloudy"
                        45, 48 -> "Foggy"
                        51, 53, 55 -> "Drizzle"
                        61, 63, 65 -> "Rainy"
                        71, 73, 75 -> "Snowy"
                        95, 96, 99 -> "Thunderstorm"
                        else -> "Fair"
                    }
                    WeatherData("${temp.toInt()}°C", "$humidity%", condition)
                } else {
                    WeatherData("24°C", "65%", "Clear")
                }
            }
        } catch (e: Exception) {
            WeatherData("22°C", "58%", "Clear")
        }
    }
}
