package com.shadowprotectors.alarmapp.util

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class ParsedLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
)

object LocationLinkParser {

    private val COORD_REGEX = Pattern.compile("(-?\\d{1,2}\\.\\d{3,8})[\\s,]+(-?\\d{1,3}\\.\\d{3,8})")
    private val AT_COORD_REGEX = Pattern.compile("@(-?\\d{1,2}\\.\\d{3,8}),(-?\\d{1,3}\\.\\d{3,8})")
    private val PARAM_COORD_REGEX = Pattern.compile("(?:q|ll|query|daddr|destination)=(-?\\d{1,2}\\.\\d{3,8}),(-?\\d{1,3}\\.\\d{3,8})")
    private val GEO_REGEX = Pattern.compile("geo:(-?\\d{1,2}\\.\\d{3,8}),(-?\\d{1,3}\\.\\d{3,8})")

    /**
     * Parses a string containing coordinates, a Google Maps link, a geo URI, or WhatsApp shared location text.
     */
    suspend fun parse(input: String): ParsedLocation? {
        return withContext(Dispatchers.IO) {
            val text = input.trim()
            if (text.isEmpty()) return@withContext null

            // 1. Check if input is a short URL (maps.app.goo.gl or goo.gl/maps)
            if (text.contains("maps.app.goo.gl") || text.contains("goo.gl/maps")) {
                val expandedUrl = expandShortUrl(text)
                if (expandedUrl != null) {
                    val fromExpanded = parseFromUrl(expandedUrl)
                    if (fromExpanded != null) return@withContext fromExpanded
                }
            }

            // 2. Parse from standard URL
            if (text.startsWith("http://") || text.startsWith("https://")) {
                val fromUrl = parseFromUrl(text)
                if (fromUrl != null) return@withContext fromUrl
            }

            // 3. Parse from geo: URI
            val geoMatcher = GEO_REGEX.matcher(text)
            if (geoMatcher.find()) {
                val lat = geoMatcher.group(1)?.toDoubleOrNull()
                val lng = geoMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null) {
                    return@withContext ParsedLocation(lat, lng)
                }
            }

            // 4. Parse from raw text containing coordinate numbers
            val rawMatcher = COORD_REGEX.matcher(text)
            if (rawMatcher.find()) {
                val lat = rawMatcher.group(1)?.toDoubleOrNull()
                val lng = rawMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                    return@withContext ParsedLocation(lat, lng)
                }
            }

            null
        }
    }

    private fun parseFromUrl(urlStr: String): ParsedLocation? {
        try {
            val uri = Uri.parse(urlStr)

            // A. Query parameters: q=lat,lng or ll=lat,lng
            val qParam = uri.getQueryParameter("q") ?: uri.getQueryParameter("query") ?: uri.getQueryParameter("ll") ?: uri.getQueryParameter("daddr")
            if (qParam != null) {
                val matcher = COORD_REGEX.matcher(qParam)
                if (matcher.find()) {
                    val lat = matcher.group(1)?.toDoubleOrNull()
                    val lng = matcher.group(2)?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        return ParsedLocation(lat, lng)
                    }
                }
            }

            // B. Path-based coordinates: @lat,lng,zoom
            val atMatcher = AT_COORD_REGEX.matcher(urlStr)
            if (atMatcher.find()) {
                val lat = atMatcher.group(1)?.toDoubleOrNull()
                val lng = atMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null) {
                    return ParsedLocation(lat, lng)
                }
            }

            // C. Regex match anywhere in URL
            val paramMatcher = PARAM_COORD_REGEX.matcher(urlStr)
            if (paramMatcher.find()) {
                val lat = paramMatcher.group(1)?.toDoubleOrNull()
                val lng = paramMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null) {
                    return ParsedLocation(lat, lng)
                }
            }
        } catch (e: Exception) {
            Log.e("LocationLinkParser", "Failed to parse URL: ${e.message}")
        }
        return null
    }

    private fun expandShortUrl(shortUrl: String): String? {
        var currentUrl = shortUrl
        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) {
            val matcher = Pattern.compile("https?://[^\\s]+").matcher(shortUrl)
            if (matcher.find()) {
                currentUrl = matcher.group(0) ?: shortUrl
            } else {
                currentUrl = "https://$shortUrl"
            }
        }

        try {
            var connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val redirectedUrl = connection.getHeaderField("Location")
                if (!redirectedUrl.isNullOrEmpty()) {
                    return redirectedUrl
                }
            }

            // If HEAD was not redirected, try GET
            connection.disconnect()
            connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            return connection.url.toString()
        } catch (e: Exception) {
            Log.e("LocationLinkParser", "Error expanding short URL: ${e.message}")
        }
        return null
    }

    private fun isValidCoordinate(lat: Double, lng: Double): Boolean {
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }
}
