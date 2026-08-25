package com.shadowprotectors.alarmapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shadowprotectors.alarmapp.databinding.ActivityModeSelectionBinding
import com.shadowprotectors.alarmapp.engine.TripMode
import com.shadowprotectors.alarmapp.ui.TrainActivity

class ModeSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModeSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if tracking is already active — if so, auto-route to the appropriate page
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getString("PREF_TRIP_MODE", null)

        binding = ActivityModeSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardBusMode.setOnClickListener {
            saveModeAndLaunch(TripMode.BUS_CAR)
        }

        binding.cardTrainMode.setOnClickListener {
            saveModeAndLaunch(TripMode.TRAIN)
        }
    }

    private fun saveModeAndLaunch(mode: TripMode) {
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("PREF_TRIP_MODE", mode.name).apply()

        if (mode == TripMode.BUS_CAR) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        } else {
            val intent = Intent(this, TrainActivity::class.java)
            startActivity(intent)
        }
    }
}
