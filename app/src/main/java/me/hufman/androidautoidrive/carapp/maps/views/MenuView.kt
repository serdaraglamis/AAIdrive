package me.hufman.androidautoidrive.carapp.maps.views

import android.util.Log
import io.bimmergestalt.idriveconnectkit.rhmi.*
import me.hufman.androidautoidrive.carapp.L
import me.hufman.androidautoidrive.carapp.SettingsToggleList
import me.hufman.androidautoidrive.carapp.maps.FrameUpdater
import me.hufman.androidautoidrive.carapp.maps.MapAppMode
import me.hufman.androidautoidrive.carapp.maps.MapInteractionController

class MenuView(val state: RHMIState, val interaction: MapInteractionController, val frameUpdater: FrameUpdater, val mapAppMode: MapAppMode) {
	companion object {
		val TAG = "MapMenu"
		fun fits(state: RHMIState): Boolean {
			return state is RHMIState.PlainState &&
				state.componentsList.filterIsInstance<RHMIComponent.Label>().isNotEmpty() &&   // show whether currently navigating
				state.componentsList.filterIsInstance<RHMIComponent.List>().size > 2
		}
	}

	val alwaysMenuEntries = listOf(L.MAP_ACTION_VIEWMAP, L.MAP_ACTION_SEARCH)
	val duringNavMenuEntries = listOf(L.MAP_ACTION_RECALC_NAV, L.MAP_ACTION_CLEARNAV)
	val menuEntries = ArrayList<String>()
	val rhmiMenuEntries = object: RHMIModel.RaListModel.RHMIListAdapter<String>(3, menuEntries) {}
	val menuMap = state.componentsList.filterIsInstance<RHMIComponent.List>()[0]
	val mapModel = menuMap.getModel()!!
	val menuList = state.componentsList.filterIsInstance<RHMIComponent.List>()[1]

	val labelSettings: RHMIComponent.Label
	val menuSettings = state.componentsList.filterIsInstance<RHMIComponent.List>()[2]
	val settingsView: SettingsToggleList = SettingsToggleList(menuSettings, mapAppMode.appSettings, mapAppMode.settings, 149)

	init {
		val listIndex = state.componentsList.indexOf(menuSettings)
		labelSettings = state.componentsList.filterIndexed { index, rhmiComponent ->
			index < listIndex && rhmiComponent is RHMIComponent.Label
		}.filterIsInstance<RHMIComponent.Label>().last()
	}
	fun initWidgets(stateMap: RHMIState, stateInput: RHMIState) {
		mapAppMode.appSettings.callback = {
			settingsView.redraw()
		}
		state.componentsList.forEach {
			it.setVisible(false)
		}
		redrawCommands()

		state.focusCallback = FocusCallback { focused ->
			if (focused) {
				redrawCommands()
				Log.i(TAG, "Showing map on menu")
				frameUpdater.showWindow(350, 90, mapModel)
			} else {
				Log.i(TAG, "Hiding map on menu")
				frameUpdater.hideWindow(mapModel)
			}
		}

		menuMap.setVisible(true)
		menuMap.setSelectable(true)
		menuMap.setProperty(RHMIProperty.PropertyId.LIST_COLUMNWIDTH.id, "350,0,*")
		menuMap.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = stateMap.id

		menuList.setProperty(RHMIProperty.PropertyId.LIST_COLUMNWIDTH.id, "100,0,*")
		menuList.setVisible(true)
		menuList.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback {  listIndex ->
			val destStateId = when (listIndex) {
				0 -> stateMap.id    // must be index 0, because it's also index 0 in menuMap
				1 -> stateInput.id
				else -> state.id
			}
			Log.i(TAG, "User pressed menu item $listIndex ${menuEntries.getOrNull(listIndex)}, setting target ${menuList.getAction()?.asHMIAction()?.getTargetModel()?.id} to $destStateId")
			menuList.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = destStateId
			if (listIndex== 2) {
				// recalculate nav
				interaction.recalcNavigation()
			}
			if (listIndex == 3) {
				// clear navigation
				interaction.stopNavigation()
				// the interaction is async, but we trust that it will clear the destination so we can redraw to hide the commands
				mapAppMode.currentNavDestination = null
				redrawCommands()
			}
		}
		// it seems that menuMap and menuList share the same HMI Action values, so use the same RA handler
		menuMap.getAction()?.asRAAction()?.rhmiActionCallback = menuList.getAction()?.asRAAction()?.rhmiActionCallback

		// decorate the settings
		labelSettings.setVisible(true)
		labelSettings.getModel()?.asRaDataModel()?.value = L.MAP_OPTIONS

		settingsView.initWidgets()
	}

	fun redrawCommands() {
		menuEntries.clear()
		menuEntries.addAll(alwaysMenuEntries)
		if (mapAppMode.currentNavDestination != null) {
			menuEntries.addAll(duringNavMenuEntries)
		}
		menuList.getModel()?.setValue(rhmiMenuEntries,0, menuEntries.size, menuEntries.size)
	}
}