package com.shadowprotectors.alarmapp.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class PlaceSearchResult(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

class GeocodingHelper(private val context: Context) {

    private val geocoder = Geocoder(context, Locale.getDefault())

    /**
     * Searches places using Android Geocoder with local proximity bias,
     * falling back to Nominatim / Photon OSM search for accurate landmark resolution (e.g., Mattuthavani bus stop).
     */
    suspend fun searchPlaces(
        query: String,
        userLat: Double? = null,
        userLng: Double? = null,
        maxResults: Int = 5
    ): List<PlaceSearchResult> {
        return withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val results = mutableListOf<PlaceSearchResult>()

            // 1. Try Android Native Geocoder with local bounds if user coordinates are known
            try {
                if (Geocoder.isPresent()) {
                    val localAddresses = if (userLat != null && userLng != null) {
                        // Bias search within ±1.5 degrees (~160 km radius around user)
                        val lowerLeftLat = (userLat - 1.5).coerceAtLeast(-90.0)
                        val lowerLeftLon = (userLng - 1.5).coerceAtLeast(-180.0)
                        val upperRightLat = (userLat + 1.5).coerceAtMost(90.0)
                        val upperRightLon = (userLng + 1.5).coerceAtMost(180.0)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            suspendCoroutine { continuation ->
                                geocoder.getFromLocationName(
                                    query, maxResults, lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon,
                                    object : Geocoder.GeocodeListener {
                                        override fun onGeocode(addresses: MutableList<Address>) {
                                            continuation.resume(addresses)
                                        }
                                        override fun onError(errorMessage: String?) {
                                            continuation.resume(mutableListOf())
                                        }
                                    }
                                )
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            geocoder.getFromLocationName(query, maxResults, lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon) ?: emptyList()
                        }
                    } else {
                        emptyList()
                    }

                    if (localAddresses.isNotEmpty()) {
                        results.addAll(localAddresses.map { addressToSearchResult(it) })
                    } else {
                        // General fallback on Android Geocoder
                        val generalAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            suspendCoroutine { continuation ->
                                geocoder.getFromLocationName(query, maxResults, object : Geocoder.GeocodeListener {
                                    override fun onGeocode(addresses: MutableList<Address>) {
                                        continuation.resume(addresses)
                                    }
                                    override fun onError(errorMessage: String?) {
                                        continuation.resume(mutableListOf())
                                    }
                                })
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            geocoder.getFromLocationName(query, maxResults) ?: emptyList()
                        }
                        results.addAll(generalAddresses.map { addressToSearchResult(it) })
                    }
                }
            } catch (e: Exception) {
                Log.w("GeocodingHelper", "Native geocoder error: ${e.message}")
            }

            // 2. If results are empty or need better OSM precision (like bus stops), query Photon / Nominatim API
            if (results.isEmpty()) {
                try {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val biasParam = if (userLat != null && userLng != null) "&lat=$userLat&lon=$userLng" else ""
                    val url = URL("https://photon.komoot.io/api/?q=$encodedQuery&limit=$maxResults$biasParam")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "TravelAlarm/1.0")
                    }

                    if (connection.responseCode == 200) {
                        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonObject = org.json.JSONObject(responseText)
                        val features = jsonObject.optJSONArray("features") ?: JSONArray()

                        for (i in 0 until features.length()) {
                            val feature = features.getJSONObject(i)
                            val geometry = feature.getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")
                            val lng = coordinates.getDouble(0)
                            val lat = coordinates.getDouble(1)

                            val properties = feature.getJSONObject("properties")
                            val name = properties.optString("name", query)
                            val city = properties.optString("city", properties.optString("county", properties.optString("state", "")))
                            val subtitle = listOfNotNull(properties.optString("street").takeIf { it.isNotBlank() }, city.takeIf { it.isNotBlank() }, properties.optString("country").takeIf { it.isNotBlank() }).joinToString(", ")

                            results.add(PlaceSearchResult(title = name, subtitle = subtitle, latitude = lat, longitude = lng))
                        }
                    }
                } catch (e: Exception) {
                    Log.w("GeocodingHelper", "Photon OSM search error: ${e.message}")
                }
            }

            results
        }
    }

    suspend fun reverseGeocode(latitude: Double, longitude: Double): String {
        return withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) {
                return@withContext String.format(Locale.US, "Location (%.4f, %.4f)", latitude, longitude)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCoroutine { continuation ->
                        geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (addresses.isNotEmpty()) {
                                    val addr = addresses[0]
                                    val name = addr.featureName ?: addr.subLocality ?: addr.locality ?: addr.getAddressLine(0) ?: "Selected Point"
                                    continuation.resume(name)
                                } else {
                                    continuation.resume(String.format(Locale.US, "Point (%.4f, %.4f)", latitude, longitude))
                                }
                            }

                            override fun onError(errorMessage: String?) {
                                continuation.resume(String.format(Locale.US, "Point (%.4f, %.4f)", latitude, longitude))
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        addr.featureName ?: addr.subLocality ?: addr.locality ?: addr.getAddressLine(0) ?: "Selected Point"
                    } else {
                        String.format(Locale.US, "Point (%.4f, %.4f)", latitude, longitude)
                    }
                }
            } catch (e: Exception) {
                String.format(Locale.US, "Point (%.4f, %.4f)", latitude, longitude)
            }
        }
    }

    suspend fun getLandmarkConfirmation(latitude: Double, longitude: Double): String {
        return withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) {
                return@withContext "Coordinates: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}"
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCoroutine { continuation ->
                        geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (addresses.isNotEmpty()) {
                                    continuation.resume(formatLandmarkText(addresses[0]))
                                } else {
                                    continuation.resume("GPS Coordinates: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}")
                                }
                            }

                            override fun onError(errorMessage: String?) {
                                continuation.resume("GPS Coordinates: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}")
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        formatLandmarkText(addresses[0])
                    } else {
                        "GPS Coordinates: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}"
                    }
                }
            } catch (e: Exception) {
                "GPS Coordinates: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}"
            }
        }
    }

    private fun formatLandmarkText(addr: Address): String {
        val landmarks = listOfNotNull(
            addr.featureName?.takeIf { it != addr.subLocality && it != addr.locality && !it.matches(Regex("^[0-9\\-]+$")) },
            addr.thoroughfare?.let { "on $it" },
            addr.subLocality?.let { "near $it" },
            addr.locality
        )
        return if (landmarks.isNotEmpty()) {
            landmarks.distinct().joinToString(", ")
        } else {
            addr.getAddressLine(0) ?: "Identified Area"
        }
    }

    private fun addressToSearchResult(addr: Address): PlaceSearchResult {
        val title = addr.featureName ?: addr.subLocality ?: addr.locality ?: "Selected Place"
        val subtitleParts = listOfNotNull(
            addr.thoroughfare,
            addr.subLocality.takeIf { it != title },
            addr.locality.takeIf { it != title },
            addr.adminArea,
            addr.countryName
        )
        val subtitle = if (subtitleParts.isNotEmpty()) subtitleParts.joinToString(", ") else addr.getAddressLine(0) ?: ""
        return PlaceSearchResult(
            title = title,
            subtitle = subtitle,
            latitude = addr.latitude,
            longitude = addr.longitude
        )
    }
}
