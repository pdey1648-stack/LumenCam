package com.example.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.domain.model.ThermalTier
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class ThermalMonitor(private val context: Context) {

    fun observeThermalTier(): Flow<ThermalTier> = callbackFlow {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            trySend(ThermalTier.NORMAL)
            close()
            return@callbackFlow
        }

        trySend(mapThermalStatus(powerManager.currentThermalStatus))

        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            trySend(mapThermalStatus(status))
        }
        powerManager.addThermalStatusListener(context.mainExecutor, listener)

        awaitClose {
            powerManager.removeThermalStatusListener(listener)
        }
    }.distinctUntilChanged()

    private fun mapThermalStatus(status: Int): ThermalTier {
        return when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalTier.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalTier.SEVERE
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalTier.MODERATE
            else -> ThermalTier.NORMAL
        }
    }
}
