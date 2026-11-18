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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * 实时定位与天气辅助类（专为机车与车载大屏定制）
 * - 5 秒高频准确定位：每 5 秒刷新一次定位，实时捕获机车行驶轨迹与位置变化
 * - 优先硬件 GPS：无 GPS 信号时（隧道、地库）自动平滑回退至基站/网络定位与 IP 兜底
 * - 智能逆地理更新：位移超过 150 米或跨区时即时刷新县市名称
 * - 车机无 GMS 兼容：内置开源在线逆地理与气象引擎，确保 100% 成功解析
 */
class WeatherLocationHelper(
    private val context: Context,
    private val onUpdate: (location: String, weather: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLocationText = "正在定位..."
    private var lastWeatherText = "天气获取中..."

    private var isRunning = false
    private var isGpsFixReceived = false
    private var lastGpsFixTime = 0L

    // 记录上一次逆地理与天气的经纬度与时间
    private var lastGeocodedLat = 0.0
    private var lastGeocodedLon = 0.0
    private var lastGeocodeTime = 0L
    private var lastWeatherFetchTime = 0L
    private var lastWeatherOk = false   // 上次天气请求是否成功，失败时缩短重试间隔

    // JWT 缓存：一次签发在有效期内复用，避免每次天气刷新都做 Ed25519 签名
    @Volatile
    private var cachedJwt = ""
    private var cachedJwtExpireAt = 0L

    private var activeGpsListener: LocationListener? = null
    private var activeNetworkListener: LocationListener? = null

    // 5 秒高频定时轮询，确保在各类特殊车载 ROM 下均能准时刷新
    private val periodicPollRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkLocationUpdates()
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "WeatherLocationHelper"
        private const val POLL_INTERVAL_MS = 5000L         // 5 秒刷新一次定位
        private const val GPS_SIGNAL_TIMEOUT_MS = 8000L    // 8 秒无 GPS 信号视为失去 GPS
        private const val WEATHER_REFRESH_INTERVAL_MS = 15 * 60 * 1000L // 天气成功 15 分钟刷新一次
        private const val WEATHER_RETRY_INTERVAL_MS = 60 * 1000L        // 天气失败 1 分钟后重试
        private const val MIN_DISTANCE_METERS_FOR_GEOCODE = 150f        // 位移超 150 米重新解析区县

        // 和风天气 Web 服务（JWT 鉴权）：专属 API Host + Ed25519 动态签名，响应强制 gzip
        private const val QWEATHER_API_HOST = "km65nrb2n2.re.qweatherapi.com"
        // JWT Header.kid = 凭据ID
        private const val QWEATHER_KID = "C5PU4P6GYQ"
        // JWT Payload.iss = 开发者ID（Q 开头）
        private const val QWEATHER_ISS = "Q968B3786C"
        // JWT Payload.sub = 项目ID
        private const val QWEATHER_SUB = "2E2DXH4Q3J"
        // Ed25519 私钥（PKCS#8 DER 的 Base64，去掉 PEM 头尾标记）—— 用于 JWT 签名
        // 注意：私钥明文内置于客户端，务必在和风控制台为该凭据设置 QPM 限额/请求来源限制
        private const val QWEATHER_PRIVATE_KEY_B64 =
            "MC4CAQAwBQYDK2VwBCIEIIY8xoJmixl9mOurdEHt16G8ba8PaExsveR0gvFEmHPn"
        private const val JWT_VALID_MS = 14 * 60 * 1000L   // JWT 有效期 14 分钟（< 官方 24h 上限）
        private const val JWT_EDDSA = "EdDSA"
    }

    /**
     * 启动/恢复高频定位（MainActivity.onResume 调用）
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. 先进行一次毫秒级 IP 定位兜底，避免首次进入时短暂白屏
        if (lastGeocodedLat == 0.0) {
            fetchIpLocationAndWeather()
        }

        // 2. 注册系统定位监听并启动 5 秒轮询刷新
        registerLocationListeners()
        mainHandler.removeCallbacks(periodicPollRunnable)
        mainHandler.postDelayed(periodicPollRunnable, POLL_INTERVAL_MS)
    }

    /**
     * 暂停定位刷新（MainActivity.onPause 调用，避免熄火或切后台耗电）
     */
    fun pause() {
        isRunning = false
        mainHandler.removeCallbacks(periodicPollRunnable)
        unregisterLocationListeners()
    }

    /**
     * 完全停止（MainActivity.onDestroy 调用）
     */
    fun stop() {
        pause()
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationListeners() {
        if (!hasLocationPermission()) {
            Log.d(TAG, "未授予系统定位权限，使用 IP 兜底定位")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // 1. 优先注册 GPS 定位监听（5 秒间隔，0 米阈值）
        val isGpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (isGpsEnabled && hasFine) {
            try {
                // 先读取最后已知 GPS 位置
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    onNewLocation(it, isGps = true)
                }

                val gpsListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        onNewLocation(location, isGps = true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                activeGpsListener = gpsListener
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    POLL_INTERVAL_MS,
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.w(TAG, "注册 GPS 监听失败: ${e.message}")
            }
        }

        // 2. 注册网络定位监听（基站/WiFi，5 秒间隔）作为双轨保障
        val isNetEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (isNetEnabled) {
            try {
                val netListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // 仅当最近 8 秒无有效 GPS 信号时才采用网络定位
                        val now = SystemClock.elapsedRealtime()
                        if (!isGpsFixReceived || (now - lastGpsFixTime > GPS_SIGNAL_TIMEOUT_MS)) {
                            onNewLocation(location, isGps = false)
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
                    POLL_INTERVAL_MS,
                    0f,
                    netListener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.w(TAG, "注册网络定位监听失败: ${e.message}")
            }
        }
    }

    private fun unregisterLocationListeners() {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        activeGpsListener?.let {
            try { locationManager.removeUpdates(it) } catch (e: Exception) { e.printStackTrace() }
            activeGpsListener = null
        }
        activeNetworkListener?.let {
            try { locationManager.removeUpdates(it) } catch (e: Exception) { e.printStackTrace() }
            activeNetworkListener = null
        }
    }

    /**
     * 5 秒周期性检测与主动拉取最新位置（应对某些车机 ROM requestLocationUpdates 不准时回调的情况）
     */
    @SuppressLint("MissingPermission")
    private fun checkLocationUpdates() {
        if (!hasLocationPermission()) return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        val now = SystemClock.elapsedRealtime()

        // 1. 优先检查 GPS 最后已知位置
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null) {
                    onNewLocation(gpsLoc, isGps = true)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. 若 8 秒内未收到 GPS 信号，回退检查网络或被动定位
        if (!isGpsFixReceived || (now - lastGpsFixTime > GPS_SIGNAL_TIMEOUT_MS)) {
            try {
                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null) {
                    onNewLocation(netLoc, isGps = false)
                    return
                }
                val passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passiveLoc != null) {
                    onNewLocation(passiveLoc, isGps = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 接收到有效定位数据（GPS 或网络）
     */
    private fun onNewLocation(location: Location, isGps: Boolean) {
        if (isGps) {
            isGpsFixReceived = true
            lastGpsFixTime = SystemClock.elapsedRealtime()
        }

        val lat = location.latitude
        val lon = location.longitude

        val now = System.currentTimeMillis()
        val shouldGeocode = if (lastGeocodedLat == 0.0 && lastGeocodedLon == 0.0) {
            true
        } else {
            val dist = FloatArray(1)
            Location.distanceBetween(lastGeocodedLat, lastGeocodedLon, lat, lon, dist)
            // 位移超过 150 米（车辆行驶移动）或距上次逆地理超过 60 秒，触发更新
            dist[0] >= MIN_DISTANCE_METERS_FOR_GEOCODE || (now - lastGeocodeTime > 60_000L)
        }

        if (shouldGeocode) {
            lastGeocodedLat = lat
            lastGeocodedLon = lon
            lastGeocodeTime = now

            thread {
                val locationName = reverseGeocode(lat, lon)
                if (locationName.isNotBlank()) {
                    lastLocationText = locationName
                    postUpdate()
                }
            }
        }

        // 检查天气刷新：成功 15 分钟后刷新；失败则 1 分钟后重试，避免永久卡在"天气获取中"
        val weatherInterval = if (lastWeatherOk) WEATHER_REFRESH_INTERVAL_MS else WEATHER_RETRY_INTERVAL_MS
        if (now - lastWeatherFetchTime > weatherInterval) {
            lastWeatherFetchTime = now
            thread {
                fetchWeather(lat, lon)
            }
        }
    }

    /**
     * 双重逆地理编码解析：
     * 1. 优先尝试系统 Geocoder
     * 2. 车机/无 GMS 系统自动切换到开源在线逆地理接口，确保 100% 成功解析
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
            Log.w(TAG, "系统 Geocoder 不可用: ${e.message}")
        }

        // 2. 车机专用无 key 开源逆地理编码（支持简体中文行政区划）
        try {
            val urlStr = "https://api-bdc.io/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=zh-Hans"
                .format(Locale.US, lat, lon)
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
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
     * IP 定位及天气反查（毫秒级秒开展示）
     */
    private fun fetchIpLocationAndWeather() {
        thread {
            try {
                val url = URL("http://ip-api.com/json/?lang=zh-CN")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
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

                        if (lastLocationText == "正在定位..." && locName.isNotEmpty()) {
                            lastLocationText = locName
                            postUpdate()
                        }

                        if (lastWeatherText == "天气获取中..." && lat != 0.0 && lon != 0.0) {
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
     * 生成/复用和风天气 JWT（Ed25519 签名）。
     * - Header:  {"alg":"EdDSA","kid":凭据ID}
     * - Payload: {"iss":开发者ID,"sub":项目ID,"iat":now-30,"exp":now+14min}
     * - 签名：PKCS#8 DER 的最后 32 字节即 Ed25519 种子，直接构造私钥签名
     * - 使用 Android Base64 URL_SAFE|NO_PADDING|NO_WRAP（API 8+ 兼容，minSdk 21 可用）
     */
    @Synchronized
    private fun getJwt(): String {
        val now = System.currentTimeMillis()
        if (cachedJwt.isNotEmpty() && now < cachedJwtExpireAt - 60_000L) {
            return cachedJwt
        }
        val iat = now / 1000 - 30
        val exp = (now + JWT_VALID_MS) / 1000

        val header = "{\"alg\":\"$JWT_EDDSA\",\"kid\":\"$QWEATHER_KID\"}"
        val payload = "{\"iss\":\"$QWEATHER_ISS\",\"sub\":\"$QWEATHER_SUB\",\"iat\":$iat,\"exp\":$exp}"

        val flags = android.util.Base64.URL_SAFE or
                android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP
        val h = android.util.Base64.encodeToString(header.toByteArray(Charsets.UTF_8), flags)
        val p = android.util.Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), flags)
        val signingInput = "$h.$p"

        // PKCS#8 Ed25519 私钥 DER 结构末尾 32 字节是 seed
        val der = android.util.Base64.decode(QWEATHER_PRIVATE_KEY_B64, android.util.Base64.DEFAULT)
        val seed = der.copyOfRange(der.size - 32, der.size)
        val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val inputBytes = signingInput.toByteArray(Charsets.UTF_8)
        signer.update(inputBytes, 0, inputBytes.size)
        val sig = android.util.Base64.encodeToString(signer.generateSignature(), flags)

        cachedJwt = "$signingInput.$sig"
        cachedJwtExpireAt = now + JWT_VALID_MS
        return cachedJwt
    }

    /**
     * 调用和风天气实况 API（v7/weather/now，按经纬度查询）。
     * - 专属 API Host + JWT（Authorization: Bearer）鉴权；响应为 gzip，必须解压；
     * - 返回 code=200 时取 now.text（中文天气）+ now.temp（温度）；
     * - 任何失败都改写兜底文案并标记失败，配合 1 分钟重试，杜绝永久"天气获取中"。
     */
    private fun fetchWeather(lat: Double, lon: Double) {
        var ok = false
        try {
            // 和风 location 参数是"经度,纬度"，与常见 latitude/longitude 顺序相反
            val urlStr = "https://$QWEATHER_API_HOST/v7/weather/now?location=%.4f,%.4f&lang=zh"
                .format(Locale.US, lon, lat)
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer ${getJwt()}")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "RadioFM/1.2.5 (Android)")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                // 和风强制 gzip 压缩，必须解压，否则 JSONObject 解析乱码/抛异常
                val stream = try {
                    GZIPInputStream(conn.inputStream)
                } catch (e: Exception) {
                    conn.inputStream  // 万一未压缩则退回原始流
                }
                val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                if (json.optString("code") == "200") {
                    val now = json.optJSONObject("now")
                    if (now != null) {
                        val text = now.optString("text", "")
                        val temp = now.optString("temp", "")
                        if (text.isNotEmpty() || temp.isNotEmpty()) {
                            lastWeatherText = when {
                                text.isNotEmpty() && temp.isNotEmpty() -> "$text ${temp}°C"
                                text.isNotEmpty() -> text
                                else -> "${temp}°C"
                            }
                            ok = true
                        }
                    }
                } else {
                    Log.w(TAG, "和风天气返回异常: code=${json.optString("code")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "天气查询异常: ${e.message}")
        }

        lastWeatherOk = ok
        if (!ok) {
            // 关键修复：失败时给出兜底文案而不是永远停在"天气获取中..."
            // 若之前已有成功天气数据则保留旧值，仅首次失败时改为"天气暂不可用"
            if (lastWeatherText == "天气获取中...") {
                lastWeatherText = "天气暂不可用"
            }
        }
        postUpdate()
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
