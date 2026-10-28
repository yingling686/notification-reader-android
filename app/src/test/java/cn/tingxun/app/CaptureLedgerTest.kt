package cn.tingxun.app

import org.junit.Assert.*
import org.junit.Test

class CaptureLedgerTest {
    @Test fun unchangedSnapshotIsSkippedButAnUpdateIsRecovered() {
        val ledger = CaptureLedger()
        assertTrue(ledger.observe("key", "first"))
        assertFalse(ledger.observe("key", "first"))
        assertTrue(ledger.observe("key", "changed-body-same-post-time"))
        assertFalse(ledger.observe("key", "changed-body-same-post-time"))
    }
    @Test fun callbackAndScanShareHintsAndBoundMemory() {
        val ledger = CaptureLedger(2)
        assertTrue(ledger.observe("a", "1")); assertFalse(ledger.observe("a", "1"))
        ledger.observe("b", "1"); ledger.observe("c", "1")
        assertTrue(ledger.observe("a", "1")) // Room still deduplicates after eviction.
        ledger.clear(); assertTrue(ledger.observe("a", "1"))
    }
}
