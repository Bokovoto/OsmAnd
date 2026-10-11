package net.osmand.plus.roadcrew.tacho

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import net.osmand.PlatformUtil

/**
 * The Bluetooth card download behind the one "Свали картата" button (ROADMAP 410-411): find the
 * tachograph (RoadCrewTachoBleChoice), pair it through Android if it is new, try a dropped start
 * again, and say which part of the card is coming. Survives rotation without holding the screen.
 */
class RoadCrewTachoBluetoothModel(app: Application) : AndroidViewModel(app) {
    enum class Step { IDLE, SEARCHING, CHOOSING, PAIRING, CONNECTING, READING_CARD, RECEIVING, SAVING, DONE, FAILED, CANCELLED }

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
        val choices: List<RoadCrewTachoBleChoice.Candidate> = emptyList(),
        val error: String? = null,
        val file: RoadCrewTachoBluetoothStore.Stored? = null,
    ) {
        val busy: Boolean get() = step in setOf(Step.SEARCHING, Step.PAIRING, Step.CONNECTING,
            Step.READING_CARD, Step.RECEIVING, Step.SAVING)
    }

    val state = MutableLiveData(State())
    private var worker: Thread? = null
    @Volatile private var transport: RoadCrewTachoBleTransport? = null
    @Volatile private var cancelled = false
    /** Address and name of the tachograph this run reached, for "Опитай пак". */
    @Volatile private var chosen: Pair<String, String>? = null

    /** Find the tachograph near the phone, pair it if it is new, and download. */
    @Synchronized fun start() = launch { search() }

    /** The driver picked one of several unpaired tachographs (Step.CHOOSING). */
    @Synchronized fun startWith(address: String) = launch {
        val adapter = adapter() ?: throw java.io.IOException("Bluetooth is off")
        prepareAndDownload(adapter.getRemoteDevice(address))
    }

    /** "Опитай пак": the tachograph chosen before, straight away and without a new search. */
    @Synchronized fun retry() {
        val address = state.value?.address
        if (address != null) startWith(address) else start()
    }

    @Synchronized fun reset() {
        if (worker?.isAlive != true) state.value = State()
    }

    @Synchronized fun cancel() {
        cancelled = true
        transport?.close()
        worker?.interrupt()
    }

    override fun onCleared() { cancel() }

    private fun launch(job: () -> Unit) {
        if (worker?.isAlive == true) return
        cancelled = false
        chosen = null
        state.value = State(Step.SEARCHING)
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

    private fun search() {
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
        val bonded = RoadCrewTachoBleFinder.bonded(adapter)
        val heard = RoadCrewTachoBleFinder.scan(adapter, bonded)
        val decision = RoadCrewTachoBleChoice.decide(bonded, heard)
        when (decision.kind) {
            RoadCrewTachoBleChoice.Kind.USE -> prepareAndDownload(adapter.getRemoteDevice(decision.candidates[0].address))
            RoadCrewTachoBleChoice.Kind.ASK -> state.postValue(State(Step.CHOOSING, choices = decision.candidates))
            RoadCrewTachoBleChoice.Kind.NONE -> throw java.io.IOException("No tachograph nearby")
        }
    }

    private fun prepareAndDownload(device: BluetoothDevice) {
        val name = device.name ?: device.address
        chosen = device.address to name
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            state.postValue(State(Step.PAIRING, device = name))
            RoadCrewTachoBleFinder.pair(getApplication(), device)
        }
        download(device, name)
    }

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
