package net.osmand.plus.roadcrew.tacho

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import net.osmand.PlatformUtil

/**
 * The Bluetooth card download behind "Свали картата" (ROADMAP 410-411, 439): from the tachograph
 * shown on the card screen, paired already - pairing is only in the "Тахограф" section
 * (RoadCrewTachoPairModel; Galin, 03.11.2026). Opening the Bluetooth side looks for the paired
 * tachographs near the phone and picks the nearest by itself. A dropped start is tried again, and
 * the part of the card that is coming is named. Survives rotation without holding the screen.
 */
class RoadCrewTachoBluetoothModel(app: Application) : AndroidViewModel(app) {
    enum class Step { IDLE, CONNECTING, READING_CARD, RECEIVING, SAVING, DONE, FAILED, CANCELLED }

    data class State(
        val step: Step = Step.IDLE,
        val device: String? = null,
        val address: String? = null,
        val attempt: Int = 1,
        val retrying: Boolean = false,
        val permille: Int = 0,
        val fileId: Int = -1,
        val secondGeneration: Boolean = false,
        val filePercent: Int = 0,
        val filesDone: Int = 0,
        val filesTotal: Int = RoadCrewTachoVuProgress.FILES_G1 + RoadCrewTachoVuProgress.FILES_G2,
        val error: String? = null,
        val file: RoadCrewTachoBluetoothStore.Stored? = null,
    ) {
        val busy: Boolean get() = step in setOf(Step.CONNECTING, Step.READING_CARD, Step.RECEIVING, Step.SAVING)
    }

    /** The tachograph "Свали картата" reaches: the nearest paired one heard, or the one the driver picked. */
    data class Nearby(
        val scanning: Boolean = false,
        /** The phone's paired tachographs, the heard ones first (RoadCrewTachoBleChoice.pairedWithSignal). */
        val paired: List<RoadCrewTachoBleChoice.Candidate> = emptyList(),
        val selected: RoadCrewTachoBleChoice.Candidate? = null,
        val error: String? = null,
    ) {
        val selectedHeard: Boolean get() = selected != null && RoadCrewTachoBleChoice.heard(selected)
    }

    val state = MutableLiveData(State())
    val nearby = MutableLiveData(Nearby())
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var scanner: Thread? = null
    @Volatile private var transport: RoadCrewTachoBleTransport? = null
    @Volatile private var cancelled = false
    /** Address and name of the tachograph this run reached, for "Опитай пак". */
    @Volatile private var chosen: Pair<String, String>? = null

    /**
     * Opening the Bluetooth side, or Bluetooth just turned on: which paired tachographs are near the
     * phone. The nearest heard is picked by itself; one the driver picked stays while it is paired.
     */
    @Synchronized fun findNearby() {
        if (worker?.isAlive == true || scanner?.isAlive == true) return
        nearby.value = (nearby.value ?: Nearby()).copy(scanning = true, error = null)
        scanner = Thread({
            var paired = emptyList<RoadCrewTachoBleChoice.Candidate>()
            var near = emptyList<RoadCrewTachoBleChoice.Candidate>()
            var error: String? = null
            try {
                val adapter = adapter()
                if (adapter == null || !adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
                val bonded = RoadCrewTachoBleFinder.bonded(adapter)
                val heard = RoadCrewTachoBleFinder.scan(adapter, bonded)
                paired = RoadCrewTachoBleChoice.pairedWithSignal(bonded, heard)
                near = RoadCrewTachoBleChoice.nearbyPaired(bonded, heard)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            // Merged on the main thread with the pick as it is then: the driver may have picked one in
            // the "Тахограф" section while this scan was still listening.
            main.post {
                val now = nearby.value ?: Nearby()
                val kept = now.selected?.let { s -> paired.firstOrNull { it.address.equals(s.address, ignoreCase = true) } }
                val picked = near.firstOrNull()?.let { n -> paired.firstOrNull { it.address.equals(n.address, ignoreCase = true) } }
                nearby.value = if (error != null) Nearby(selected = now.selected, error = error)
                    else Nearby(paired = paired, selected = kept ?: picked)
            }
        }, "RoadCrewTachoNearby").apply { start() }
    }

    /** The driver picked one in the "Тахограф" section, or its wizard paired a new one. */
    @Synchronized fun select(address: String, name: String, rssi: Int) {
        val now = nearby.value ?: Nearby()
        nearby.value = now.copy(selected = RoadCrewTachoBleChoice.Candidate(address, name, true, rssi))
    }

    /** "Свали картата": the tachograph shown on the screen. Pairing is only in the "Тахограф" section. */
    @Synchronized fun downloadFrom(address: String) = launch {
        val adapter = adapter() ?: throw java.io.IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        val name = device.name ?: address
        chosen = address to name
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw java.io.IOException("Pair the tachograph first")
        download(device, name)
    }

    /** "Опитай пак": the tachograph tried before, straight away and without a new search. */
    @Synchronized fun retry() {
        val address = state.value?.address ?: nearby.value?.selected?.address
        if (address != null) downloadFrom(address)
    }

    @Synchronized fun reset() {
        if (worker?.isAlive != true) state.value = State()
    }

    @Synchronized fun cancel() {
        cancelled = true
        transport?.close()
        worker?.interrupt()
    }

    override fun onCleared() { cancel(); scanner?.interrupt() }

    private fun launch(job: () -> Unit) {
        if (worker?.isAlive == true || scanner?.isAlive == true) return
        cancelled = false
        chosen = null
        state.value = State(Step.CONNECTING)
        worker = Thread({
            try {
                job()
            } catch (e: Exception) {
                state.postValue(if (cancelled) State(Step.CANCELLED)
                    else State(Step.FAILED, device = chosen?.second, address = chosen?.first,
                        error = e.message ?: e.javaClass.simpleName))
            }
        }, "RoadCrewTachoBluetooth").apply { start() }
    }

    private fun adapter() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    private fun download(device: BluetoothDevice, name: String) {
        var attempt = 1
        while (true) {
            val tracker = RoadCrewTachoVuProgress()
            val connection = RoadCrewTachoBleTransport()
            transport = connection
            state.postValue(State(Step.CONNECTING, device = name, attempt = attempt, retrying = attempt > 1))
            try {
                connection.connect(getApplication(), device)
                state.postValue(State(Step.READING_CARD, device = name, attempt = attempt))
                var posted = 0L
                val bytes = RoadCrewTachoVuDownload.download(connection, object : RoadCrewTachoVuDownload.Progress {
                    override fun data(bytes: ByteArray, offset: Int, length: Int) = tracker.data(bytes, offset, length)
                    override fun received(bytes: Int, parts: Int) {
                        tracker.received(bytes, parts)
                        val now = System.nanoTime()
                        if (now - posted >= 250_000_000L) {
                            posted = now
                            state.postValue(progress(tracker, name, attempt))
                        }
                    }
                })
                tracker.finish()
                // The protocol is closed before touching local storage. No card-writing command.
                connection.close()
                state.postValue(State(Step.SAVING, device = name, permille = 1000,
                    filesDone = tracker.filesDone(), filesTotal = tracker.filesTotal()))
                val stored = RoadCrewTachoBluetoothStore.save(getApplication(), bytes, System.currentTimeMillis())
                try {
                    RoadCrewTachoReminder.remember(getApplication(), stored.takenAt / 1000)
                } catch (e: RuntimeException) {
                    // A reminder failure must not misreport an already verified, saved file as lost.
                    PlatformUtil.getLog(RoadCrewTachoBluetoothModel::class.java).warn("Cannot reschedule card reminder", e)
                }
                state.postValue(State(Step.DONE, device = name, permille = 1000, filesDone = tracker.filesDone(),
                    filesTotal = tracker.filesTotal(), secondGeneration = tracker.filesTotal() > RoadCrewTachoVuProgress.FILES_G1,
                    file = stored))
                return
            } catch (e: Exception) {
                if (cancelled) throw e
                if (!RoadCrewTachoBleChoice.again(attempt, tracker.fileId() >= 0, e.message)) throw e
                attempt++
                state.postValue(State(Step.CONNECTING, device = name, attempt = attempt, retrying = true))
                try {
                    Thread.sleep(RoadCrewTachoBleChoice.PAUSE_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("Download cancelled", interrupted)
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun progress(tracker: RoadCrewTachoVuProgress, name: String, attempt: Int) = State(
        step = if (tracker.fileId() < 0) Step.READING_CARD else Step.RECEIVING,
        device = name, attempt = attempt, permille = tracker.permille(), fileId = tracker.fileId(),
        secondGeneration = tracker.secondGeneration(), filePercent = tracker.filePercent(),
        filesDone = tracker.filesDone(), filesTotal = tracker.filesTotal())
}
