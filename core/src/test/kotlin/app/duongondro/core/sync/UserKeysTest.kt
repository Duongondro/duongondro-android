package app.duongondro.core.sync

import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.SoftwareDeviceKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class UserKeysTest {
    private val user = UUID.fromString("b67fe412-4108-71f1-b85c-4c0606f45a8a")
    private val identity = Identity.generate()
    private val practiceKey = AccountKeys.newPracticeKey()
    private val u = UserKeys.create()
    private val published = Statements.UserKey(u.id, u.publicKey, user, u.version)
    private val prf = E2EE.randomBytes(32)
    private val credentialId = E2EE.randomBytes(20)

    private fun unlockable(keyVersion: Long = 1): UserKeys.Unlock {
        val box = UserKeys.sealForPasskey(u, prf, credentialId, user, identity)
        val wraps = listOf(
            UserKeys.wrapToUserKey(identity.seed, E2EE.WrapKind.IDENTITY_SEED, published, user, 1, identity),
            UserKeys.wrapToUserKey(practiceKey, E2EE.WrapKind.PRACTICE_KEY, published, user, keyVersion, identity),
        )
        return UserKeys.Unlock(UserKeys.statement(u, user, identity), box.box, box.signature, wraps)
    }

    private fun expect(failure: AccountKeys.Failure, block: () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: AccountKeys.Error) {
            assertEquals(failure, e.failure)
        }
    }

    @Test fun aNewPhoneUnlocksWithThePasskeysOutput() {
        val unlocked = UserKeys.unlock(prf, credentialId, user, identity.publicKey, 1, unlockable(), null)
        assertArrayEquals(identity.seed, unlocked.identity.seed)
        assertArrayEquals(practiceKey, unlocked.practiceKey)
        assertEquals(u.id, unlocked.userKey.id)
        assertArrayEquals(u.publicKey, unlocked.userKey.publicKey)
    }

    @Test fun anotherOutputOrPasskeyCannotUnlock() {
        expect(AccountKeys.Failure.PASSKEY_CANNOT_UNLOCK) { UserKeys.unlock(E2EE.randomBytes(32), credentialId, user, identity.publicKey, 1, unlockable(), null) }
        // The box names its credential: another passkey's id fails its signature first.
        expect(AccountKeys.Failure.NOT_AUTHENTIC) { UserKeys.unlock(prf, E2EE.randomBytes(20), user, identity.publicKey, 1, unlockable(), null) }
    }

    @Test fun anotherIdentityKeyIsRefused() {
        expect(AccountKeys.Failure.NOT_AUTHENTIC) { UserKeys.unlock(prf, credentialId, user, Identity.generate().publicKey, 1, unlockable(), null) }
        expect(AccountKeys.Failure.ACCOUNT_HAS_NO_KEYS) { UserKeys.unlock(prf, credentialId, user, null, 1, unlockable(), null) }
    }

    @Test fun aUserKeyWithoutTheCurrentPracticeKeyFallsBackToTheCode() {
        expect(AccountKeys.Failure.RECOVERY_OUTDATED) { UserKeys.unlock(prf, credentialId, user, identity.publicKey, 2, unlockable(1), null) }
        val session = UUID.randomUUID()
        val other = AccountKeys.SealedSample(session, E2EE.sealSession(E2EE.sealKey(AccountKeys.newPracticeKey(), user), session, user, 1, "{}".toByteArray()))
        expect(AccountKeys.Failure.RECOVERY_OUTDATED) { UserKeys.unlock(prf, credentialId, user, identity.publicKey, 1, unlockable(), other) }
    }

    @Test fun aStatementForAnotherUserIsRefused() {
        val theirs = UserKeys.statement(u, UUID.randomUUID(), identity)
        expect(AccountKeys.Failure.NOT_AUTHENTIC) { UserKeys.verify(theirs, user, identity.publicKey) }
        assertEquals(u.id, UserKeys.verify(UserKeys.statement(u, user, identity), user, identity.publicKey).id)
    }

    @Test fun missingWrapsFollowTheKeyVersion() {
        assertEquals(listOf(E2EE.WrapKind.PRACTICE_KEY, E2EE.WrapKind.IDENTITY_SEED), UserKeys.missing(emptyList(), 1))
        assertEquals(emptyList<E2EE.WrapKind>(), UserKeys.missing(listOf(1 to 1L, 2 to 1L), 1))
        // After a rotation: the new practice key, not the seed again.
        assertEquals(listOf(E2EE.WrapKind.PRACTICE_KEY), UserKeys.missing(listOf(1 to 1L, 2 to 1L), 2))
    }

    @Test fun uTravelsToAnotherPhoneAsKind4() {
        val device = UUID.randomUUID()
        val deviceKey = SoftwareDeviceKey.generate()
        val wrap = UserKeys.wrapToDevice(u, device, deviceKey.publicKey, user, identity)
        assertEquals(E2EE.WrapKind.USER_KEY, wrap.kind)
        assertEquals(u.version, wrap.keyVersion)
        val received = UserKeys.receive(wrap, published, user, device, deviceKey, identity)
        assertNotNull(received)
        assertArrayEquals(u.key.rawPrivate, received!!.key.rawPrivate)
        // Not for a newer U, nor signed by another identity.
        assertNull(UserKeys.receive(wrap, published.copy(version = 2), user, device, deviceKey, identity))
        assertNull(UserKeys.receive(wrap, published, user, device, deviceKey, Identity.generate()))
    }

    @Test fun theLocalRecordRoundTrips() {
        val back = UserKeys.Local.deserialised(u.serialised())!!
        assertEquals(u.id, back.id)
        assertEquals(u.version, back.version)
        assertArrayEquals(u.key.rawPrivate, back.key.rawPrivate)
        assertNull(UserKeys.Local.deserialised(ByteArray(10)))
    }
}
