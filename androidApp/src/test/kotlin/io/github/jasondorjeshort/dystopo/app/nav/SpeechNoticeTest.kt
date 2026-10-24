package io.github.jasondorjeshort.dystopo.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T137: the card's notice when the engine cannot speak: one line per failure kind, naming no product. */
class SpeechNoticeTest {
    @Test
    fun aLinePerKind() {
        assertEquals(NavSpeech.NOTICE_NO_ENGINE, NavSpeech.noticeText("noEngine"))
        assertEquals(NavSpeech.NOTICE_NO_VOICE, NavSpeech.noticeText("noVoice"))
        assertNull(NavSpeech.noticeText(null))
        for (t in listOf(NavSpeech.NOTICE_NO_ENGINE, NavSpeech.NOTICE_NO_VOICE)) {
            assertTrue("Android text-to-speech settings" in t, t)
            for (name in listOf("Google", "Samsung", "eSpeak", "RHVoice", "Maps")) assertTrue(name !in t, "$name in $t")
        }
    }
}
