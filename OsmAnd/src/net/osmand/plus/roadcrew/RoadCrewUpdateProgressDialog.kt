package net.osmand.plus.roadcrew

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.roadcrew.RoadCrewAppUpdater.Phase
import java.text.NumberFormat

/** Presentation only. The updater owns the transfer independently of this window. */
class RoadCrewUpdateProgressDialog(
    activity: MapActivity,
    private val onPrimary: Runnable,
    private val onCancel: Runnable
) : Dialog(activity) {
    private val root = LinearLayout(context)
    private val title = text(18f)
    private val status = text(16f)
    private val amount = text(14f)
    private val spinner = ProgressBar(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val endDot = View(context)
    private val primary = button(true, onPrimary)
    private val cancel = button(false, onCancel)
    private var lastPhase: Phase? = null
    private val accent get() = color(R.color.roadcrew_update_progress)
    private val track get() = color(R.color.roadcrew_update_track)

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val poster = ImageView(context).apply {
            setImageResource(R.drawable.roadcrew_update_poster)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.roadcrew_update_poster_description)
        }
        root.addView(poster, if (landscape) LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.25f))

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        root.addView(controls, if (landscape) LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(details, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        controls.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        details.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val statusRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        spinner.indeterminateTintList = ColorStateList.valueOf(accent)
        statusRow.addView(spinner, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        statusRow.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        details.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val startDot = View(context).apply { background = dot(accent) }
        line.addView(startDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        progress.max = 1000
        progress.isIndeterminate = false
        progress.progressTintList = ColorStateList.valueOf(accent)
        progress.progressBackgroundTintList = ColorStateList.valueOf(track)
        line.addView(progress, LinearLayout.LayoutParams(0, dp(8), 1f))
        endDot.background = dot(track)
        line.addView(endDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        details.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, dp(24)).apply { topMargin = dp(12) })
        amount.setTextColor(color(R.color.roadcrew_update_secondary))
        details.addView(amount, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        controls.addView(primary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        controls.addView(cancel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(root)
        window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        ViewCompat.requestApplyInsets(root)
    }

    fun render(phase: Phase, received: Long, total: Long, version: String, error: Int) {
        title.text = version
        val busy = phase == Phase.CONNECTING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING || phase == Phase.CANCELLING
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        if (phase != lastPhase) {
            status.setText(when (phase) {
                Phase.PERMISSION -> R.string.roadcrew_update_permission_needed
                Phase.CONNECTING -> R.string.roadcrew_update_connecting
                Phase.DOWNLOADING -> R.string.roadcrew_update_download_in_progress
                Phase.VERIFYING -> R.string.roadcrew_update_verifying
                Phase.READY -> R.string.roadcrew_update_ready
                Phase.FAILED -> error
                Phase.CANCELLING -> R.string.roadcrew_update_cancelling
            })
            status.setTextColor(if (phase == Phase.FAILED) color(R.color.roadcrew_update_error) else Color.WHITE)
            lastPhase = phase
        }
        val fraction = if (phase == Phase.READY) 1.0 else if (total > 0) (received.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
        progress.progress = (fraction * progress.max).toInt()
        endDot.background = dot(if (phase == Phase.READY) accent else track)
        amount.text = if (total > 0) context.getString(R.string.roadcrew_update_bytes_total,
            NumberFormat.getPercentInstance().format(kotlin.math.floor(fraction * 100) / 100), Formatter.formatShortFileSize(context, received),
            Formatter.formatShortFileSize(context, total))
        else context.getString(R.string.roadcrew_update_bytes_unknown, Formatter.formatShortFileSize(context, received))
        primary.visibility = if (busy) View.GONE else View.VISIBLE
        primary.setText(when (phase) {
            Phase.PERMISSION -> R.string.roadcrew_update_open_settings
            Phase.READY -> R.string.roadcrew_update_install
            else -> R.string.roadcrew_update_retry
        })
        cancel.setText(if (phase == Phase.READY) R.string.roadcrew_button_close else R.string.roadcrew_button_cancel)
        cancel.isEnabled = phase != Phase.CANCELLING
        cancel.alpha = if (cancel.isEnabled) 1f else 0.5f
    }

    private fun text(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(Color.WHITE)
        letterSpacing = 0f
    }

    private fun button(isPrimary: Boolean, action: Runnable) = AppCompatButton(context).apply {
        isAllCaps = false
        textSize = 16f
        letterSpacing = 0f
        setTextColor(Color.WHITE)
        minHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color(if (isPrimary) R.color.roadcrew_update_button else R.color.roadcrew_update_track))
        }
        backgroundTintList = null
        setOnClickListener { action.run() }
    }

    private fun dot(tint: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint) }
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = RoadCrewUi.dp(context, value.toFloat())
}
