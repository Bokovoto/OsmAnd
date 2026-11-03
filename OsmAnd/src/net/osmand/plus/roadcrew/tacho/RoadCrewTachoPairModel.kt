package net.osmand.plus.roadcrew.tacho

import android.app.Application
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

/**
 * The "Тахограф" section (ROADMAP 439; Galin, 03.11.2026): the phone's paired tachographs and which of
 * them are near, and "Нов тахограф" - a wizard in four steps, in the order the tachograph needs them:
 * its data allowed ("Данни от VDO", "ИТС издаване"), its Bluetooth connecting turned on, the
 * tachograph picked from the ones not paired yet, the code confirmed. Pairing starts only after the
 * driver has said the tachograph waits - never from "Свали картата", never as a failed attempt that
 * explains itself afterwards. Survives rotation; a result reaches only the step that asked for it.
 */
class RoadCrewTachoPairModel(app: Application) : AndroidViewModel(app) {
    enum class Step { LIST, DATA, CONNECT, SEARCHING, CHOOSE, PAIRING, PAIRED, FAILED }

    data class State(
        val step: Step = Step.LIST,
        /** The list's scan is running; the wizard's own search is Step.SEARCHING. */
        val scanning: Boolean = false,
        /** The phone's paired tachographs, the heard ones first (RoadCrewTachoBleChoice.pairedWithSignal). */
        val paired: List<RoadCrewTachoBleChoice.Candidate> = emptyList(),
        /** Step 3: tachographs heard that the phone has not paired, nearest first. */
        val found: List<RoadCrewTachoBleChoice.Candidate> = emptyList(),
        /** Step 4: the one being paired. */
        val device: RoadCrewTachoBleChoice.Candidate? = null,
        val error: String? = null,
    )

    val state = MutableLiveData(State())
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    /** The section opens: every paired tachograph, and which of them are heard now. */
    @Synchronized fun scanList() {
        val now = state.value ?: State()
        if (now.step != Step.LIST || worker?.isAlive == true) return
        state.value = now.copy(scanning = true, error = null)
        worker = Thread({
            var paired = emptyList<RoadCrewTachoBleChoice.Candidate>()
            var error: String? = null
            try {
                val adapter = adapter()
                if (adapter == null || !adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
                val bonded = RoadCrewTachoBleFinder.bonded(adapter)
                paired = RoadCrewTachoBleChoice.pairedTachographs(bonded)
                val heard = RoadCrewTachoBleFinder.scan(adapter, bonded)
                paired = RoadCrewTachoBleChoice.pairedWithSignal(bonded, heard)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            main.post { val s = state.value ?: State(); state.value = s.copy(scanning = false, paired = paired,
                error = if (s.step == Step.LIST) error else s.error) }
        }, "RoadCrewTachoPairList").apply { start() }
    }

    /** "Нов тахограф": step 1, the tachograph's data. */
    fun startNew() = move(Step.DATA)

    /** Step 1 "Продължи": the data is allowed, now its Bluetooth connecting. */
    fun dataDone() {
        if (state.value?.step == Step.DATA) move(Step.CONNECT)
    }

    /**
     * Step 2 "Продължи" (the tachograph waits for the phone) and step 3 "Търси пак": the tachographs
     * near the phone that it has not paired.
     */
    @Synchronized fun searchNew() {
        val now = state.value ?: State()
        if (now.step != Step.CONNECT && now.step != Step.CHOOSE) return
        state.value = now.copy(step = Step.SEARCHING, found = emptyList(), error = null)
        val previous = worker
        worker = Thread({
            var found = emptyList<RoadCrewTachoBleChoice.Candidate>()
            var error: String? = null
            var stopped = false
            try {
                // The list's own scan first: one scan at a time.
                previous?.join()
                val adapter = adapter()
                if (adapter == null || !adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
                val bonded = RoadCrewTachoBleFinder.bonded(adapter)
                val heard = RoadCrewTachoBleFinder.scan(adapter, bonded)
                found = RoadCrewTachoBleChoice.unpaired(bonded, heard)
            } catch (e: InterruptedException) {
                stopped = true
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            if (!stopped) main.post { val s = state.value ?: State()
                if (s.step == Step.SEARCHING) state.value = s.copy(step = Step.CHOOSE, found = found, error = error) }
        }, "RoadCrewTachoPairSearch").apply { start() }
    }

    /**
     * Step 3: the driver picked his tachograph - Android's own pairing, the code shown on the phone and
     * confirmed on both. Nothing here sets a PIN or confirms for the driver (RoadCrewTachoBleFinder.pair).
     */
    @Synchronized fun pair(address: String) {
        val now = state.value ?: State()
        if (now.step != Step.CHOOSE) return
        val device = now.found.firstOrNull { it.address.equals(address, ignoreCase = true) } ?: return
        state.value = now.copy(step = Step.PAIRING, device = device, error = null)
        worker = Thread({
            var error: String? = null
            try {
                val adapter = adapter() ?: throw java.io.IOException("Bluetooth is off")
                RoadCrewTachoBleFinder.pair(getApplication(), adapter.getRemoteDevice(device.address))
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            main.post { val s = state.value ?: State()
                if (s.step == Step.PAIRING) state.value = s.copy(step = if (error == null) Step.PAIRED else Step.FAILED, error = error) }
        }, "RoadCrewTachoPair").apply { start() }
    }

    /** "Опитай пак" after a failed pairing: back to step 2, the tachograph's connecting. */
    fun again() {
        if (state.value?.step == Step.FAILED) move(Step.CONNECT)
    }

    /** "Назад": one step back; false on the list, where the section closes. */
    fun back(): Boolean {
        val step = state.value?.step ?: Step.LIST
        when (step) {
            Step.LIST, Step.PAIRED -> return false
            Step.DATA -> move(Step.LIST)
            Step.CONNECT -> move(Step.DATA)
            else -> move(Step.CONNECT)
        }
        return true
    }

    /** "Откажи": back to the list. A pairing Android already shows finishes or fails there by itself. */
    fun cancel() = move(Step.LIST)

    override fun onCleared() { worker?.interrupt() }

    private fun move(step: Step) {
        val now = state.value ?: State()
        state.value = now.copy(step = step, error = null,
            found = if (step == Step.CHOOSE) now.found else emptyList(),
            device = if (step == Step.PAIRING || step == Step.FAILED || step == Step.PAIRED) now.device else null)
        if (step == Step.LIST) scanList()
    }

    private fun adapter() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
}
