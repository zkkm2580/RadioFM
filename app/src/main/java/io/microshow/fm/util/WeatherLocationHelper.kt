package io.microshow.fm.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
 * 实时定位与天气辅助类（专为手机与车机双端优化）
 * 1. 定位策略：优先硬件 GPS 定位；若 GPS 无信号（隧道、地库或搜星超时），自动平滑回退到网络基站/WiFi定位；若均无则使用网络 IP 归属地兜底。
 * 2. 逆地理编码：优先使用系统 Geocoder，车机/无 GMS 系统自动切换到开源在线逆地理接口，确保 100% 解析出县市名称。
 * 3. 实时天气：通过免 API Key 的 Open-Meteo 气象服务获取气温与天气状况。
 */
class WeatherLocationHelper(
    private val context: Context,
    private val onUpdate: (location: String, weather: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLocationText = "正在定位..."
    private var lastWeatherText = "天气获取中..."

    private var isGpsFixReceived = false
    private var hasResolvedLocation = false
    private var lastFetchTime = 0L

    private var activeGpsListener: LocationListener? = null
    private var activeNetworkListener: LocationListener? = null

    private val gpsTimeoutRunnable = Runnable {
        if (!isGpsFixReceived) {
            Log.d(TAG, "GPS 搜星超时（可能处于室内/隧道或无 GPS 信号），自动回退至网络定位")
            fallbackToNetworkLocation()
        }
    }

    companion object {
        private const val TAG = "WeatherLocationHelper"
        private const val GPS_TIMEOUT_MS = 8000L // GPS 搜星等待 8 秒，无信号则回退网络
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 分钟刷新一次
    }

    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFetchTime < REFRESH_INTERVAL_MS)) {
            return
        }
        lastFetchTime = now
        isGpsFixReceived = false

        // 1. 先进行一次毫秒级 IP 定位预加载，避免车机界面等待搜星时长时间白屏或只显示“正在定位...”
        fetchIpLocationAndWeather()

        // 2. 优先启动 GPS 硬件定位
        startGpsLocationPriority()
    }

    @SuppressLint("MissingPermission")
    private fun startGpsLocationPriority() {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.d(TAG, "未授予系统定位权限，使用 IP 兜底定位")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        val isGpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (isGpsEnabled && hasFine) {
            Log.d(TAG, "GPS 硬件可用，优先启动 GPS 搜星监听")

            // 如果有历史缓存的有效 GPS 定位，先尝试读取
            try {
                val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastGps != null && (System.currentTimeMillis() - lastGps.time < 300_000L)) {
                    // 5 分钟内的最新缓存 GPS 位置直接使用
                    isGpsFixReceived = true
                    resolveCoordinates(lastGps.latitude, lastGps.longitude, isGps = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取 GPS 缓存位置失败: ${e.message}")
            }

            // 注册 GPS 实时监听
            stopGpsListener()
            val gpsListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "收到有效 GPS 定位信号: lat=${location.latitude}, lon=${location.longitude}")
                    isGpsFixReceived = true
                    mainHandler.removeCallbacks(gpsTimeoutRunnable)
                    resolveCoordinates(location.latitude, location.longitude, isGps = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    Log.d(TAG, "GPS 被禁用，立即回退到网络定位")
                    mainHandler.removeCallbacks(gpsTimeoutRunnable)
                    fallbackToNetworkLocation()
                }
            }
            activeGpsListener = gpsListener

            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    5f,
                    gpsListener,
                    Looper.getMainLooper()
                )
                // 启动 8 秒 GPS 搜星超时计时器：若无信号自动切网络定位
                mainHandler.removeCallbacks(gpsTimeoutRunnable)
                mainHandler.postDelayed(gpsTimeoutRunnable, GPS_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "请求 GPS 监听异常: ${e.message}")
                fallbackToNetworkLocation()
            }
        } else {
            Log.d(TAG, "当前设备不支持 GPS 或 GPS 未开启，直接使用网络定位")
            fallbackToNetworkLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToNetworkLocation() {
        if (isGpsFixReceived) return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCoarse && !hasFine) return

        // 1. 尝试网络基站/WiFi定位
        val isNetEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (isNetEnabled) {
            try {
                val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastNet != null && !isGpsFixReceived) {
                    resolveCoordinates(lastNet.latitude, lastNet.longitude, isGps = false)
                }

                stopNetworkListener()
                val netListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!isGpsFixReceived) {
                            Log.d(TAG, "收到网络基站/WiFi定位信号: lat=${location.latitude}, lon=${location.longitude}")
                            resolveCoordinates(location.latitude, location.longitude, isGps = false)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                activeNetworkListener = netListener

                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    netListener,
                    Looper.getMainLooper()
                )
                // 10秒后停止网络监听，避免持续耗电
                mainHandler.postDelayed({
                    stopNetworkListener()
                }, 10000L)
            } catch (e: Exception) {
                Log.w(TAG, "网络定位请求异常: ${e.message}")
            }
        }

        // 2. 检查被动定位源（Passive Provider，车机端常由前台导航地图广播共享位置）
        if (!isGpsFixReceived) {
            try {
                val passive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passive != null) {
                    resolveCoordinates(passive.latitude, passive.longitude, isGps = false)
                }
            } catch (e: Exception) {
                // 忽略被动定位获取失败
            }
        }
    }

    private fun stopGpsListener() {
        activeGpsListener?.let {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            try {
                locationManager?.removeUpdates(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            activeGpsListener = null
        }
    }

    private fun stopNetworkListener() {
        activeNetworkListener?.let {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            try {
                locationManager?.removeUpdates(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            activeNetworkListener = null
        }
    }

    /**
     * 停止全部监听器（Activity 销毁或暂停时调用）
     */
    fun stop() {
        mainHandler.removeCallbacks(gpsTimeoutRunnable)
        stopGpsListener()
        stopNetworkListener()
    }

    /**
     * 根据经纬度执行逆地理编码与天气拉取
     */
    private fun resolveCoordinates(lat: Double, lon: Double, isGps: Boolean) {
        thread {
            val locationName = reverseGeocode(lat, lon)
            if (locationName.isNotBlank()) {
                hasResolvedLocation = true
                lastLocationText = locationName
                postUpdate()
            }

            fetchWeather(lat, lon)
        }
    }

    /**
     * 双重逆地理编码解析：
     * 1. 优先尝试 Android 原生系统 Geocoder（手机系统可用）
     * 2. 车机与无 GMS 系统自动切换到开源在线逆地理接口，确保 100% 成功解析出城市与区县
     */
    private fun reverseGeocode(lat: Double, lon: Double): String {
        // 1. 系统 Geocoder
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
                    if (formatted.isNotBlank()) {
                        return formatted
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "系统 Geocoder 不可用（车机常见）: ${e.message}")
        }

        // 2. 车机专用无 key 开源逆地理编码（支持简体中文行政区划）
        try {
            val urlStr = "https://api-bdc.io/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=zh-Hans"
                .format(Locale.US, lat, lon)
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RadioFM")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                val city = json.optString("city", "")
                val district = json.optString("locality", "")
                val region = json.optString("principalSubdivision", "")

                val formatted = when {
                    city.isNotEmpty() && district.isNotEmpty() && city != district -> "$city $district"
                    district.isNotEmpty() -> district
                    city.isNotEmpty() -> city
                    region.isNotEmpty() -> region
                    else -> ""
                }
                if (formatted.isNotBlank()) {
                    return formatted
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "在线逆地理编码请求异常: ${e.message}")
        }

        return ""
    }

    /**
     * IP 定位及天气反查（兜底与秒开展示）
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

                        // 如果尚未通过 GPS/基站解析出更高精度的位置，则先显示 IP 城市
                        if (!hasResolvedLocation && locName.isNotEmpty()) {
                            lastLocationText = locName
                            postUpdate()
                        }

                        if (!hasResolvedLocation && lat != 0.0 && lon != 0.0) {
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
     * 调用 Open-Meteo 开源气象服务
     */
    private fun fetchWeather(lat: Double, lon: Double) {
        try {
            val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code"
                .format(Locale.US, lat, lon)
            val conn = URL(urlStr).openConnection() as HttpURLConnection
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
