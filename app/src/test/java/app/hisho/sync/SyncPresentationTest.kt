package app.hisho.sync

import org.junit.Assert.*
import org.junit.Test

class SyncPresentationTest {
    @Test fun reviewIsNotUnqualifiedSuccess() {
        assertTrue(SyncPresentation.label("REVIEW_REQUIRED").contains("確認が必要"))
        assertFalse(SyncPresentation.busy("REVIEW_REQUIRED"))
    }
    @Test fun queuedAndRunningAreBusy() {
        assertTrue(SyncPresentation.busy("QUEUED"))
        assertTrue(SyncPresentation.busy("RUNNING"))
        assertFalse(SyncPresentation.busy("API_ERROR"))
    }
}
