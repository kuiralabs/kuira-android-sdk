package com.midnight.kuira.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking

/**
 * Pins the dust-sync **opt-out** contract (#244): when [MidnightWallet.dustBackupEnabled]
 * is false, [MidnightWallet.backupDustToCloud] must NOT touch the Drive backup and must
 * report the dust lane as [CloudBackupStatus.Idle]. "Disable = stop uploading" — the
 * user-facing guarantee behind the toggle — has to hold at the SDK layer, not just the UI.
 */
class MidnightWalletBackupGateTest {

    private fun walletWith(backup: DustCloudBackup): MidnightWallet = MidnightWallet(
        dustSyncManager = mock(),
        dustRepository = mock(),
        indexerClient = mock(),
        nodeRpcClient = mock(),
        balanceRepository = mock(),
        shieldedTracker = mock(),
        walletAddress = "addr1",
        dustSeed = ByteArray(32),
        provingKeysDir = "",
        networkId = "test",
        spentDustNullifierStore = mock(),
        dustCloudBackup = backup,
    )

    @Test
    fun `disabled dust backup skips the Drive upload and reports Idle`() = runTest {
        val backup = mock<DustCloudBackup>()
        val wallet = walletWith(backup)

        wallet.dustBackupEnabled = false
        wallet.backupDustToCloud()

        verifyBlocking(backup, never()) { upload(any(), any(), any()) }
        assertEquals(CloudBackupStatus.Idle, wallet.backupStatus.value.dust)
    }
}
