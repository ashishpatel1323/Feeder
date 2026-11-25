package com.nononsenseapps.feeder.sync2

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * This device, as named on progress synced to other devices ("42% played on Pixel 8, 2h ago").
 * The id is random and never changes, so renaming keeps this device apart from the others. The
 * name is the phone's own name (Settings, About phone) or its model until renamed on the Sync
 * screen. Same fields as the web app's device.ts: positions carry "dv" (id) and "dn" (name).
 */
class ThisDevice private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("this_device", Context.MODE_PRIVATE)

    val id: String =
        prefs.getString(PREF_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit { putString(PREF_ID, it) } }

    private val _name = MutableStateFlow(prefs.getString(PREF_NAME, null)?.takeIf { it.isNotBlank() } ?: autoName())

    /** The name set on the Sync screen, or the phone's own name. */
    val name: StateFlow<String> = _name.asStateFlow()

    /** The phone's name from Settings when it has one, else its model, such as "Pixel 8". */
    fun autoName(): String {
        val own = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.trim()
        if (!own.isNullOrBlank()) return own.take(MAX_NAME)
        val model = Build.MODEL.orEmpty().trim()
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val name = if (maker.isNotEmpty() && !model.startsWith(maker, ignoreCase = true) && maker != "Google") "${maker.replaceFirstChar { it.uppercase() }} $model" else model
        return name.ifBlank { "Android" }.take(MAX_NAME)
    }

    /** Renames this device (blank = back to the phone's name). Positions saved from now on carry it. */
    fun setName(name: String) {
        val n = name.trim().take(MAX_NAME)
        prefs.edit { if (n.isEmpty() || n == autoName()) remove(PREF_NAME) else putString(PREF_NAME, n) }
        _name.value = n.ifEmpty { autoName() }
    }

    /** The stamp for a position saved here now. */
    fun stamp(): DeviceStamp = DeviceStamp(id, _name.value)

    companion object {
        private const val PREF_ID = "id"
        private const val PREF_NAME = "name"
        const val MAX_NAME = 40

        @Volatile
        private var instance: ThisDevice? = null

        fun get(context: Context): ThisDevice =
            instance ?: synchronized(this) {
                instance ?: ThisDevice(context).also { instance = it }
            }
    }
}

/** Which device saved a position: [id] and the [name] it had then. Empty = not known (older versions). */
data class DeviceStamp(
    val id: String,
    val name: String,
) {
    val known: Boolean get() = id.isNotEmpty() && name.isNotEmpty()

    companion object {
        val NONE = DeviceStamp("", "")

        /** The "dv" and "dn" fields of a synced position. */
        fun fromMap(v: Map<*, *>): DeviceStamp {
            val id = v["dv"] as? String ?: ""
            val name = v["dn"] as? String ?: ""
            return if (id.isNotEmpty() && name.isNotEmpty()) DeviceStamp(id, name) else NONE
        }
    }
}

/** Adds "dv" and "dn" to a synced position when [stamp] is known (same field order as the web app). */
fun MutableMap<String, Any?>.putStamp(stamp: DeviceStamp): MutableMap<String, Any?> {
    if (stamp.known) {
        put("dv", stamp.id)
        put("dn", stamp.name)
    }
    return this
}

/** "just now", "5 min ago", "2h ago", "3d ago", or a short date. Same words as the web app. */
fun agoText(
    ts: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 7 * 86_400 -> "${s / 86_400}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
    }
}

/** "on Pixel 8, 2h ago" when another device saved it last, else null (progress made here has no label). */
fun otherDeviceText(
    stamp: DeviceStamp?,
    ts: Long,
    thisDeviceId: String,
    now: Long = System.currentTimeMillis(),
): String? {
    if (stamp == null || !stamp.known || stamp.id == thisDeviceId) return null
    return "on ${stamp.name}, ${agoText(ts, now)}"
}
