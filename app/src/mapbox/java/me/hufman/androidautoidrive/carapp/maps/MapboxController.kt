package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ResourceOptionsManager
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import me.hufman.androidautoidrive.AppSettingsObserver
import me.hufman.androidautoidrive.BuildConfig
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import kotlin.math.max
import kotlin.math.min

class MapboxController(private val context: Context,
                       private val carLocationProvider: CarLocationProvider,
                       private val virtualDisplay: VirtualDisplay,
                       private val appSettings: AppSettingsObserver,
                       private val mapAppMode: MapAppMode): MapInteractionController {

	private val SHUTDOWN_WAIT_INTERVAL = 120000L   // milliseconds of inactivity before shutting down map

	var handler = Handler(Looper.getMainLooper())
	var projection: MapboxProjection? = null

	val navController = MapboxNavController.getInstance(carLocationProvider) {
		drawNavigation()
		mapAppMode.currentNavDestination = it.currentNavDestination
	}
	private val mapboxLocationSource = MapboxLocationSource()
	var currentLocation: Location? = null
	var animatingCamera = false
	private val scrollZoomAnimation = MapAnimationOptions.Builder().duration(1000).build()
	private var startZoom = 6f  // what zoom level we start the projection with
	private var currentZoom = 15f

	init {
		ResourceOptionsManager.getDefault(context.applicationContext, BuildConfig.MapboxAccessToken)

		carLocationProvider.callback = { location ->
			handler.post {
				onLocationUpdate(location)
			}
		}
	}

	private fun onLocationUpdate(location: Location) {
		val firstView = currentLocation == null
		currentLocation = location
		// move the map dot to the new location
		mapboxLocationSource.onLocationUpdate(location)

		if (firstView) {  // first view
			initCamera()
		} else {
			updateCamera()
		}
	}

	override fun showMap() {
		// cancel a shutdown timer
		handler.removeCallbacks(shutdownMapRunnable)

		if (projection == null) {
			Log.i(TAG, "First showing of the map")
			this.projection = MapboxProjection(context, virtualDisplay.display, appSettings, mapboxLocationSource).apply {
				mapListener = Runnable {
					drawNavigation()
				}
			}
		}

		if (projection?.isShowing == false) {
			projection?.show()
		}

		drawNavigation()

		// nudge the camera to trigger a redraw, in case we changed windows
		if (!animatingCamera) {
			projection?.map?.camera?.moveBy(ScreenCoordinate(1.0, 1.0))
		}
		// register for location updates
		carLocationProvider.start()
	}

	override fun pauseMap() {
		carLocationProvider.stop()

		handler.postDelayed(shutdownMapRunnable, SHUTDOWN_WAIT_INTERVAL)
	}
	private val shutdownMapRunnable = Runnable {
		Log.i(TAG, "Shutting down MapboxProjection due to inactivity of ${SHUTDOWN_WAIT_INTERVAL}ms")
		projection?.hide()
		projection = null
	}

	override fun zoomIn(steps: Int) {
		mapAppMode.startInteraction()
		currentZoom = min(20f, currentZoom + steps)
		updateCamera()
	}

	override fun zoomOut(steps: Int) {
		mapAppMode.startInteraction()
		currentZoom = max(0f, currentZoom - steps)
		updateCamera()
	}

	private fun initCamera() {
		// set the camera to the starting position
		mapAppMode.startInteraction()
		val location = currentLocation
		if (location != null) {
			val cameraPosition = CameraOptions.Builder()
					.center(Point.fromLngLat(location.longitude, location.latitude))
					.zoom(startZoom.toDouble())
					.build()
			projection?.map?.getMapboxMap()?.setCamera(cameraPosition)
		}
	}

	private fun updateCamera() {
		if (animatingCamera) {
			return
		}
		val location = currentLocation ?: return
		val cameraPosition = CameraOptions.Builder()
				.center(Point.fromLngLat(location.longitude, location.latitude))
				.zoom(currentZoom.toDouble())
				.build()
		projection?.map?.camera?.flyTo(cameraPosition, scrollZoomAnimation)
	}

	override fun navigateTo(dest: LatLong) {
		mapAppMode.startInteraction(NAVIGATION_MAP_STARTZOOM_TIME + 4000)
		navController.navigateTo(dest)
		animateNavigation()
	}

	override fun recalcNavigation() {
		navController.currentNavDestination?.let {
			navController.navigateTo(it)
		}
	}

	override fun stopNavigation() {
		navController.stopNavigation()
	}

	private fun animateNavigation() {
		// show a camera animation to zoom out to the whole navigation route
		val dest = navController.currentNavDestination ?: return
		val startLocation = currentLocation ?: return
		// zoom out to the full view
		val startPoint = Point.fromLngLat(startLocation.longitude, startLocation.latitude)
		val destPoint = Point.fromLngLat(dest.longitude, dest.latitude)

		val camera = projection?.map?.getMapboxMap()?.cameraState?.let {
			CameraOptions.Builder()
					.center(it.center)
					.zoom(it.zoom)
					.bearing(it.bearing)
					.pitch(it.pitch)
					.padding(it.padding)
					.build()
		} ?: return
		val navZoomAnimation = MapAnimationOptions.Builder().duration(3000).build()
		val currentVisibleRegion = projection?.map?.getMapboxMap()?.coordinateBoundsForCamera(camera)
		if (currentVisibleRegion == null || !currentVisibleRegion.contains(startPoint, false) || !currentVisibleRegion.contains(destPoint, false)) {
			animatingCamera = true
			handler.postDelayed({
				val cameraPosition = projection?.map?.getMapboxMap()?.cameraForCoordinates(listOf(
						startPoint,
						destPoint
				), EdgeInsets(150.0, 100.0, 100.0, 100.0))
				if (cameraPosition != null) {
					projection?.map?.camera?.flyTo(cameraPosition, navZoomAnimation)
				}
			}, 100)
		}

		// then zoom back in to the user's chosen zoom
		handler.postDelayed({
			animatingCamera = false
			val location = currentLocation ?: return@postDelayed
			val cameraPosition = CameraOptions.Builder()
					.center(Point.fromLngLat(location.longitude, location.latitude))
					.zoom(currentZoom.toDouble())
					.build()
			projection?.map?.camera?.flyTo(cameraPosition, navZoomAnimation)
		}, NAVIGATION_MAP_STARTZOOM_TIME.toLong())
	}

	private fun drawNavigation() {
		// make sure we are in the UI thread, and then draw navigation lines onto it
		// because route search comes back on a network thread
		if (Looper.myLooper() != handler.looper) {
			handler.post {
				projection?.drawNavigation(navController)
			}
		} else {
			projection?.drawNavigation(navController)
		}
	}
}