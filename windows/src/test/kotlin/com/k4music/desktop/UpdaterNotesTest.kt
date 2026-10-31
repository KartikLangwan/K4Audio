package com.k4music.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdaterNotesTest {
    @Test
    fun theUpdateDialogShowsPlainBullets() {
        val notes = Updater.cleanNotes(
            "K4 Audio **2.15.0**\n\n## Downloads\n\n| | Android |\n| --- | --- |\n| **File** | `x.apk` |\n\n## What's new\n\n- Lighter on **PC**\n- A `music` folder\n\nThe installer isn't signed."
        )
        assertEquals("• Lighter on PC\n• A music folder", notes)
        assertNull(Updater.cleanNotes(null))
        assertNull(Updater.cleanNotes("no bullets here"))
    }
}
