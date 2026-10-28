package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test

class RoutePolicyTest {
    private val sco = RouteEndpoint(1, RouteKind.BLUETOOTH_CALL, "同一副耳机的通话通道")
    private val a2dp = RouteEndpoint(2, RouteKind.BLUETOOTH_MEDIA, "同一副耳机的媒体通道")
    @Test fun prefersMediaEvenWhenCallProfileIsEnumeratedFirst() {
        assertEquals(a2dp, RoutePolicy.choose(listOf(sco, a2dp)))
    }
    @Test fun callOnlyConnectionCannotBeUsedForMediaReading() {
        assertNull(RoutePolicy.choose(listOf(sco)))
        assertFalse(RoutePolicy.confirmed(listOf(sco), listOf(sco)))
    }
    @Test fun actualHeadphonePortDoesNotHaveToEqualPreferredPort() {
        val actual = a2dp.copy(id = 99)
        assertTrue(RoutePolicy.confirmed(listOf(actual), listOf(sco, a2dp)))
    }
    @Test fun connectedHeadphonesDoNotAuthorizeSpeakerOrUnknownOutput() {
        val speaker = RouteEndpoint(3, RouteKind.OTHER, "扬声器")
        assertFalse(RoutePolicy.confirmed(listOf(speaker), listOf(a2dp)))
        assertFalse(RoutePolicy.confirmed(emptyList(), listOf(a2dp)))
    }
    @Test fun mixedSpeakerAndHeadphoneRouteIsRejected() {
        val speaker = RouteEndpoint(3, RouteKind.OTHER)
        assertFalse(RoutePolicy.confirmed(listOf(a2dp, speaker), listOf(a2dp, speaker)))
    }
    @Test fun removalAndOutputChangesStopAnAlreadyVerifiedRoute() {
        assertTrue(RoutePolicy.unchanged(listOf(a2dp), setOf(2)))
        assertFalse(RoutePolicy.unchanged(emptyList(), setOf(2)))
        assertFalse(RoutePolicy.unchanged(listOf(a2dp.copy(id = 4)), setOf(2)))
        assertFalse(RoutePolicy.confirmed(listOf(a2dp), emptyList()))
    }
    @Test fun wiredAndBleHeadsetsAreValidMediaDevices() {
        val wired = RouteEndpoint(5, RouteKind.WIRED)
        val ble = RouteEndpoint(6, RouteKind.BLE_HEADSET)
        assertEquals(wired, RoutePolicy.choose(listOf(a2dp, ble, wired)))
        assertEquals(ble, RoutePolicy.choose(listOf(sco, a2dp, ble)))
        assertTrue(RoutePolicy.confirmed(listOf(ble), listOf(ble)))
    }
}
