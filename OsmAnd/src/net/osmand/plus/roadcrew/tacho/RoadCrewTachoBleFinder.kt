package net.osmand.plus.roadcrew.tacho

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds the tachograph and pairs it the way Tacho Manager does (ROADMAP 411): a short scan
 * that accepts DTCO-<serial> and SE5000-<serial>, and Android's own pairing - the system shows the code and the
 * driver confirms it on the phone and on the tachograph. Nothing here sets a PIN or confirms
 * for the driver.
 */
@SuppressLint("MissingPermission")
object RoadCrewTachoBleFinder {
    /** Tacho Manager scanned 6 s on 11.10.2026; a paired tachograph heard ends it sooner. */
    private const val SCAN_MILLIS = 5000L
    private const val PAIR_MILLIS = 120_000L

    fun bonded(adapter: BluetoothAdapter): List<RoadCrewTachoBleChoice.Candidate> =
        adapter.bondedDevices.orEmpty().map {
            RoadCrewTachoBleChoice.Candidate(it.address, it.name, true, Int.MIN_VALUE)
        }

    @Throws(IOException::class)
    fun scan(adapter: BluetoothAdapter, bonded: List<RoadCrewTachoBleChoice.Candidate>): List<RoadCrewTachoBleChoice.Candidate> {
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth is off")
        val pairedTachographs = bonded.filter { RoadCrewTachoBleChoice.isTachograph(it.name) }
            .map { it.address.uppercase() }.toSet()
        val heard = ConcurrentHashMap<String, RoadCrewTachoBleChoice.Candidate>()
        val enough = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (!RoadCrewTachoBleChoice.isTachograph(name)) return
                val address = result.device.address.uppercase()
                heard[address] = RoadCrewTachoBleChoice.Candidate(address, name, address in pairedTachographs, result.rssi)
                if (address in pairedTachographs) enough.countDown()
            }

            override fun onScanFailed(errorCode: Int) {
                enough.countDown()
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            enough.await(SCAN_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Download cancelled", e)
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) { }
        }
        return heard.values.toList()
    }

    @Throws(IOException::class)
    fun pair(context: Context, device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val outcome = ArrayBlockingQueue<Int>(4)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) outcome.offer(state)
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            if (!device.createBond()) throw IOException("Pairing could not start")
            val state = try {
                outcome.poll(PAIR_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Download cancelled", e)
            } ?: throw IOException("Pairing timed out")
            if (state != BluetoothDevice.BOND_BONDED) throw IOException("Pairing was refused")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
