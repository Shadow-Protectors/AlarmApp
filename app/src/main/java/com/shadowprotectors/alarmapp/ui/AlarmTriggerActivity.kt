package com.shadowprotectors.alarmapp.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.shadowprotectors.alarmapp.alert.AudioAlarmHelper
import com.shadowprotectors.alarmapp.databinding.ActivityAlarmTriggerBinding
import com.shadowprotectors.alarmapp.service.LocationTrackingService

class AlarmTriggerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEST_NAME = "EXTRA_DEST_NAME"
        const val EXTRA_DISTANCE_KM = "EXTRA_DISTANCE_KM"
    }

    private lateinit var binding: ActivityAlarmTriggerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOnAndShowOverLockscreen()

        binding = ActivityAlarmTriggerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val destName = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
        val distanceKm = intent.getDoubleExtra(EXTRA_DISTANCE_KM, 0.5)

        binding.tvDestName.text = destName
        binding.tvDistanceRemaining.text = String.format("Distance to stop: %.2f km", distanceKm)

        // Dismiss button click
        binding.btnDismissAlarm.setOnClickListener {
            dismissAndStopAlarm()
        }

        // Handle hardware / gesture back navigation to guarantee stopping alarm
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismissAndStopAlarm()
            }
        })
    }

    private fun dismissAndStopAlarm() {
        LocationTrackingService.stopAlarm(this)
        AudioAlarmHelper.stopFullAlarm(this)
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            AudioAlarmHelper.stopFullAlarm(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocationTrackingService.stopAlarm(this)
        AudioAlarmHelper.stopFullAlarm(this)
    }

    private fun turnScreenOnAndShowOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}
