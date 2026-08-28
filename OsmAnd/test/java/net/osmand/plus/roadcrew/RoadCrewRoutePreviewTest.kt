package net.osmand.plus.roadcrew

import android.graphics.Rect
import android.view.Gravity
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.osmand.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoadCrewRoutePreviewTest {

	@Test
	fun portraitPreviewUsesSpaceBetweenHeaderAndRouteSheet() {
		val viewport = Rect(0, 0, 1080, 2200)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(20, 10, 1060, 150), Gravity.TOP)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(0, 2020, 1080, 2200), Gravity.BOTTOM)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(0, 920, 1080, 2200), Gravity.BOTTOM)
		assertEquals(Rect(0, 150, 1080, 920), viewport)
	}

	@Test
	fun landscapePreviewAccountsForSidebarAndOppositeRail() {
		val viewport = Rect(0, 0, 2200, 1080)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(10, 20, 2190, 130), Gravity.TOP)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(0, 0, 720, 1080), Gravity.LEFT)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(2040, 200, 2190, 900), Gravity.RIGHT)
		assertEquals(Rect(720, 130, 2040, 1080), viewport)
	}

	@Test
	fun rtlSidebarLeavesTheMapOnItsLeft() {
		val viewport = Rect(0, 0, 2200, 1080)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(1480, 0, 2200, 1080), Gravity.RIGHT)
		assertEquals(Rect(0, 0, 1480, 1080), viewport)
	}

	@Test
	fun overlappingOrOffscreenPanelsDoNotExpandViewport() {
		val viewport = Rect(0, 160, 1080, 1000)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(0, 0, 1080, 150), Gravity.TOP)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(0, 1200, 1080, 2200), Gravity.BOTTOM)
		assertEquals(Rect(0, 160, 1080, 1000), viewport)
	}

	@Test
	fun fullScreenSheetLeavesNoAreaForFitting() {
		val viewport = Rect(0, 160, 1080, 2200)
		RoadCrewRoutePreview.excludePanel(viewport, Rect(0, 0, 1080, 2200), Gravity.BOTTOM)
		assertTrue(viewport.isEmpty)
	}

	@Test
	fun boundsIncludeTheDetourNotJustStartAndDestination() {
		val route = listOf(location(43.25, 26.57), location(42.70, 23.32), location(43.22, 27.92))
		val bounds = RoadCrewRoutePreview.routeBounds(route)!!
		assertEquals(23.32, bounds.left, 0.000001)
		assertEquals(27.92, bounds.right, 0.000001)
		assertEquals(43.25, bounds.top, 0.000001)
		assertEquals(42.70, bounds.bottom, 0.000001)
	}

	@Test
	fun missingRouteDoesNotProduceAnUnrelatedMapCenter() {
		assertNull(RoadCrewRoutePreview.routeBounds(emptyList()))
	}

	private fun location(lat: Double, lon: Double) = Location("route-preview-test").apply {
		latitude = lat
		longitude = lon
	}
}
