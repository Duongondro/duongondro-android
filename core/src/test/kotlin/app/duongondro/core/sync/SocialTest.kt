package app.duongondro.core.sync

import app.duongondro.core.Session
import app.duongondro.core.api.Api
import app.duongondro.core.api.Http
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.MemoryDeviceKeyStore
import app.duongondro.core.crypto.StatementTypes
import app.duongondro.core.uuidV7
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/** The pin store's rules, as iOS's FriendPinTests. */
class FriendPinTest {
    @Test
    fun aFriendDroppedAndListedAgainKeepsTheFirstKey() = runBlocking {
        val db = MemorySyncDatabase()
        val friend = UUID.randomUUID()
        db.pin(friend, byteArrayOf(1), "Ania", Instant.EPOCH)
        db.keepFriends(emptySet())
        assertFalse("unlisted, not forgotten", db.pinnedFriends().single().listed)
        val again = db.pin(friend, byteArrayOf(2), "Ania", Instant.EPOCH)
        assertArrayEquals("the server's new key is not a first sight", byteArrayOf(1), again.identityPk)
        assertTrue(again.listed)
        db.repin(friend, byteArrayOf(2), "", Instant.EPOCH)
        assertArrayEquals("a key an invite proved replaces it", byteArrayOf(2), db.pinnedFriends().single().identityPk)
        assertEquals("an empty name keeps the known one", "Ania", db.pinnedFriends().single().displayName)
        db.forgetFriend(friend)
        assertTrue(db.pinnedFriends().isEmpty())
    }

    @Test
    fun seenSeqsOnlyGrow() = runBlocking {
        val db = MemorySyncDatabase()
        val friend = UUID.randomUUID()
        db.noteSeq(friend, "chenrezig", 5)
        db.noteSeq(friend, "chenrezig", 3)
        assertEquals(5L, db.seenSeq(friend, "chenrezig"))
    }
}

/**
 * Social against a fake server that lists friends and forwards whatever
 * statements it is given: pinning on first sight, a changed key, a forged or
 * replayed statement, publishing only what changed, and pokes.
 */
class SocialTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = Instant.parse("2026-10-14T18:00:00Z")

    private class FakeServer : Http {
        /** userId → (displayName, identity key as the server names it). */
        val friends = linkedMapOf<UUID, Pair<String, ByteArray?>>()
        /** What GET /api/friends/streaks forwards. */
        val friendStreaks = mutableListOf<Triple<UUID, String, SignedStatement>>()
        /** This account's own statements, by practice, as PUT stored them. */
        val own = linkedMapOf<String, SignedStatement>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** Runs once, on the next GET /api/streaks, after its answer is made and before it arrives. */
        var onOwnStreaks: (() -> Unit)? = null
        var pokedToday = mutableSetOf<UUID>()

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun ok(body: String = "") = Http.Response(if (body.isEmpty()) 204 else 200, body.toByteArray())

        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Response {
            val path = url.substringAfter("http://server/")
            calls += "$method $path"
            return when {
                method == "GET" && path == "api/friends" -> ok("""{"friends":[${friends.entries.joinToString(",") { (id, f) ->
                    """{"userId":"$id","displayName":"${f.first}","notifyDone":false,"since":"2026-10-01T00:00:00Z"""" +
                        (f.second?.let { ""","identityPublicKey":"${b64(it)}"""" } ?: "") + "}"
                }}]}""")
                method == "GET" && path == "api/friends/streaks" -> ok("""{"streaks":[${friendStreaks.joinToString(",") { (u, p, s) ->
                    """{"userId":"$u","practice":"$p","payload":"${b64(s.payload)}","signature":"${b64(s.signature)}"}"""
                }}]}""")
                method == "GET" && path == "api/streaks" -> ok("""{"streaks":[${own.entries.joinToString(",") { (p, s) ->
                    """{"userId":"${UUID(0, 0)}","practice":"$p","payload":"${b64(s.payload)}","signature":"${b64(s.signature)}"}"""
                }}]}""").also { onOwnStreaks?.also { onOwnStreaks = null }?.invoke() }
                method == "PUT" && path.startsWith("api/streaks/") -> {
                    val o = Api.json.parseToJsonElement(body!!.decodeToString()).jsonObject
                    val st = SignedStatement(Base64.getDecoder().decode(o["payload"]!!.jsonPrimitive.content),
                        Base64.getDecoder().decode(o["signature"]!!.jsonPrimitive.content))
                    val practice = path.removePrefix("api/streaks/")
                    val stored = own[practice]?.let { Statements.parseStreak(it.payload)!!.seq } ?: 0
                    if (Statements.parseStreak(st.payload)!!.seq <= stored) return Http.Response(409, """{"error":"seq"}""".toByteArray())
                    own[practice] = st
                    ok()
                }
                method == "DELETE" && path.startsWith("api/streaks/") ->
                    if (own.remove(path.removePrefix("api/streaks/")) != null) ok() else Http.Response(404, """{"error":"no"}""".toByteArray())
                method == "POST" && path.endsWith("/pokes") -> {
                    val id = UUID.fromString(path.removePrefix("api/friends/").removeSuffix("/pokes"))
                    if (!pokedToday.add(id)) Http.Response(409, """{"error":"already"}""".toByteArray()) else ok()
                }
                else -> ok()
            }
        }
    }

    private fun phone(server: FakeServer, db: MemorySyncDatabase, user: UUID, identity: Identity, clock: () -> Instant = { now }): Social {
        val secrets = MemorySecretStore().apply { write(SecretName.IDENTITY_SEED, identity.seed) }
        runBlocking { db.saveSyncState(SyncState(user, 1)) }
        return Social(Account(Api("http://server", "t", server), secrets, MemoryDeviceKeyStore(), db), db, clock, { zone })
    }

    private fun streak(friend: UUID, identity: Identity, practice: String, current: Int, seq: Long, deadline: Instant = now.plus(Duration.ofHours(30))) =
        SignedStatement.sign(StatementTypes.STREAK,
            Statements.streak(Statements.Streak(friend, practice, "2026-10-14", current, current, deadline, seq)), identity)

    @Test
    fun friendsArePinnedOnFirstSightAndOnlyVerifiedStreaksShow() = runBlocking {
        val server = FakeServer()
        val db = MemorySyncDatabase()
        val social = phone(server, db, UUID.randomUUID(), Identity.generate())
        val ania = UUID.randomUUID()
        val aniaKey = Identity.generate()
        server.friends[ania] = "Ania" to aniaKey.publicKey
        server.friendStreaks += Triple(ania, "chenrezig", streak(ania, aniaKey, "chenrezig", 9, 100))
        // Signed by someone else: dropped.
        server.friendStreaks += Triple(ania, "amitabha", streak(ania, Identity.generate(), "amitabha", 50, 100))

        val first = social.friends().single()
        assertFalse(first.keyChanged)
        assertEquals(listOf("chenrezig"), first.streaks.map { it.practice })
        assertEquals(9, first.streaks.single().current)
        assertArrayEquals(aniaKey.publicKey, db.pinnedFriends().single().identityPk)

        // The server replays an older, validly signed statement: never shown below one already seen.
        server.friendStreaks.clear()
        server.friendStreaks += Triple(ania, "chenrezig", streak(ania, aniaKey, "chenrezig", 1, 50))
        assertTrue(social.friends().single().streaks.isEmpty())

        // The server swaps her key: the pin stays, and nothing of hers shows.
        val swapped = Identity.generate()
        server.friends[ania] = "Ania" to swapped.publicKey
        server.friendStreaks.clear()
        server.friendStreaks += Triple(ania, "chenrezig", streak(ania, swapped, "chenrezig", 10, 200))
        val changed = social.friends().single()
        assertTrue(changed.keyChanged)
        assertTrue(changed.streaks.isEmpty())

        // Dropped from the list: unlisted but pinned, so listing her again under the new key is no first sight.
        server.friends.clear()
        assertTrue(social.friends().isEmpty())
        assertFalse(db.pinnedFriends().single().listed)
        server.friends[ania] = "Ania" to swapped.publicKey
        assertTrue(social.friends().single().keyChanged)

        // Unfriending forgets the pin.
        social.unfriend(ania)
        assertTrue(db.pinnedFriends().isEmpty())
    }

    @Test
    fun publicStreaksArePublishedSignedAndOnlyWhenChanged() = runBlocking {
        val server = FakeServer()
        val db = MemorySyncDatabase()
        val me = UUID.randomUUID()
        val identity = Identity.generate()
        var clock = now
        val social = phone(server, db, me, identity) { clock }
        for (d in listOf(1L, 0L)) {
            val t = now.minus(Duration.ofDays(d)).minusSeconds(600)
            db.insert(SyncRecord(Session(uuidV7(t), "dorje-sempa", 108, t, true, zone.id, null, t), t))
        }
        db.insert(SyncRecord(Session(uuidV7(now), "chenrezig", 10, now, true, zone.id, null, now), now))

        social.setPublic("dorje-sempa", true)
        val st = server.own.getValue("dorje-sempa")
        assertTrue(st.verify(StatementTypes.STREAK, identity.publicKey))
        val parsed = Statements.parseStreak(st.payload)!!
        assertEquals(me, parsed.user)
        assertEquals(2, parsed.current)
        assertEquals("2026-10-14", parsed.day)
        assertTrue("only public practices go up", "chenrezig" !in server.own)

        clock = now.plusSeconds(60)
        assertEquals("nothing changed, nothing sent", 0, social.publishStreaks())

        // Another phone sent a higher seq: the next one goes above it.
        val ahead = Statements.Streak(me, "dorje-sempa", "2026-10-13", 1, 1, now, clock.syncMillis() + 10_000)
        server.own["dorje-sempa"] = SignedStatement.sign(StatementTypes.STREAK, Statements.streak(ahead), identity)
        val fresh = Social(Account(Api("http://server", "t", server), MemorySecretStore().apply { write(SecretName.IDENTITY_SEED, identity.seed) },
            MemoryDeviceKeyStore(), db), db, { clock }, { zone })
        assertEquals(1, fresh.publishStreaks())
        assertEquals(2, Statements.parseStreak(server.own.getValue("dorje-sempa").payload)!!.current)

        social.setPublic("dorje-sempa", false)
        assertTrue(server.own.isEmpty())
        assertFalse(db.isPublic("dorje-sempa"))
    }

    /** Review found it: private while a publish has the server's list in hand must stay private. */
    @Test
    fun privateDuringAPublishStaysPrivate() = runBlocking {
        val server = FakeServer()
        val db = MemorySyncDatabase()
        val me = UUID.randomUUID()
        val identity = Identity.generate()
        val social = phone(server, db, me, identity)
        db.insert(SyncRecord(Session(uuidV7(now), "chenrezig", 10, now, true, zone.id, null, now), now))
        social.setPublic("chenrezig", true)
        assertTrue("chenrezig" in server.own)
        var toggler: Thread? = null
        server.onOwnStreaks = {
            toggler = Thread { runBlocking { social.setPublic("chenrezig", false) } }.apply { start() }
            Thread.sleep(300)
        }
        social.publishStreaks()
        toggler!!.join()
        assertFalse(db.isPublic("chenrezig"))
        assertTrue(server.own.isEmpty())
    }

    @Test
    fun theServerSaysWhichPracticesArePublic() = runBlocking {
        val server = FakeServer()
        val db = MemorySyncDatabase()
        val me = UUID.randomUUID()
        val identity = Identity.generate()
        db.insert(SyncRecord(Session(uuidV7(now), "chenrezig", 10, now, true, zone.id, null, now), now))
        // Made public on another phone.
        server.own["chenrezig"] = SignedStatement.sign(StatementTypes.STREAK,
            Statements.streak(Statements.Streak(me, "chenrezig", "2026-10-01", 1, 1, now, 5)), identity)
        assertEquals(1, phone(server, db, me, identity).publishStreaks())
        assertTrue(db.isPublic("chenrezig"))
        assertEquals("2026-10-14", Statements.parseStreak(server.own.getValue("chenrezig").payload)!!.day)
    }

    /** Review found it: private on another phone reaches this one; a practice not yet published stays public. */
    @Test
    fun privateOnAnotherPhoneIsPrivateHere() = runBlocking {
        val server = FakeServer()
        val db = MemorySyncDatabase()
        val me = UUID.randomUUID()
        val social = phone(server, db, me, Identity.generate())
        db.insert(SyncRecord(Session(uuidV7(now), "chenrezig", 10, now, true, zone.id, null, now), now))
        social.setPublic("chenrezig", true)
        // The other phone makes it private: the server drops it.
        server.own.clear()
        // And this phone has one it never managed to publish.
        db.setPublic("mandala", true)
        social.publishStreaks()
        assertFalse(db.isPublic("chenrezig"))
        assertTrue(server.own.isEmpty())
        assertTrue("never published, so kept", db.isPublic("mandala"))
    }

    @Test
    fun aSecondPokeTheSameDayCountsAsSent() = runBlocking {
        val server = FakeServer()
        val social = phone(server, MemorySyncDatabase(), UUID.randomUUID(), Identity.generate())
        val friend = UUID.randomUUID()
        assertEquals(Social.utcDay(now), social.poke(friend))
        assertEquals(Social.utcDay(now), social.poke(friend))
        assertEquals(2, server.calls.count { it.endsWith("/pokes") })
    }

    @Test
    fun aStatementCarriesTrackedDaysOnly() {
        val me = UUID.randomUUID()
        val t = now.minusSeconds(600)
        val s = Social.statement("mandala", listOf(Session(uuidV7(t), "mandala", 0, t, true, zone.id, null, t)), me, 7, now, zone)!!
        assertEquals(1, s.current)
        assertEquals("2026-10-14", s.day)
        // Midnight at the end of the next day, in Warsaw.
        assertEquals(Instant.parse("2026-10-15T22:00:00Z"), s.deadline)
        assertEquals(null, Social.statement("mandala", emptyList(), me, 7, now, zone))
    }
}

/** The Friends tab's news and leaderboard, from the signed deadline. */
class FriendsBoardTest {
    private val now = Instant.parse("2026-10-14T18:00:00Z")

    private fun friend(name: String, vararg streaks: Social.FriendStreak, keyChanged: Boolean = false) =
        Social.FriendView(UUID.randomUUID(), name, null, false, keyChanged, streaks.toList())

    private fun s(practice: String, current: Int, hoursLeft: Long, seq: Long = 1) =
        Social.FriendStreak(practice, null, current, current, now.plus(Duration.ofHours(hoursLeft)), seq)

    @Test
    fun doneTodayComesFirstThenWaitingThenNothing() {
        val ania = friend("Ania", s("chenrezig", 9, 30, seq = 5))
        val piotr = friend("Piotr", s("refuge", 23, 6))
        val gone = friend("Gone", s("mandala", 4, -1))
        val swapped = friend("Swapped", s("mandala", 40, 30), keyChanged = true)
        val news = FriendsBoard.news(listOf(piotr, gone, ania, swapped), now)
        assertEquals(listOf("Ania", "Piotr"), news.map { it.friend.displayName })
        assertEquals(listOf(true, false), news.map { it.doneToday })
        assertEquals(listOf(23, 9), FriendsBoard.leaders(listOf(ania, piotr, gone, swapped), now).map { it.streak.current })
    }
}
