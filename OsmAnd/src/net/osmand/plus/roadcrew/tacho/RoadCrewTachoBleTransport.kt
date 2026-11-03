package net.osmand.plus.roadcrew.tacho

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import net.osmand.PlatformUtil
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One worker thread owns GATT operations; callbacks only enqueue bounded events. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class RoadCrewTachoBleTransport : RoadCrewTachoVuDownload.Channel, Closeable {
    companion object {
        // Shared ITS download UUIDs; also published in Stoneridge's Smart 2 transport specification.
        val SERVICE: UUID = UUID.fromString("eef90782-55dd-4388-b80b-695aba7a69b5")
        val DATA: UUID = UUID.fromString("29d3a479-1592-47df-80a4-afa742d369bb")
        val CREDIT: UUID = UUID.fromString("db9c4128-bff3-41fe-a306-fb6f9a8aeb2d")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private data class Event(val kind: String, val status: Int = 0, val id: UUID? = null,
                             val bytes: ByteArray = byteArrayOf(), val number: Int = 0)
    private val events = ArrayBlockingQueue<Event>(128)
    private val frames = ArrayDeque<ByteArray>()
    private val assembler = RoadCrewTachoVuDownload.Assembler()
    @Volatile private var failure: String? = null
    @Volatile private var closed = false
    @Volatile private var gatt: BluetoothGatt? = null
    private lateinit var data: BluetoothGattCharacteristic
    private lateinit var credit: BluetoothGattCharacteristic
    private var mtu = 23
    private val credits = RoadCrewTachoVuDownload.Credits()
    private var lastFrameAt = 0L

    private fun offer(event: Event) {
        if (!closed && !events.offer(event)) failure = "Bluetooth receive buffer full"
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Preserve callback order: the final C2 may already be queued before disconnect.
                offer(Event("disconnected", status))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) offer(Event("connected"))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = offer(Event("services", status))
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = offer(Event("mtu", status, number = mtu))
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            offer(Event("descriptor", status, descriptor.characteristic.uuid))
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            offer(Event("write", status, characteristic.uuid))
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
            offer(Event("value", id = characteristic.uuid, bytes = characteristic.value?.clone() ?: byteArrayOf()))
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            offer(Event("value", id = characteristic.uuid, bytes = value.clone()))
    }

    @Throws(IOException::class)
    fun connect(context: Context, device: BluetoothDevice) {
        checkOpen()
        // Pairing is performed by Android and confirmed by the driver on the tachograph, never bypassed.
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw IOException("Pair the tachograph first")
        val connected = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not open Bluetooth")
        synchronized(this) {
            if (closed) { connected.close(); throw IOException("Download cancelled") }
            gatt = connected
        }
        try {
            await("connected", null, 15_000)
            if (!connected.discoverServices()) throw IOException("Could not discover Bluetooth services")
            await("services", null, 15_000)
            val service = connected.getService(SERVICE) ?: throw IOException("Unsupported tachograph Bluetooth profile")
            data = service.getCharacteristic(DATA) ?: throw IOException("Download characteristic missing")
            credit = service.getCharacteristic(CREDIT) ?: throw IOException("Credit characteristic missing")
            for (c in listOf(data, credit)) {
                configureCharacteristic(c)
            }
            if (!connected.requestMtu(240)) throw IOException("Could not negotiate Bluetooth MTU")
            mtu = await("mtu", null, 10_000).number
            if (mtu < 23) throw IOException("Invalid Bluetooth MTU")
            for (c in listOf(data, credit)) {
                if (!connected.setCharacteristicNotification(c, true)) throw IOException("Could not enable indications")
                val descriptor = c.getDescriptor(CCCD)
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (!connected.writeDescriptor(descriptor)) throw IOException("Could not subscribe to download")
                await("descriptor", c.uuid, 10_000)
            }
            grant()
        } catch (e: Exception) {
            close()
            if (e is IOException) throw e
            throw IOException("Bluetooth setup failed", e)
        }
    }

    @Throws(IOException::class)
    override fun send(frame: ByteArray) {
        checkOpen()
        val delay = 10_000_000L - (System.nanoTime() - lastFrameAt)
        if (delay > 0) try { TimeUnit.NANOSECONDS.sleep(delay) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw IOException("Download cancelled", e)
        }
        for (segment in RoadCrewTachoVuDownload.segment(frame, mtu)) {
            replenish()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!credits.maySend()) {
                dispatch(next(deadline))
                replenish()
            }
            credits.sent()
            write(data, segment)
        }
    }

    @Throws(IOException::class)
    override fun receive(timeoutMillis: Long): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            checkOpen()
            replenish()
            if (frames.isNotEmpty()) return frames.removeFirst()
            dispatch(next(deadline))
        }
    }

    private fun replenish() {
        if (credits.grantDue()) grant()
    }

    // The next data write waits until the unit answers this grant (Credits, ROADMAP 410).
    // Marked before the write, so an answer that beats the write's own callback still counts.
    private fun grant() {
        credits.granted()
        write(credit, byteArrayOf(RoadCrewTachoVuDownload.Credits.WINDOW.toByte()))
    }

    private fun configureCharacteristic(c: BluetoothGattCharacteristic) {
        val properties = c.properties
        val hasCccd = c.getDescriptor(CCCD) != null
        val role = if (c.uuid == DATA) "FIFO" else "credits"
        // Capabilities only: no device address, name or card data in the diagnostic.
        val details = "$role properties=0x${Integer.toHexString(properties)} cccd=$hasCccd"
        PlatformUtil.getLog(RoadCrewTachoBleTransport::class.java).info("Tachograph GATT $details")
        val withoutResponse = try {
            RoadCrewTachoVuDownload.useWriteWithoutResponse(
                properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                hasCccd,
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
        } catch (e: IOException) {
            throw IOException("${e.message} ($details)", e)
        }
        c.writeType = if (withoutResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    }

    private fun write(c: BluetoothGattCharacteristic, bytes: ByteArray) {
        checkOpen()
        c.value = bytes
        if (gatt?.writeCharacteristic(c) != true) throw IOException("Bluetooth write could not start")
        await("write", c.uuid, 10_000)
    }

    private fun await(kind: String, id: UUID?, timeout: Long): Event {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (true) {
            val event = next(deadline)
            if (event.kind == kind && event.id == id) {
                if (event.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth $kind failed (${event.status})")
                return event
            }
            dispatch(event)
        }
    }

    private fun dispatch(event: Event) {
        if (event.kind == "disconnected") throw IOException("Bluetooth disconnected (${event.status})")
        if (event.kind != "value") throw IOException("Unexpected Bluetooth operation: ${event.kind}")
        when (event.id) {
            CREDIT -> {
                if (event.bytes.size != 1) throw IOException("Invalid Bluetooth credit")
                credits.unitIndicated(event.bytes[0].toInt() and 255)
            }
            DATA -> {
                credits.received()
                val frame = assembler.accept(event.bytes)
                if (frame != null) {
                    if (frames.size >= 32) throw IOException("Bluetooth frame queue full")
                    frames.addLast(frame); lastFrameAt = System.nanoTime()
                }
            }
            else -> throw IOException("Unexpected Bluetooth characteristic")
        }
    }

    private fun next(deadline: Long): Event {
        while (true) {
            checkOpen()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("Bluetooth response timed out")
            try {
                val event = events.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS)
                if (event != null) { checkOpen(); return event }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("Download cancelled", e)
            }
        }
    }

    private fun checkOpen() {
        if (closed || Thread.currentThread().isInterrupted) throw IOException("Download cancelled")
        failure?.let { throw IOException(it) }
    }

    @Synchronized override fun close() {
        closed = true
        try { gatt?.disconnect() } catch (_: SecurityException) { }
        try { gatt?.close() } catch (_: SecurityException) { }
        gatt = null
    }
}
