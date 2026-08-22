package com.shadowprotectors.alarmapp.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun searchPlaces(query: String, maxResults: Int = 5): List<PlaceSearchResult> {
        return withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent() || query.isBlank()) {
                return@withContext emptyList()
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCoroutine { continuation ->
                        geocoder.getFromLocationName(query, maxResults, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                continuation.resume(addresses.map { addressToSearchResult(it) })
                            }

                            override fun onError(errorMessage: String?) {
                                Log.w("GeocodingHelper", "Geocode error: $errorMessage")
                                continuation.resume(emptyList())
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, maxResults) ?: emptyList()
                    addresses.map { addressToSearchResult(it) }
                }
            } catch (e: Exception) {
                Log.e("GeocodingHelper", "Failed searching place: ${e.message}", e)
                emptyList()
            }
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
        val title = addr.featureName ?: addr.subLocality ?: addr.locality ?: "Location"
        val subtitleParts = listOfNotNull(
            addr.subLocality.takeIf { it != title },
            addr.locality.takeIf { it != title },
            addr.adminArea,
            addr.countryName
        )
        val subtitle = if (subtitleParts.isNotEmpty()) subtitleParts.joinToString(", ") else addr.getAddressLine(0) ?: ""
        return PlaceSearchResult(title, subtitle, addr.latitude, addr.longitude)
    }
}
