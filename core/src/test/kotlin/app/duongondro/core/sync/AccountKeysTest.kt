package app.duongondro.core.sync

import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.SoftwareDeviceKey
import app.duongondro.core.crypto.StatementTypes
import app.duongondro.core.crypto.Tier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The account steps without a server: what iOS's live set-up, restore and
 * key-receipt tests exercise against one, checked here as pure functions.
 */
class AccountKeysTest {
    private val user = UUID.randomUUID()
    private val now = Instant.ofEpochMilli(1_791_176_400_000)

    private fun failure(expected: AccountKeys.Failure, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: AccountKeys.Error) {
            assertEquals(expected, e.failure)
        }
    }

    // First device

    @Test fun aNewAccountGetsNewSecrets() {
        val s = AccountKeys.firstDeviceSecrets(user, null, AccountKeys.StoredSetUp(null, null, null))
        assertTrue(s.publishIdentity)
        assertTrue("nothing stored belongs to this user yet", s.discardStored)
        assertEquals(user.toString(), s.setUpUser)
        assertEquals(32, s.practiceKey.size)
    }

    @Test fun anInterruptedSetUpResumesWithItsSecrets() {
        val identity = Identity.generate()
        val key = AccountKeys.newPracticeKey()
        val stored = AccountKeys.StoredSetUp(user.toString(), identity.seed, key)
        // Before the identity was published.
        val before = AccountKeys.firstDeviceSecrets(user, null, stored)
        assertFalse(before.discardStored)
        assertArrayEquals(identity.publicKey, before.identity.publicKey)
        assertArrayEquals(key, before.practiceKey)
        // After: only with the very identity the account published.
        val after = AccountKeys.firstDeviceSecrets(user, identity.publicKey, stored)
        assertFalse(after.publishIdentity)
        assertArrayEquals(key, after.practiceKey)
    }

    @Test fun anotherAccountsLeftoversAreNotReused() {
        val leftover = Identity.generate()
        val stored = AccountKeys.StoredSetUp(UUID.randomUUID().toString(), leftover.seed, ByteArray(32))
        val s = AccountKeys.firstDeviceSecrets(user, null, stored)
        assertTrue(s.discardStored)
        assertFalse(s.identity.publicKey.contentEquals(leftover.publicKey))
        assertFalse(s.practiceKey.contentEquals(ByteArray(32)))
    }

    @Test fun anAccountWithOtherKeysMustRestore() {
        val published = Identity.generate().publicKey
        failure(AccountKeys.Failure.ACCOUNT_HAS_KEYS) {
            AccountKeys.firstDeviceSecrets(user, published, AccountKeys.StoredSetUp(null, null, null))
        }
        failure(AccountKeys.Failure.ACCOUNT_HAS_KEYS) {
            AccountKeys.firstDeviceSecrets(user, published, AccountKeys.StoredSetUp(user.toString(), Identity.generate().seed, ByteArray(32)))
        }
    }

    // Device lists

    private fun device(tier: Tier = Tier.SOFTWARE) = Statements.ListedDevice(UUID.randomUUID(), SoftwareDeviceKey.generate().publicKey, tier)

    @Test fun theFirstListIsVersionOneAndSigned() {
        val identity = Identity.generate()
        val me = device(Tier.HARDWARE)
        val list = AccountKeys.deviceListAdding(me, user, identity, null, emptyList(), now)
        assertTrue(list.verify(StatementTypes.DEVICE_LIST, identity.publicKey))
        val parsed = Statements.parseDeviceList(list.payload)!!
        assertEquals(1L, parsed.version)
        assertEquals(listOf(me), parsed.devices)
        assertEquals(now, parsed.issuedAt)
        assertTrue(AccountKeys.listsDevice(list, me.id))
        assertFalse(AccountKeys.listsDevice(list, UUID.randomUUID()))
        assertFalse(AccountKeys.listsDevice(null, me.id))
    }

    @Test fun aNewDeviceExtendsTheListWithoutRemovedOnes() {
        val identity = Identity.generate()
        val a = device()
        val b = device()
        val v1 = AccountKeys.deviceListAdding(a, user, identity, null, emptyList(), now)
        val v2 = AccountKeys.deviceListAdding(b, user, identity, v1, listOf(a, b), now)
        assertEquals(listOf(a, b), Statements.parseDeviceList(v2.payload)!!.devices)
        assertEquals(2L, Statements.parseDeviceList(v2.payload)!!.version)
        // a was removed on the server; c joins. a is not listed again, b keeps its place.
        val c = device()
        val v3 = AccountKeys.deviceListAdding(c, user, identity, v2, listOf(b, c), now)
        assertEquals(listOf(b, c), Statements.parseDeviceList(v3.payload)!!.devices)
        // b re-registered with another key is not trusted from the old list.
        val bNewKey = Statements.ListedDevice(b.id, SoftwareDeviceKey.generate().publicKey, b.tier)
        val v4 = AccountKeys.deviceListAdding(device(), user, identity, v3, listOf(bNewKey, c), now)
        assertEquals(listOf(c), Statements.parseDeviceList(v4.payload)!!.devices.dropLast(1))
        // Adding a listed device again replaces it rather than doubling it.
        val v5 = AccountKeys.deviceListAdding(c, user, identity, v3, listOf(b, c), now)
        assertEquals(listOf(b, c), Statements.parseDeviceList(v5.payload)!!.devices)
    }

    @Test fun aListNotSignedByTheIdentityIsRefused() {
        val identity = Identity.generate()
        val forged = AccountKeys.deviceListAdding(device(), user, Identity.generate(), null, emptyList(), now)
        failure(AccountKeys.Failure.NOT_AUTHENTIC) { AccountKeys.deviceListAdding(device(), user, identity, forged, emptyList(), now) }
        // On restore, it is checked against the account's published key.
        val real = AccountKeys.deviceListAdding(device(), user, identity, null, emptyList(), now)
        failure(AccountKeys.Failure.NOT_AUTHENTIC) {
            AccountKeys.deviceListAdding(device(), user, identity, real, emptyList(), now, verifyWith = Identity.generate().publicKey)
        }
    }

    // Wraps

    @Test fun wrappedSecretsReachTheDevice() {
        val identity = Identity.generate()
        val practiceKey = AccountKeys.newPracticeKey()
        val deviceKey = SoftwareDeviceKey.generate()
        val id = UUID.randomUUID()
        val wraps = AccountKeys.wrapSecrets(id, deviceKey.publicKey, user, 1, identity, practiceKey)
        assertEquals(listOf(E2EE.WrapKind.PRACTICE_KEY, E2EE.WrapKind.IDENTITY_SEED), wraps.map { it.kind })
        for (w in wraps) {
            assertEquals(AccountKeys.AuthType.SIGNATURE, w.authType)
            assertEquals(E2EE.PUBLIC_KEY_SIZE, w.ephemeralKey.size)
            assertEquals(E2EE.WRAPPED_BOX_SIZE, w.box.size)
        }
        assertArrayEquals(practiceKey, AccountKeys.receiveWrap(wraps[0], user, id, deviceKey, identity.publicKey))
        assertArrayEquals(identity.seed, AccountKeys.receiveWrap(wraps[1], user, id, deviceKey, identity.publicKey))
    }

    @Test fun anUnsignedOrForeignWrapIsSkipped() {
        val identity = Identity.generate()
        val deviceKey = SoftwareDeviceKey.generate()
        val id = UUID.randomUUID()
        val w = AccountKeys.wrapSecrets(id, deviceKey.publicKey, user, 2, identity, AccountKeys.newPracticeKey())[0]
        assertNull("signed by someone else", AccountKeys.receiveWrap(w, user, id, deviceKey, Identity.generate().publicKey))
        val asEnrol = AccountKeys.Wrap(w.kind, w.keyVersion, w.ephemeralKey, w.box, AccountKeys.AuthType.ENROL, w.authenticator)
        assertNull("not a signature", AccountKeys.receiveWrap(asEnrol, user, id, deviceKey, identity.publicKey))
        val otherVersion = AccountKeys.Wrap(w.kind, 3, w.ephemeralKey, w.box, w.authType, w.authenticator)
        assertNull("the signature covers the key version", AccountKeys.receiveWrap(otherVersion, user, id, deviceKey, identity.publicKey))
        assertNull("addressed to another device", AccountKeys.receiveWrap(w, user, UUID.randomUUID(), deviceKey, identity.publicKey))
    }

    // Recovery

    private class Account(val identity: Identity = Identity.generate(), val practiceKey: ByteArray = AccountKeys.newPracticeKey()) {
        val recovery = AccountKeys.newRecoverySecret()
        val code = RecoveryCode.encode(recovery)
    }

    @Test fun theRecoveryCodeRestoresBothSecrets() {
        val a = Account()
        val boxes = AccountKeys.recoveryBoxes(a.recovery, user, a.identity, a.practiceKey)
        val session = UUID.randomUUID()
        val sample = AccountKeys.SealedSample(session,
            E2EE.sealSession(E2EE.sealKey(a.practiceKey, user), session, user, 1, "{}".toByteArray()))
        val restored = AccountKeys.restore(a.code.lowercase().replace("-", " "), user, a.identity.publicKey, 1, boxes, sample)
        assertArrayEquals(a.identity.seed, restored.identity.seed)
        assertArrayEquals(a.practiceKey, restored.practiceKey)
        assertEquals(1L, restored.keyVersion)
        // With nothing synced yet there is no sample, and that is fine.
        AccountKeys.restore(a.code, user, a.identity.publicKey, 1, boxes, null)
    }

    @Test fun aWrongCodeDoesNotRestore() {
        val a = Account()
        val boxes = AccountKeys.recoveryBoxes(a.recovery, user, a.identity, a.practiceKey)
        failure(AccountKeys.Failure.BAD_RECOVERY_CODE) {
            AccountKeys.restore(RecoveryCode.encode(AccountKeys.newRecoverySecret()), user, a.identity.publicKey, 1, boxes, null)
        }
        failure(AccountKeys.Failure.BAD_RECOVERY_CODE) { AccountKeys.restore("not a code", user, a.identity.publicKey, 1, boxes, null) }
        failure(AccountKeys.Failure.BAD_RECOVERY_CODE) {
            AccountKeys.restore(a.code, UUID.randomUUID(), a.identity.publicKey, 1, boxes, null)
        }
    }

    @Test fun anAccountWithoutKeysCannotRestore() {
        val a = Account()
        failure(AccountKeys.Failure.ACCOUNT_HAS_NO_KEYS) { AccountKeys.restore(a.code, user, null, 1, emptyList(), null) }
    }

    @Test fun boxesForAnotherIdentityAreNotAuthentic() {
        val a = Account()
        val boxes = AccountKeys.recoveryBoxes(a.recovery, user, a.identity, a.practiceKey)
        // The server publishes another identity key than the one in the boxes.
        failure(AccountKeys.Failure.NOT_AUTHENTIC) { AccountKeys.restore(a.code, user, Identity.generate().publicKey, 1, boxes, null) }
        // A missing box.
        failure(AccountKeys.Failure.NOT_AUTHENTIC) { AccountKeys.restore(a.code, user, a.identity.publicKey, 1, boxes.take(1), null) }
        // A box whose signature does not verify.
        val unsigned = boxes.map { AccountKeys.RecoveryBox(it.kind, it.box, ByteArray(64)) }
        failure(AccountKeys.Failure.NOT_AUTHENTIC) { AccountKeys.restore(a.code, user, a.identity.publicKey, 1, unsigned, null) }
    }

    @Test fun boxesFromBeforeARotationAreOutdated() {
        val a = Account()
        val boxes = AccountKeys.recoveryBoxes(a.recovery, user, a.identity, a.practiceKey)
        val rotated = AccountKeys.newPracticeKey()
        val session = UUID.randomUUID()
        val sample = AccountKeys.SealedSample(session,
            E2EE.sealSession(E2EE.sealKey(rotated, user), session, user, 2, "{}".toByteArray()))
        failure(AccountKeys.Failure.RECOVERY_OUTDATED) { AccountKeys.restore(a.code, user, a.identity.publicKey, 2, boxes, sample) }
    }
}
