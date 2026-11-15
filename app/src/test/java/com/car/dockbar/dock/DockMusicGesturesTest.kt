package com.car.dockbar.dock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockMusicGesturesTest {
    private val gestures = DockCatalog.of("music")!!.defaultGestures()

    @Test
    fun songInformationTapStillOpensThePlayer() {
        assertEquals(listOf(DockAction.OpenPlayer), resolve(HitTargets.ROOT))
    }

    @Test
    fun songInformationLongPressOnlyTogglesLyricsOnce() {
        assertEquals(listOf(DockAction.ToggleLyric), resolve(HitTargets.ROOT, longPress = true))
    }

    @Test
    fun nextButtonTapOnlySkipsToTheNextTrack() {
        assertEquals(listOf(DockAction.MediaNext), resolve(HitTargets.NEXT))
    }

    @Test
    fun playButtonOnlyTogglesPlayPause() {
        assertEquals(listOf(DockAction.PlayPause), resolve(HitTargets.PLAY))
        assertEquals(GestureBinding(tap = DockAction.PlayPause), gestures[HitTargets.PLAY])
    }

    @Test
    fun holdingPlayButtonDoesNotTriggerAnAction() {
        val emitted = mutableListOf<DockAction>()
        val resolver = GestureResolver(
            binding = gestures.getValue(HitTargets.PLAY),
            slopPx = 8f,
            emit = { emitted += it },
            onFingerSettled = {},
        )
        resolver.onDown(0f, 0f)
        resolver.onLongTimeout()
        resolver.onLongTimeout()
        assertTrue(emitted.isEmpty())
        resolver.onCancel()
    }

    @Test
    fun nextButtonGestureCanRoundTripWithoutChangingTheOtherMusicActions() {
        assertTrue(HitTargets.NEXT in HitTargets.all)
        val patch = GestureMaps.parsePatch(
            JSONObject("""{"next":{"tap":{"type":"playPause"}}}""")
        )!!
        val roundTripped = GestureMaps.parsePatch(GestureMaps.toJson(patch))
        assertEquals(patch, roundTripped)
        val merged = GestureMaps.merge(gestures, roundTripped)
        assertEquals(GestureBinding(tap = DockAction.PlayPause), merged[HitTargets.NEXT])
        assertEquals(gestures[HitTargets.ROOT], merged[HitTargets.ROOT])
        assertEquals(gestures[HitTargets.PLAY], merged[HitTargets.PLAY])
    }

    private fun resolve(target: String, longPress: Boolean = false): List<DockAction> {
        val emitted = mutableListOf<DockAction>()
        val resolver = GestureResolver(
            binding = gestures.getValue(target),
            slopPx = 8f,
            emit = { emitted += it },
            onFingerSettled = {},
        )
        resolver.onDown(0f, 0f)
        if (longPress) {
            resolver.onLongTimeout()
            resolver.onLongTimeout()
        }
        resolver.onUp(0f, 0f)
        return emitted
    }
}
