package net.osmand.plus.roadcrew;

import static net.osmand.router.GeneralRouter.VEHICLE_HEIGHT;
import static net.osmand.router.GeneralRouter.VEHICLE_LENGTH;
import static net.osmand.router.GeneralRouter.VEHICLE_WEIGHT;
import static net.osmand.router.GeneralRouter.VEHICLE_WIDTH;

import android.content.Context;
import android.location.LocationManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RoutingHelperUtils;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.router.GeneralRouter;
import net.osmand.router.GeneralRouter.RoutingParameter;
import net.osmand.util.Algorithms;

import java.util.Map;

final class RoadCrewSetupStatus {

	private static final String[] REQUIRED_TRUCK_PARAMETERS = {
			VEHICLE_HEIGHT,
			VEHICLE_WIDTH,
			VEHICLE_WEIGHT,
			VEHICLE_LENGTH
	};

	private RoadCrewSetupStatus() {
	}

	static boolean isLocationPermissionReady(@NonNull Context context) {
		return OsmAndLocationProvider.isLocationPermissionAvailable(context);
	}

	static boolean isDeviceLocationReady(@NonNull Context context) {
		LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		if (locationManager == null) {
			return false;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			return locationManager.isLocationEnabled();
		}
		try {
			return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
					|| locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (Exception e) {
			return false;
		}
	}

	static boolean areNotificationsReady(@NonNull Context context) {
		return AndroidUtils.hasPostNotificationPermission(context)
				&& NotificationManagerCompat.from(context).areNotificationsEnabled();
	}

	static boolean isOfflineMapReady(@NonNull OsmandApplication app) {
		return app.getResourceManager().isAnyMapInstalled();
	}

	static boolean isDriverProfileReady(@NonNull OsmandApplication app) {
		RoadCrewDriverProfile profile = RoadCrewDriverProfile.load(app);
		return !profile.getDisplayName().isEmpty()
				&& profile.hasPlateIdentity()
				&& profile.isPlateAlertsEnabled();
	}

	static boolean areTruckVehicleParametersReady(@NonNull OsmandApplication app) {
		GeneralRouter router = app.getRouter(ApplicationMode.TRUCK);
		if (router == null) {
			return false;
		}
		Map<String, RoutingParameter> parameters = RoutingHelperUtils.getParametersForDerivedProfile(
				ApplicationMode.TRUCK, router);
		for (String parameterId : REQUIRED_TRUCK_PARAMETERS) {
			RoutingParameter parameter = parameters.get(parameterId);
			if (parameter == null) {
				return false;
			}
			String value = app.getSettings()
					.getCustomRoutingProperty(parameterId, parameter.getDefaultString())
					.getModeValue(ApplicationMode.TRUCK);
			if (!isPositiveNumber(value)) {
				return false;
			}
		}
		return true;
	}

	static boolean isPhoneSetupReady(@NonNull Context context, @NonNull OsmandApplication app) {
		return isLocationPermissionReady(context)
				&& isDeviceLocationReady(context)
				&& areNotificationsReady(context)
				&& isOfflineMapReady(app);
	}

	private static boolean isPositiveNumber(String value) {
		if (Algorithms.isEmpty(value) || "-".equals(value)) {
			return false;
		}
		try {
			return Double.parseDouble(value) > 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
