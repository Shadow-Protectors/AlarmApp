package com.shadowprotectors.alarmapp.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shadowprotectors.alarmapp.databinding.LayoutMapPickerBinding
import com.shadowprotectors.alarmapp.util.GeocodingHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import java.util.Locale

class MapPickerBottomSheet : BottomSheetDialogFragment() {

    interface OnDestinationSelectedListener {
        fun onDestinationSelected(name: String, latitude: Double, longitude: Double)
    }

    private var _binding: LayoutMapPickerBinding? = null
    private val binding get() = _binding!!

    private var listener: OnDestinationSelectedListener? = null
    private lateinit var geocodingHelper: GeocodingHelper
    private lateinit var searchAdapter: SearchResultAdapter

    private var currentCenterLat = 9.9196
    private var currentCenterLng = 78.1100
    private var currentPlaceName = "Madurai Junction"

    private var searchJob: Job? = null
    private var reverseGeocodeJob: Job? = null

    fun setOnDestinationSelectedListener(listener: OnDestinationSelectedListener) {
        this.listener = listener
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val prefs = context.getSharedPreferences("${context.packageName}_osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue = context.packageName
        geocodingHelper = GeocodingHelper(context)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = false
                behavior.skipCollapsed = true
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutMapPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()
        setupSearch()
        setupListeners()
        fetchInitialLocation()
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(currentCenterLat, currentCenterLng))

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    onMapMoved()
                    return true
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    onMapMoved()
                    return true
                }
            })
        }
    }

    private fun onMapMoved() {
        val center = binding.mapView.mapCenter
        currentCenterLat = center.latitude
        currentCenterLng = center.longitude

        binding.tvSelectedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", currentCenterLat, currentCenterLng)

        // Debounce reverse geocoding
        reverseGeocodeJob?.cancel()
        reverseGeocodeJob = lifecycleScope.launch {
            delay(500)
            binding.pbGeocoding.isVisible = true
            val resolvedName = geocodingHelper.reverseGeocode(currentCenterLat, currentCenterLng)
            currentPlaceName = resolvedName
            binding.tvSelectedPlaceName.text = resolvedName
            binding.pbGeocoding.isVisible = false
        }
    }

    private fun setupSearch() {
        searchAdapter = SearchResultAdapter { result ->
            binding.cardSuggestions.isVisible = false
            binding.etSearchQuery.setText("")
            binding.btnClearSearch.isVisible = false

            currentCenterLat = result.latitude
            currentCenterLng = result.longitude
            currentPlaceName = result.title

            binding.tvSelectedPlaceName.text = result.title
            binding.tvSelectedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", result.latitude, result.longitude)

            binding.mapView.controller.animateTo(GeoPoint(result.latitude, result.longitude))
            binding.mapView.controller.setZoom(16.5)
        }

        binding.rvSearchSuggestions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }

        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.isVisible = query.isNotEmpty()

                searchJob?.cancel()
                if (query.length >= 2) {
                    searchJob = lifecycleScope.launch {
                        delay(350)
                        val results = geocodingHelper.searchPlaces(query, currentCenterLat, currentCenterLng)
                        if (results.isNotEmpty()) {
                            searchAdapter.submitList(results)
                            binding.cardSuggestions.isVisible = true
                        } else {
                            binding.cardSuggestions.isVisible = false
                        }
                    }
                } else {
                    binding.cardSuggestions.isVisible = false
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            binding.cardSuggestions.isVisible = false
        }
    }

    private fun setupListeners() {
        binding.fabMyLocation.setOnClickListener {
            fetchInitialLocation()
        }

        binding.btnConfirmDestination.setOnClickListener {
            listener?.onDestinationSelected(currentPlaceName, currentCenterLat, currentCenterLng)
            dismiss()
        }
    }

    private fun fetchInitialLocation() {
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentCenterLat = location.latitude
                    currentCenterLng = location.longitude
                    binding.mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                    binding.mapView.controller.setZoom(16.0)
                }
            }
        } catch (e: SecurityException) {
            // Use default coordinates if permission not granted yet
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MapPickerBottomSheet"
        fun newInstance() = MapPickerBottomSheet()
    }
}
