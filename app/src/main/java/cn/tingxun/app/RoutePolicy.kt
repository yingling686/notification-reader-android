package cn.tingxun.app

enum class RouteKind { WIRED, USB, BLUETOOTH_MEDIA, BLE_HEADSET, HEARING_AID, BLUETOOTH_CALL, OTHER }
data class RouteEndpoint(val id: Int, val kind: RouteKind, val name: String = "")

object RoutePolicy {
    const val CONFIRM_TIMEOUT_MS = 3000L
    fun mediaHeadphone(kind: RouteKind) = kind !in setOf(RouteKind.BLUETOOTH_CALL, RouteKind.OTHER)
    fun choose(available: List<RouteEndpoint>): RouteEndpoint? = available.filter { mediaHeadphone(it.kind) }
        .minByOrNull { when (it.kind) {
            RouteKind.WIRED -> 0; RouteKind.USB -> 1; RouteKind.BLE_HEADSET -> 2
            RouteKind.BLUETOOTH_MEDIA -> 3; RouteKind.HEARING_AID -> 4; else -> 99
        } }
    // Actual routing is authoritative. A preferred port ID is only a request and can differ.
    fun confirmed(actual: List<RouteEndpoint>, available: List<RouteEndpoint>): Boolean =
        actual.isNotEmpty() && actual.all { mediaHeadphone(it.kind) } && choose(available) != null
    fun unchanged(actual: List<RouteEndpoint>, verifiedIds: Set<Int>) = actual.isNotEmpty() && actual.map { it.id }.toSet() == verifiedIds
}
