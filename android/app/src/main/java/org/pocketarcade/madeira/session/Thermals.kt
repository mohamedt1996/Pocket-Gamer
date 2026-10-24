// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Heat in a game session: Android's sustained performance mode for the game window,
// Android's game mode (battery mode lowers the automatic frame cap), and thermal
// status changes, so a hot phone is reported instead of silently throttling.

package org.pocketarcade.madeira.session

import android.app.Activity
import android.app.GameManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast

object Thermals {
    private const val TAG = "PocketThermals"

    /** System battery saver is independent of the game dashboard's battery mode. */
    fun powerSaveMode(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    } catch (_: RuntimeException) { false }

    /** Android's game mode for this app is "battery" (Android 12+; the user picks it in
     *  the system's game dashboard or the phone maker's game booster). */
    fun batteryGameMode(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            context.getSystemService(GameManager::class.java)?.gameMode == GameManager.GAME_MODE_BATTERY
        } catch (_: Throwable) { false }
    }

    /**
     * For the game window: sustained performance mode when the phone supports it and the
     * setting is on (steady clocks it can hold, rather than bursts and then throttling),
     * and a thermal status listener that logs every change and warns once the phone is hot.
     */
    fun attach(activity: Activity, spec: LaunchSpec) {
        activity.runOnUiThread {
            val power = activity.getSystemService(PowerManager::class.java) ?: return@runOnUiThread
            val sustained = spec.steadyPerformance && power.isSustainedPerformanceModeSupported
            if (sustained) activity.window.setSustainedPerformanceMode(true)
            Log.i(TAG, "sustained performance mode: " + (if (sustained) "on" else if (spec.steadyPerformance) "not supported" else "off") +
                ", battery game mode: ${batteryGameMode(activity)}, power saver: ${power.isPowerSaveMode}")
            Log.i(TAG, "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}; " +
                "launch: graphics=${spec.graphics}, frame cap=${spec.frameCap}, CPU mode=${spec.cpuMode}")
            if (Build.VERSION.SDK_INT >= 29) {
                var warned = false
                power.addThermalStatusListener(activity.mainExecutor) { status ->
                    Log.i(TAG, "thermal status: ${describe(status)}")
                    if (status >= PowerManager.THERMAL_STATUS_SEVERE && !warned) {
                        warned = true
                        Toast.makeText(activity, "The phone is getting hot. A lower frame cap in the game's settings keeps it cooler.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun describe(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> status.toString()
    }
}
