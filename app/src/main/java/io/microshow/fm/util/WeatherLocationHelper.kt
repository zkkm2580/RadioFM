package io.microshow.fm.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 实时定位与天气辅助工具
 * - 县市定位：优先通过 GPS / 基站定位 + Geocoder 逆地理编码；室内或无卫星时自动由 IP 网络定位兜底
 * - 实时天气：通过免 API Key 的 Open-Meteo 气象服务获取实时天气代码与气温
 */
class WeatherLocationHelper(
    private val context: Context,
    private val onUpdate: (location: String, weather: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLocationText = "正在定位..."
    private var lastWeatherText = "天气获取中..."

    private var hasAccurateLocation = false
    private var lastFetchTime = 0L

    companion object {
        private const val TAG = "WeatherLocationHelper"
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 分钟最小刷新间隔
    }

    /**
     * 触发刷新
     */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFetchTime < REFRESH_INTERVAL_MS)) {
            return
        }
        lastFetchTime = now

        // 1. 先异步进行 IP 定位兜底，确保秒级显示基础城市与天气
        fetchIpLocationAndWeather()

        // 2. 尝试系统原生定位（精确到区/县）
        trySystemLocation()
    }

    @SuppressLint("MissingPermission")
    private fun trySystemLocation() {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.d(TAG, "未授予系统定位权限，继续使用 IP 兜底定位")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        try {
            // 优先检查最后一次已知缓存位置
            val gpsLoc = if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            val netLoc = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            val bestLoc = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }

            if (bestLoc != null) {
                resolveCoordinates(bestLoc.latitude, bestLoc.longitude, isAccurate = true)
            }

            // 注册单次位置监听获取最新精准经纬度
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try {
                        locationManager.removeUpdates(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    resolveCoordinates(location.latitude, location.longitude, isAccurate = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            val provider = when {
                hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider != null) {
                locationManager.requestLocationUpdates(provider, 1000L, 10f, listener, Looper.getMainLooper())
                // 15 秒后自动注销监听，避免长时间处于搜星耗电状态
                mainHandler.postDelayed({
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, 15000L)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "系统定位安全异常: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "系统定位失败: ${e.message}")
        }
    }

    /**
     * IP 定位兜底（支持中文行政区划）
     */
    private fun fetchIpLocationAndWeather() {
        thread {
            try {
                val url = URL("http://ip-api.com/json/?lang=zh-CN")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RadioFM")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        val region = json.optString("regionName")
                        val city = json.optString("city")
                        val lat = json.optDouble("lat", 0.0)
                        val lon = json.optDouble("lon", 0.0)

                        val locName = when {
                            city.isNotEmpty() -> city
                            region.isNotEmpty() -> region
                            else -> ""
                        }

                        if (!hasAccurateLocation && locName.isNotEmpty()) {
                            lastLocationText = locName
                            postUpdate()
                        }

                        if (!hasAccurateLocation && lat != 0.0 && lon != 0.0) {
                            fetchWeather(lat, lon)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "IP 定位请求异常: ${e.message}")
            }
        }
    }

    /**
     * 根据精准经纬度解析区县名称与对应天气
     */
    private fun resolveCoordinates(lat: Double, lon: Double, isAccurate: Boolean) {
        if (isAccurate) {
            hasAccurateLocation = true
        }

        thread {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.CHINA)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                        val district = addr.subLocality ?: ""

                        val formatted = when {
                            city.isNotEmpty() && district.isNotEmpty() && city != district -> "$city $district"
                            district.isNotEmpty() -> district
                            city.isNotEmpty() -> city
                            else -> addr.adminArea ?: ""
                        }

                        if (formatted.isNotEmpty()) {
                            lastLocationText = formatted
                            postUpdate()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder 解析区县失败: ${e.message}")
            }

            fetchWeather(lat, lon)
        }
    }

    /**
     * 调用 Open-Meteo 开源气象 API 获取实时天气与气温
     */
    private fun fetchWeather(lat: Double, lon: Double) {
        try {
            val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code"
                .format(Locale.US, lat, lon)
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RadioFM")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                val current = json.optJSONObject("current")
                if (current != null) {
                    val temp = current.optDouble("temperature_2m", Double.NaN)
                    val code = current.optInt("weather_code", 0)
                    val desc = parseWmoWeatherCode(code)

                    lastWeatherText = if (!temp.isNaN()) {
                        "$desc ${Math.round(temp)}°C"
                    } else {
                        desc
                    }
                    postUpdate()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "天气查询异常: ${e.message}")
        }
    }

    private fun postUpdate() {
        mainHandler.post {
            onUpdate(lastLocationText, lastWeatherText)
        }
    }

    private fun parseWmoWeatherCode(code: Int): String {
        return when (code) {
            0 -> "晴"
            1, 2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻雨"
            61 -> "小雨"
            63 -> "中雨"
            65 -> "大雨"
            66, 67 -> "强冻雨"
            71 -> "小雪"
            73 -> "中雪"
            75 -> "大雪"
            77 -> "雪粒"
            80, 81, 82 -> "阵雨"
            85, 86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷暴冰雹"
            else -> "多云"
        }
    }
}
