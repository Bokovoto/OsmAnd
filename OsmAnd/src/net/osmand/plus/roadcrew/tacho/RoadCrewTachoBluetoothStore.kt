package net.osmand.plus.roadcrew.tacho

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import net.osmand.plus.utils.AndroidUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only a complete, byte-for-byte read-back export becomes visible in download history. */
object RoadCrewTachoBluetoothStore {
    data class Stored(val uri: Uri, val name: String, val takenAt: Long, val size: Int)

    fun save(context: Context, bytes: ByteArray, takenAt: Long): Stored {
        val card = RoadCrewTachoDdd.validateAndGetCardNumber(bytes).ifEmpty { "card" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(takenAt))
        val name = "C_${stamp}_${card}.ddd"
        checkCancelled()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RoadCrew")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create card file")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("Cannot write card file")).use { it.write(bytes) }
                val same = resolver.openInputStream(uri)?.buffered()?.use { input ->
                    bytes.all { expected -> input.read() == (expected.toInt() and 255) } && input.read() == -1
                } ?: false
                if (!same) throw IOException("Card file read-back mismatch")
                checkCancelled()
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) throw IOException("Cannot finish card file")
                val storedName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else name
                } ?: name
                return Stored(uri, storedName, takenAt, bytes.size)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                throw e
            }
        }
        val directory = File(context.getExternalFilesDir(null) ?: throw IOException("Storage unavailable"), "RoadCrew")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create card folder")
        // Partial files cannot appear in the existing C_*.ddd history scan.
        val temporary = File.createTempFile("bluetooth-", ".part", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (!temporary.readBytes().contentEquals(bytes)) throw IOException("Card file read-back mismatch")
            checkCancelled()
            val destination = RoadCrewTachoFileNames.unused(directory, name)
            if (!destination.createNewFile()) throw IOException("Card file already exists")
            if (!temporary.renameTo(destination)) {
                destination.delete()
                throw IOException("Cannot finish card file")
            }
            return Stored(AndroidUtils.getUriForFile(context, destination), destination.name, takenAt, bytes.size)
        } finally { temporary.delete() }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled")
    }
}
