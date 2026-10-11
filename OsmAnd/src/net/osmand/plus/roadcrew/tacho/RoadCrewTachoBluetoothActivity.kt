package net.osmand.plus.roadcrew.tacho

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import net.osmand.plus.R

class RoadCrewTachoBluetoothActivity : ComponentActivity() {
    private lateinit var model: RoadCrewTachoBluetoothModel
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var choose: Button
    private lateinit var settings: Button
    private lateinit var cancel: Button
    private lateinit var remaining: Button
    private lateinit var analysis: Button
    private lateinit var done: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[RoadCrewTachoBluetoothModel::class.java]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(RoadCrewTachoUi.color(context, R.color.roadcrew_tacho_ground))
        }
        root.addView(RoadCrewTachoUi.topBar(this, getString(R.string.roadcrew_tacho_ble_title), "VDO DTCO 4.1"))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = RoadCrewTachoUi.dp(context, 16f)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = RoadCrewTachoUi.text(this, "", 19f, true, RoadCrewTachoUi.color(this, R.color.roadcrew_tacho_text))
        content.addView(status)
        spinner = ProgressBar(this)
        content.addView(spinner, RoadCrewTachoUi.below(this, 16))
        progress = RoadCrewTachoUi.text(this, "", 15f, false, RoadCrewTachoUi.color(this, R.color.roadcrew_tacho_secondary))
        content.addView(progress, RoadCrewTachoUi.below(this, 12))
        choose = button(content, R.string.roadcrew_tacho_ble_choose) { chooseDevice() }
        settings = button(content, R.string.roadcrew_tacho_ble_settings) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { status.setText(R.string.roadcrew_tacho_ble_unavailable) }
        }
        cancel = button(content, R.string.shared_string_cancel) { model.cancel() }
        remaining = button(content, R.string.roadcrew_tacho_remaining_title) {
            model.state.value?.file?.let { RoadCrewTachoRemainingActivity.open(this, it.uri, it.takenAt) }
        }
        analysis = button(content, R.string.roadcrew_tacho_analysis_title) {
            model.state.value?.file?.let { RoadCrewTachoAnalysisActivity.open(this, it.uri, it.takenAt) }
        }
        done = button(content, R.string.roadcrew_tacho_finish) { finish() }
        setContentView(root)
        model.state.observe(this) { render(it) }
    }

    private fun button(parent: LinearLayout, label: Int, action: () -> Unit): Button {
        return Button(this).apply {
            setText(label); isAllCaps = false
            minHeight = RoadCrewTachoUi.dp(context, 56f)
            setPadding(16, 12, 16, 12)
            setTextColor(RoadCrewTachoUi.color(context, R.color.roadcrew_tacho_text))
            background = RoadCrewTachoUi.shape(context, RoadCrewTachoUi.color(context, R.color.roadcrew_tacho_surface), R.color.roadcrew_tacho_line, 8f)
            setOnClickListener { action() }
            parent.addView(this, RoadCrewTachoUi.below(context, 16))
        }
    }

    private fun render(s: RoadCrewTachoBluetoothModel.State) {
        status.setText(s.phase)
        progress.text = when {
            s.error != null -> getString(R.string.roadcrew_tacho_ble_error, s.error)
            s.file != null -> getString(R.string.roadcrew_tacho_ble_saved, s.file.name, s.file.size)
            s.parts > 0 -> getString(R.string.roadcrew_tacho_ble_progress, s.bytes, s.parts)
            else -> ""
        }
        spinner.visibility = if (s.busy) View.VISIBLE else View.GONE
        choose.visibility = if (s.busy) View.GONE else View.VISIBLE
        settings.visibility = choose.visibility
        cancel.visibility = if (s.busy) View.VISIBLE else View.GONE
        cancel.isEnabled = s.canCancel
        remaining.visibility = if (s.file != null) View.VISIBLE else View.GONE
        analysis.visibility = remaining.visibility
        done.visibility = if (!s.busy) View.VISIBLE else View.GONE
        if (s.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @SuppressLint("MissingPermission")
    private fun chooseDevice() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 409)
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                status.setText(R.string.roadcrew_tacho_ble_unavailable); return
            }
            val devices = adapter.bondedDevices.sortedWith(compareBy<BluetoothDevice> { it.name ?: "" }.thenBy { it.address })
            if (devices.isEmpty()) { status.setText(R.string.roadcrew_tacho_ble_no_paired); return }
            val labels = devices.map { "${it.name ?: getString(R.string.roadcrew_tacho_ble_unnamed)}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle(R.string.roadcrew_tacho_ble_choose)
                .setItems(labels) { _, which -> confirm(devices[which]) }
                .setNegativeButton(R.string.shared_string_cancel, null).show()
        } catch (_: SecurityException) { status.setText(R.string.roadcrew_tacho_ble_permission) }
    }

    private fun confirm(device: BluetoothDevice) {
        AlertDialog.Builder(this).setTitle(R.string.roadcrew_tacho_ble_title)
            .setMessage(R.string.roadcrew_tacho_ble_confirm)
            .setPositiveButton(R.string.roadcrew_tacho_ble_parked) { _, _ -> model.start(device) }
            .setNegativeButton(R.string.shared_string_cancel, null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 409) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) chooseDevice()
            else status.setText(R.string.roadcrew_tacho_ble_permission)
        }
    }
}
