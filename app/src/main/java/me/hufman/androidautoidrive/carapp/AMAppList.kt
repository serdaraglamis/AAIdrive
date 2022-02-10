package me.hufman.androidautoidrive.carapp

import android.graphics.drawable.Drawable
import android.util.Log
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.RemoteBMWRemotingServer
import me.hufman.androidautoidrive.utils.GraphicsHelpers
import org.apache.etch.bindings.java.support.Mailbox
import kotlin.math.min
import kotlin.math.roundToInt

enum class AMCategory(val value: String) {
	ADDRESSBOOK("Addressbook"),
	MULTIMEDIA("Multimedia"),
	NAVIGATION("Navigation"),
	ONLINE_SERVICES("OnlineServices"),
	PHONE("Phone"),
	RADIO("Radio"),
	SETTINGS("Settings"),
	VEHICLE_INFORMATION("VehicleInformation"),
}

interface AMAppInfo {
	val packageName: String
	val name: String
	val icon: Drawable
	val category: AMCategory

	val amAppIdentifier
		get() = "androidautoidrive.$packageName"

	val weight
		get() = 800 - getAppWeight(this.name)

	companion object {
		fun getAppWeight(appName: String): Int {
			val name = appName.lowercase().toCharArray().filter { it.isLetter() }
			var score = min(name[0].code - 'a'.code, 'z'.code)
			score = score * 6 + ((name[1].code / 6.0).roundToInt())
			return score
		}
	}
}

class ConcreteAMAppInfo(override val packageName: String, override val name: String,
                        override val icon: Drawable, override val category: AMCategory): AMAppInfo

class AMAppList<T: AMAppInfo>(val connection: BMWRemotingServer, val graphicsHelpers: GraphicsHelpers, val amIdent: String) {
	companion object {
		val TAG = "AMAppList"

	}
	private var amHandle: Int = createAm()
	private val knownApps = HashMap<String, T>()        // keyed by amAppIdentifier
	private val pendingApps = HashMap<String, Mailbox>()    // pending async AM creations

	private val MailboxCloser = object: Mailbox.Notify {
		override fun mailboxStatus(mailbox: Mailbox?, appIdentifier: Any?, p2: Boolean) {
			if (mailbox?.isFull == true) {
				mailbox.closeDelivery()
				if (appIdentifier is String) synchronized(pendingApps) {
					pendingApps.remove(appIdentifier)
				}
			}
		}

	}

	fun getAMInfo(app: AMAppInfo): Map<Int, Any> {
		val amInfo = mutableMapOf<Int, Any>(
				0 to 145,   // basecore version
				1 to app.name,  // app name
				2 to graphicsHelpers.compress(app.icon, 48, 48), // icon
				3 to app.category.value,   // section
				4 to true,
				5 to app.weight,   // weight
				8 to -1  // mainstateId
		)
		// language translations, dunno which one is which
		for (languageCode in 101..123) {
			amInfo[languageCode] = app.name
		}

		return amInfo
	}

	/**
	 * When handling an am_onAppEvent, this function
	 * converts the given appId to the original appInfo object
	 */
	fun getAppInfo(appId: String): T? {
		return knownApps[appId]
	}

	/**
	 * Updates the list of displayed apps
	 */
	fun setApps(apps: List<T>) {
		synchronized(connection) {
			// if there are any extra apps, clear out the list
			val updatedApps = apps.map {it.amAppIdentifier to it}.toMap()
			val stillCurrent = knownApps.values.filter { previous ->
				val updated = updatedApps[previous.amAppIdentifier]
				updated != null &&
					previous.name == updated.name &&
					previous.category == updated.category
			}
			if (stillCurrent.size < knownApps.size) {
				reinitAm()
				knownApps.clear()
			}

			// then create all the apps
			for (app in apps) {
				if (!knownApps.containsKey(app.amAppIdentifier)) {
					createApp(app)
					knownApps[app.amAppIdentifier] = app
				}
			}
		}

		// wait for any pending apps to finish
		flushPending()
	}

	fun redrawApp(app: T) {
		if (knownApps.containsKey(app.amAppIdentifier)) {
			createApp(app)
			flushPending()
		}
	}

	private fun reinitAm() {
		synchronized(connection) {
			val oldHandle = amHandle
			connection.am_removeAppEventHandler(oldHandle, amIdent)
			connection.am_dispose(oldHandle)

			val newHandle = createAm()
			amHandle = newHandle
		}
	}

	private fun createAm(): Int {
		return synchronized(connection) {
			val handle = connection.am_create("0", "\u0000\u0000\u0000\u0000\u0000\u0002\u0000\u0000".toByteArray())
			connection.am_addAppEventHandler(handle, amIdent)
			handle
		}
	}

	/**
	 * Creates the given AMAppInfo in the car
	 * If the connection supports async, it will create in the background and save the mailbox to be flushed later
	 */
	private fun createApp(app: T) {
		Log.d(TAG, "Creating am app for app ${app.name}")
		if (connection is RemoteBMWRemotingServer) {
			// register the AM without waiting for a response
			val mailbox = connection._async._begin_am_registerApp(amHandle, app.amAppIdentifier, getAMInfo(app))
			synchronized(pendingApps) {
				pendingApps[app.amAppIdentifier] = mailbox
			}
			mailbox.registerNotify(MailboxCloser, app.amAppIdentifier, 0)
		} else {
			connection.am_registerApp(amHandle, app.amAppIdentifier, getAMInfo(app))
		}
	}

	/** Waits up to 2 seconds for any pending async am_registerApp calls to complete */
	private fun flushPending() {
		Log.i(TAG, "Waiting for ${pendingApps.size} pending apps")
		for (i in 0..20) {
			if (pendingApps.isNotEmpty()) {
				Thread.sleep(100)
			}
		}
		Log.i(TAG, "Finished flushing pending apps")
	}
}