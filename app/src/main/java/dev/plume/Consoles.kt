package dev.plume

import android.content.SharedPreferences

/** A device seen on an HDMI port over HDMI-CEC: its icon key (see [hdmiIcon]) and the name it gave. */
internal data class SeenDevice(val kind: String, val name: String)

private const val SEEN = "hdmi_seen:"

internal fun encodeSeen(d: SeenDevice) = d.kind + "\n" + d.name

internal fun decodeSeen(value: String?): SeenDevice? {
    val parts = value?.split('\n', limit = 2) ?: return null
    return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) SeenDevice(parts[0], parts[1]) else null
}

/**
 * What each port remembers after [name] names itself on [port]: that device, there. A device with the same name
 * remembered on another port has moved, so that port forgets it. Ports map to devices; nothing else changes.
 */
internal fun updateSeen(seen: Map<String, SeenDevice>, port: String, name: String): Map<String, SeenDevice> {
    val device = SeenDevice(hdmiIcon(name, true), name.trim())
    return seen.filter { (p, d) -> p != port && d.name != device.name } + (port to device)
}

/** The device remembered on HDMI [portId] (a TV input id), if one ever named itself there. */
internal fun SharedPreferences.seenDevice(portId: String): SeenDevice? = decodeSeen(getString(SEEN + portId, null))

/** Remembers [name] on [portId] (see [updateSeen]); writes only when something changes. */
internal fun SharedPreferences.rememberDevice(portId: String, name: String) {
    if (name.isBlank()) return
    val now = all.keys.filter { it.startsWith(SEEN) }
        .mapNotNull { key -> decodeSeen(getString(key, null))?.let { key.removePrefix(SEEN) to it } }.toMap()
    val next = updateSeen(now, portId, name)
    if (next == now) return
    val edit = edit()
    (now.keys - next.keys).forEach { edit.remove(SEEN + it) }
    next.forEach { (port, device) -> if (now[port] != device) edit.putString(SEEN + port, encodeSeen(device)) }
    edit.apply()
}

internal fun SharedPreferences.forgetDevice(portId: String) = edit().remove(SEEN + portId).apply()

/** A console's own colour for its input tile: Xbox green, PlayStation blue, Nintendo red, Apple TV black; else null. */
internal fun consoleAccent(kind: String): Int? = when (kind) {
    "xbox" -> 0xFF107C10.toInt()
    "playstation" -> 0xFF0070D1.toInt()
    "nintendoswitch" -> 0xFFE60012.toInt()
    "appletv" -> 0xFF1D1D1F.toInt()
    else -> null
}
