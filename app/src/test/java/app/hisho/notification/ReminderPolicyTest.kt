package app.hisho.notification

import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    @Test fun suppressesExpiredBlocks() {
        assertFalse(ReminderPolicy.shouldDeliver(200, 100, 200, false, -1, false))
    }
    @Test fun suppressesLatePreparation() {
        assertFalse(ReminderPolicy.shouldDeliver(100, 100, 200, true, -1, false))
    }
    @Test fun deliversStartDuringBlock() {
        assertTrue(ReminderPolicy.shouldDeliver(110, 100, 200, false, -1, false))
    }
    @Test fun restoreDoesNotRepeatDeliveredNotification() {
        assertFalse(ReminderPolicy.shouldDeliver(110, 100, 200, false, 100, false))
    }
    @Test fun snoozeCanNotifyAgain() {
        assertTrue(ReminderPolicy.shouldDeliver(150, 100, 200, false, 100, true))
    }
    @Test fun snoozeCannotReviveExpiredBlock() {
        assertFalse(ReminderPolicy.shouldDeliver(201, 100, 200, false, 100, true))
    }
    @Test fun movedBlocksHaveDifferentWorkIdentity() {
        assertNotEquals(ReminderPolicy.workName(1, 1, "start", 100), ReminderPolicy.workName(1, 1, "start", 200))
    }
    @Test fun movedBlockCanNotify() {
        assertTrue(ReminderPolicy.shouldDeliver(150, 150, 250, false, 100, false))
    }
}
