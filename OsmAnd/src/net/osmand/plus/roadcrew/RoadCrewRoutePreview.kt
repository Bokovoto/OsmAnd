package net.osmand.plus.roadcrew

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import androidx.annotation.VisibleForTesting
import net.osmand.Location
import net.osmand.data.QuadRect
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.base.ContextMenuFragment

object RoadCrewRoutePreview {

	@JvmStatic
	fun fit(activity: MapActivity) {
		val menu = activity.mapRouteInfoMenu
		val fragment = (menu.findMenuFragment()?.get()
			?: menu.findFollowTrackFragment()?.get()) as? ContextMenuFragment
		fit(activity, fragment, fragment?.mainView?.y?.toInt())
	}

	@JvmStatic
	fun fit(activity: MapActivity, fragment: ContextMenuFragment?, panelY: Int?) {
		val routing = activity.routingHelper
		val mapView = activity.mapView
		if (activity.isFinishing || activity.isDestroyed || !routing.isRoutePlanningMode
			|| !routing.isRouteCalculated || routing.isRouteBeingCalculated
			|| routing.isPublicTransportMode || mapView.isCarView
			|| activity.mapRouteInfoMenu.isSelectFromMap) {
			return
		}
		val bounds = routeBounds(routing.route.immutableAllLocations) ?: return
		val surface = mapView.view ?: return
		val viewport = Rect()
		if (!surface.getGlobalVisibleRect(viewport)) return
		val origin = IntArray(2)
		surface.getLocationOnScreen(origin)
		viewport.offset(-origin[0], -origin[1])
		val current = mapView.rotatedTileBox
		if (!viewport.intersect(0, 0, current.pixWidth, current.pixHeight)) return

		fun exclude(view: View?, edge: Int, targetY: Int? = null) {
			if (view == null || !view.isShown) return
			val rect = Rect()
			if (!view.getGlobalVisibleRect(rect)) return
			// Menu animations report the destination Y before the view reaches it.
			if (targetY != null) rect.offset(0, targetY - view.y.toInt())
			rect.offset(-origin[0], -origin[1])
			excludePanel(viewport, rect, edge)
		}

		exclude(activity.findViewById(R.id.top_widgets_panel), Gravity.TOP)
		exclude(activity.findViewById(R.id.map_bottom_widgets_panel), Gravity.BOTTOM)
		for ((edge, view) in RoadCrewNeonHud.getRoutePreviewPanels(activity)) {
			exclude(view, edge)
		}
		if (fragment != null) {
			val panel = fragment.mainView
			val edge = if (fragment.isPortrait) Gravity.BOTTOM
			else if (surface.layoutDirection == View.LAYOUT_DIRECTION_RTL) Gravity.RIGHT else Gravity.LEFT
			exclude(panel, edge, if (fragment.isPortrait) panelY else null)
		}
		val minSize = (64 * surface.resources.displayMetrics.density).toInt()
		if (viewport.width() < minSize || viewport.height() < minSize) return

		activity.mapViewTrackingUtilities.prepareRoutePreview()
		val tileBox = mapView.rotatedTileBox.copy()
		tileBox.setRotate(0f)
		// The fitting API assumes a left-hand sidebar in LTR mode. Compensate
		// so its effective origin is our measured viewport, also for RTL/rails.
		val marginLeft = viewport.left - (tileBox.pixWidth - viewport.width())
		mapView.fitRectToMap(tileBox, bounds.left, bounds.right, bounds.top, bounds.bottom,
			viewport.width(), viewport.height(), viewport.top, marginLeft, false, 0.8f, true)
	}

	@VisibleForTesting
	internal fun routeBounds(locations: List<Location>): QuadRect? {
		val first = locations.firstOrNull() ?: return null
		val rect = QuadRect(first.longitude, first.latitude, first.longitude, first.latitude)
		for (location in locations) {
			rect.left = minOf(rect.left, location.longitude)
			rect.right = maxOf(rect.right, location.longitude)
			rect.top = maxOf(rect.top, location.latitude)
			rect.bottom = minOf(rect.bottom, location.latitude)
		}
		return rect
	}

	@VisibleForTesting
	internal fun excludePanel(viewport: Rect, panel: Rect, edge: Int) {
		if (!Rect.intersects(viewport, panel)) return
		when (edge) {
			Gravity.TOP -> viewport.top = minOf(viewport.bottom, panel.bottom)
			Gravity.BOTTOM -> viewport.bottom = maxOf(viewport.top, panel.top)
			Gravity.LEFT -> viewport.left = minOf(viewport.right, panel.right)
			Gravity.RIGHT -> viewport.right = maxOf(viewport.left, panel.left)
		}
	}
}
