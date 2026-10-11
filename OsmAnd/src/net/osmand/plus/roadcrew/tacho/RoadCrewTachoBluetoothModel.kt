package net.osmand.plus.roadcrew.tacho

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import net.osmand.PlatformUtil
import net.osmand.plus.R

/** Survives rotation without retaining an Activity or opening a second GATT connection. */
class RoadCrewTachoBluetoothModel(app: Application) : AndroidViewModel(app) {
    data class State(val busy: Boolean = false, val phase: Int = R.string.roadcrew_tacho_ble_ready,
                     val bytes: Int = 0, val parts: Int = 0, val error: String? = null,
                     val file: RoadCrewTachoBluetoothStore.Stored? = null, val canCancel: Boolean = false)
    val state = MutableLiveData(State())
    private var worker: Thread? = null
    private var transport: RoadCrewTachoBleTransport? = null
    @Volatile private var cancelled = false

    @Synchronized fun start(device: BluetoothDevice) {
        if (worker?.isAlive == true) return
        cancelled = false
        val connection = RoadCrewTachoBleTransport()
        transport = connection
        state.value = State(true, R.string.roadcrew_tacho_ble_connecting, canCancel = true)
        worker = Thread({
            try {
                connection.connect(getApplication(), device)
                var updated = 0L
                state.postValue(State(true, R.string.roadcrew_tacho_ble_receiving, canCancel = true))
                val bytes = RoadCrewTachoVuDownload.download(connection) { count, parts ->
                    val now = System.nanoTime()
                    if (now - updated >= 250_000_000L) {
                        state.postValue(State(true, R.string.roadcrew_tacho_ble_receiving, count, parts, canCancel = true))
                        updated = now
                    }
                }
                // The protocol is closed before touching local storage. No extra card-writing command.
                connection.close()
                state.postValue(State(true, R.string.roadcrew_tacho_ble_saving, bytes.size))
                val stored = RoadCrewTachoBluetoothStore.save(getApplication(), bytes, System.currentTimeMillis())
                try {
                    RoadCrewTachoReminder.remember(getApplication(), stored.takenAt / 1000)
                } catch (e: RuntimeException) {
                    // A reminder failure must not misreport an already verified, saved file as lost.
                    PlatformUtil.getLog(RoadCrewTachoBluetoothModel::class.java).warn("Cannot reschedule card reminder", e)
                }
                state.postValue(State(phase = R.string.roadcrew_tacho_ble_done, bytes = bytes.size, file = stored))
            } catch (e: Exception) {
                state.postValue(if (cancelled) State(phase = R.string.roadcrew_tacho_ble_cancelled)
                    else State(phase = R.string.roadcrew_tacho_ble_failed, error = e.message ?: e.javaClass.simpleName))
            } finally {
                connection.close()
            }
        }, "RoadCrewTachoBluetooth").apply { start() }
    }

    @Synchronized fun cancel() {
        cancelled = true
        transport?.close()
        worker?.interrupt()
    }

    override fun onCleared() { cancel() }
}
