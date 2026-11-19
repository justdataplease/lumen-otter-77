package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.community.CommunityCache
import com.justdataplease.boringtravel.community.CommunityLinks
import com.justdataplease.boringtravel.community.CommunityOfflineException
import com.justdataplease.boringtravel.community.CommunityOutbox
import com.justdataplease.boringtravel.community.CommunityRefusedException
import com.justdataplease.boringtravel.community.FirestoreCommunity
import com.justdataplease.boringtravel.community.Leaderboard
import com.justdataplease.boringtravel.community.NotSignedInException
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.ProfileAvatar
import com.justdataplease.boringtravel.community.SharedItem
import com.justdataplease.boringtravel.community.SharedNote
import com.justdataplease.boringtravel.community.SharedTrip
import com.justdataplease.boringtravel.community.SharedTrips
import com.justdataplease.boringtravel.community.TripCopy
import com.justdataplease.boringtravel.community.Usernames
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.firebase.FirestoreException
import com.justdataplease.boringtravel.firebase.FirestoreRest
import com.justdataplease.boringtravel.firebase.FsQuery
import com.justdataplease.boringtravel.firebase.FsRef
import com.justdataplease.boringtravel.firebase.FsTime
import com.justdataplease.boringtravel.firebase.FsValues
import com.justdataplease.boringtravel.firebase.FsWrites
import com.justdataplease.boringtravel.firebase.Increment
import com.justdataplease.boringtravel.firebase.ServerTime
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.ios.Deeplink
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.MemorySettingsFile
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetAnswer
import com.justdataplease.boringtravel.ios.NetCall
import com.justdataplease.boringtravel.ios.NetException
import com.justdataplease.boringtravel.ios.NetRequest
import com.justdataplease.boringtravel.time.localDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 24's community on both phones (Kotlin/Native's regular expressions, text, numbers and dates differ from Java's
 * in places): a post's texts (numbers grouped, counts with one decimal, months and how long ago), usernames' rules and
 * suggestions with accents taken off (the engine's decomposed() in place of Java's Normalizer), a post's link and the
 * app's own link, the leaderboard's ranks, a shared trip read safely and fitted, Copy this trip, a picture's view, and
 * Firestore's REST API as the app speaks it (typed values, times, queries with their cursors, writes with transforms,
 * the answers, a stale token renewed, failures in people's words), the writes kept for later and the documents kept for
 * offline. Made-up ids and handles; addresses on example.com.
 */
class CommunitySmokeTest {
    private val us = Locale.US

    /** 2026-11-19 10:00 UTC. */
    private val at = 1_795_082_400_000L

    @Test fun aPostsTexts() {
        assertEquals("12 days · 1,240 km", PostText.facts(12, 1240, us))
        assertEquals("1 day", PostText.facts(1, 0, us))
        assertEquals("", PostText.facts(0, 0, us))
        assertEquals("1 like", PostText.likes(1))
        assertEquals("128 likes", PostText.likes(128))
        assertEquals("1.2k", PostText.count(1_234))
        assertEquals("1k", PostText.count(1_000))
        assertEquals("12k", PostText.count(12_345))
        assertEquals("1.5m", PostText.count(1_500_000))
        assertEquals("@trailnotes", PostText.handle("trailnotes"))
        assertEquals(at, FsValues.parseTime("2026-11-19T10:00:00Z"))
        assertEquals("2026-11", PostText.monthKey(at))
        assertEquals("Mar 2027", PostText.monthLabel("2027-03", us))
        assertEquals("2027-13", PostText.monthLabel("2027-13", us))
        assertNull(PostText.monthLabel(null, us))
        assertEquals("now", PostText.ago(at - 30_000, at, us))
        assertEquals("5m", PostText.ago(at - 5 * 60_000, at, us))
        assertEquals("3h", PostText.ago(at - 3 * 3_600_000, at, us))
        assertEquals("2d", PostText.ago(at - 2 * 86_400_000L, at, us))
        assertEquals("4w", PostText.ago(at - 34 * 86_400_000L, at, us))
        assertEquals("10 Oct 2026", PostText.ago(at - 40 * 86_400_000L, at, us))
    }

    @Test fun usernamesRulesAndSuggestions() {
        assertEquals("coast.walker", Usernames.normalize(" @Coast.Walker "))
        assertEquals(Usernames.Problem.Short, Usernames.problem("ab"))
        assertEquals(Usernames.Problem.Long, Usernames.problem("a".repeat(21)))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("coast-walker"))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("νάξος"))
        assertEquals(Usernames.Problem.Dot, Usernames.problem(".coast"))
        assertEquals(Usernames.Problem.Dots, Usernames.problem("coast..walker"))
        assertEquals(Usernames.Problem.Reserved, Usernames.problem("admin"))
        assertNull(Usernames.problem("coast_walker.2"))
        // Accents off as Java's NFD and \p{M} take them off: written whole, or as a letter and its combining mark.
        assertEquals("jose.nunez", Usernames.suggest("José Núñez"))
        assertEquals("jose.marquez", Usernames.suggest("José Márquez"))
        assertEquals("anne.sophie", Usernames.suggest("Anne-Sophie"))
        assertEquals("traveller", Usernames.suggest("traveller@example.com"))
        assertEquals("al0", Usernames.suggest("Al"))
        assertEquals("admin_tr", Usernames.suggest("Admin"))
        // Greek letters aren't a–z: nothing usable is left, as on Android.
        assertNull(Usernames.suggest("Μαρία"))
        assertNull(Usernames.suggest("  "))
    }

    @Test fun aPostsLinkAndTheAppsOwnLink() {
        assertEquals("https://justdataplease.com/apps/boringtravel/post/#pNaxos01", CommunityLinks.url("pNaxos01"))
        assertEquals("pNaxos01", CommunityLinks.find("Look: https://www.justdataplease.com/apps/boringtravel/post?id=pNaxos01 !"))
        assertNull(CommunityLinks.find("https://justdataplease.com/apps/boringtravel/post/#"))
        assertNull(CommunityLinks.find("https://example.com/apps/boringtravel/post/#pNaxos01"))
        val post = Post("pNaxos01", "u1", "coastwalker", PostKind.Trip, "Naxos by ferry")
        assertEquals("Naxos by ferry · @coastwalker on BoringTravel\nhttps://justdataplease.com/apps/boringtravel/post/#pNaxos01", CommunityLinks.shareText(post))
        assertEquals("post:pNaxos01", Deeplink.of("https://justdataplease.com/apps/boringtravel/post/#pNaxos01"))
        assertEquals("post:pNaxos01", Deeplink.of("boringtravel://post/#pNaxos01"))
        assertEquals("post:pNaxos01", Deeplink.of("boringtravel://post/pNaxos01"))
        assertEquals("post:pNaxos01", Deeplink.of("BoringTravel://post?id=pNaxos01"))
        assertNull(Deeplink.of("boringtravel://post/#not an id"))
        assertEquals("write", Deeplink.of("boringtravel://write"))
        assertEquals("pNaxos01", Deeplink.postOf("post:pNaxos01"))
        assertNull(Deeplink.postOf("write"))
        assertTrue(Post.isId("pNaxos01"))
        assertFalse(Post.isId("posts/x"))
        assertTrue(PublicDrive.isId("drvPatagonia01"))
        assertFalse(PublicDrive.isId("not a drive id!"))
    }

    @Test fun theLeaderboardsRanks() {
        fun post(id: String, uid: String, likes: Int, at: Long) = Post(id, uid, uid, PostKind.Trip, id, likes = likes, createdAt = at)
        val rows = Leaderboard.rank(listOf(post("c", "u3", 3, 1), post("b", "u2", 5, 2), post("a", "u1", 5, 1), post("d", "u4", 9, 1)), hidden = setOf("u4"))
        assertEquals(listOf("a" to 1, "b" to 1, "c" to 3), rows.map { it.post.id to it.rank })
        assertEquals(270f, ProfileAvatar.normal(-90f))
    }

    @Test fun aSharedTripIsReadSafelyAndFitted() {
        val raw = """{"v":1,"days":999,"items":[""" +
            """{"day":2,"title":"Ναός","lat":200.0,"lng":20.0,"time":2000,"link":"javascript:alert(1)"},""" +
            """{"day":1,"title":"Port","lat":37.1,"lng":25.3,"time":480,"link":" https://example.com/port "}],""" +
            """"notes":[{"day":1,"title":"Ferry","photos":["drvPhoto01","not a drive id!","drvPhoto01"]}]}"""
        val t = SharedTrips.decode(raw)
        assertNotNull(t)
        assertEquals(400, t.days)
        assertNull(t.items[0].lat)
        assertNull(t.items[0].lng)
        assertNull(t.items[0].time)
        assertNull(t.items[0].link)
        assertEquals("Ναός", t.items[0].title)
        assertEquals(37.1, t.items[1].lat)
        assertEquals(480, t.items[1].time)
        assertEquals("https://example.com/port", t.items[1].link)
        assertEquals(listOf("drvPhoto01"), t.notes.single().photos)
        assertNull(SharedTrips.decode("""{"v":2,"days":1}"""))
        assertNull(SharedTrips.decode("not json"))
        // Greek is two bytes a letter: the size is UTF-8's, as Android counts it.
        val long = SharedTrip(days = 1, items = listOf(SharedItem(1, "Port")), notes = listOf(SharedNote(1, null, "Λιμάνι", "λ".repeat(3_000))))
        assertTrue(SharedTrips.bytes(long) > 6_000)
        val fitted = SharedTrips.fit(long, 2_000)
        assertNotNull(fitted)
        assertTrue(SharedTrips.bytes(fitted) <= 2_000)
        assertEquals(long.items, fitted.items)
        assertEquals(long, SharedTrips.decode(SharedTrips.encode(long)))
    }

    @Test fun copyThisTrip() {
        val shared = SharedTrip(
            days = 3,
            items = listOf(
                SharedItem(1, "Port", "transport", "Naxos", 37.1, 25.3, 480, via = "ferry"),
                SharedItem(3, "Beach", "place", "Plaka", 37.05, 25.35, link = "https://example.com/plaka"),
                SharedItem(0, "Kitron", "food", "Halki"),
            ),
        )
        assertTrue(TripCopy.needsStart(shared))
        assertFalse(TripCopy.needsStart(SharedTrip(days = 0, items = listOf(SharedItem(0, "Idea")))))
        var n = 0
        val dated = TripCopy.build(shared, " Naxos ", "pNaxos01", localDate(2027, 6, 17), 1_000L) { "id${n++}" }
        assertEquals("Naxos", dated.trip.title)
        assertEquals(localDate(2027, 6, 17), dated.trip.startDate)
        assertEquals(localDate(2027, 6, 19), dated.trip.endDate)
        assertEquals(listOf(localDate(2027, 6, 17), localDate(2027, 6, 19), null), dated.items.map { it.day })
        assertEquals("community:pNaxos01:0", dated.items[0].externalId)
        assertEquals("https://example.com/plaka", dated.items[1].url)
        val undated = TripCopy.build(shared, "Naxos", "pNaxos01", null, 1_000L) { "id${n++}" }
        assertNull(undated.trip.startDate)
        assertTrue(undated.items.all { it.day == null })
    }

    @Test fun firestoresValuesTimesQueriesAndWrites() {
        val values = listOf<Any?>("Νάξος", 42L, 2.5, true, null, FsTime("2026-11-19T10:00:00.123456Z"), FsRef("projects/p/databases/(default)/documents/posts/a"), listOf("AR", "CL"), mapOf("k" to 1L))
        values.forEach { v -> assertEquals(v, FsValues.decode(FsValues.encode(v))) }
        assertEquals(emptyList<Any?>(), FsValues.decode(FsValues.encode(emptyList<String>())))
        assertEquals(at + 123, FsValues.parseTime("2026-11-19T10:00:00.123456Z"))
        assertEquals("2026-11-19T10:00:00.123000Z", FsValues.formatTime(at + 123))
        assertNull(FsValues.parseTime("2026-02-30T10:00:00Z"))
        assertNull(FsValues.parseTime("19 Nov 2026"))

        val q = FsQuery("posts", listOf(FsQuery.Filter("month", "EQUAL", "2026-11")), listOf(FsQuery.Order("likes", descending = true), FsQuery.Order("createdAt")), limit = 20)
        assertEquals(listOf("likes", "createdAt", "__name__"), q.allOrders.map { it.field })
        val sq = q.json()["structuredQuery"]!!.jsonObject
        assertEquals("""{"fieldFilter":{"field":{"fieldPath":"month"},"op":"EQUAL","value":{"stringValue":"2026-11"}}}""", sq["where"].toString())
        assertEquals("""{"field":{"fieldPath":"__name__"},"direction":"ASCENDING"}""", (sq["orderBy"] as JsonArray).last().toString())
        assertEquals("20", (sq["limit"] as JsonPrimitive).content)
        val doc = FsValues.doc(Json.parseToJsonElement("""{"name":"projects/p/databases/(default)/documents/posts/a1","fields":{"likes":{"integerValue":"7"},"createdAt":{"timestampValue":"2026-11-19T10:00:00Z"}}}""").jsonObject)
        assertNotNull(doc)
        assertEquals("a1", doc.id)
        assertEquals("posts/a1", doc.path)
        assertEquals(7L, doc.long("likes"))
        val next = q.copy(after = q.cursorAfter(doc)).json()["structuredQuery"]!!.jsonObject["startAt"]!!.jsonObject
        assertEquals(
            """{"values":[{"integerValue":"7"},{"timestampValue":"2026-11-19T10:00:00Z"},{"referenceValue":"projects/p/databases/(default)/documents/posts/a1"}],"before":false}""",
            next.toString(),
        )
        val both = FsQuery("posts", listOf(FsQuery.Filter("uid", "IN", listOf("u1", "u2")), FsQuery.Filter("kind", "EQUAL", "trip")))
        assertTrue("compositeFilter" in both.json()["structuredQuery"]!!.jsonObject["where"]!!.jsonObject)

        val like = FsWrites.set("n/likes/u1", mapOf("uid" to "u1", "at" to ServerTime))
        assertEquals(
            """{"update":{"name":"n/likes/u1","fields":{"uid":{"stringValue":"u1"}}},"updateTransforms":[{"fieldPath":"at","setToServerValue":"REQUEST_TIME"}]}""",
            like.toString(),
        )
        val count = FsWrites.update("n", mapOf("likes" to Increment(-1)))
        assertEquals(
            """{"update":{"name":"n","fields":{}},"updateMask":{"fieldPaths":[]},"updateTransforms":[{"fieldPath":"likes","increment":{"integerValue":"-1"}}],"currentDocument":{"exists":true}}""",
            count.toString(),
        )
        assertEquals("""{"delete":"n"}""", FsWrites.delete("n").toString())
    }

    /** Firestore's REST API on a network that answers from a script, recording what it was asked. */
    private class ScriptedNet(private val answer: (NetCall) -> NetAnswer) : Net {
        val calls = mutableListOf<NetCall>()

        override suspend fun get(request: NetRequest): NetAnswer = throw NetException("No connection")

        override suspend fun send(call: NetCall): NetAnswer {
            calls += call
            return answer(call)
        }
    }

    private fun json(call: NetCall, code: Int, body: String) = NetAnswer(code, call.url, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())

    @Test fun firestoresAnswersAStaleTokenAndFailures() = runTest {
        val root = "https://firestore.googleapis.com/v1/projects/test-project/databases/(default)/documents"
        val net = ScriptedNet { c ->
            when {
                c.headers["Authorization"] == "Bearer stale" -> json(c, 401, """{"error":{"code":401,"status":"UNAUTHENTICATED","message":"Expired"}}""")
                c.url == "$root/posts/a1" -> json(c, 200, """{"name":"projects/test-project/databases/(default)/documents/posts/a1","fields":{"title":{"stringValue":"Νάξος"}}}""")
                c.url == "$root/posts/gone" -> json(c, 404, """{"error":{"code":404,"status":"NOT_FOUND","message":"No document"}}""")
                c.url == "$root:runQuery" -> json(
                    c, 200,
                    """[{"document":{"name":"projects/test-project/databases/(default)/documents/posts/a1","fields":{}},"readTime":"2026-11-19T10:00:00Z"},""" +
                        """{"document":{"name":"projects/test-project/databases/(default)/documents/posts/b2","fields":{}},"readTime":"2026-11-19T10:00:00Z"}]""",
                )
                c.url == "$root/users/u1:runAggregationQuery" -> json(c, 200, """[{"result":{"aggregateFields":{"n":{"integerValue":"7"}}},"readTime":"2026-11-19T10:00:00Z"}]""")
                c.url == "$root:commit" -> json(c, 200, """{"commitTime":"2026-11-19T10:00:00Z"}""")
                else -> json(c, 503, """{"error":{"code":503,"status":"UNAVAILABLE","message":"Down"}}""")
            }
        }
        val asked = mutableListOf<Boolean>()
        val fs = FirestoreRest(net, "test-project", { fresh -> asked += fresh; if (fresh) "fresh" else "stale" })
        // A stale ID token is renewed once, as the SDK renews it.
        assertEquals("Νάξος", fs.get("posts/a1")?.string("title"))
        assertEquals(listOf("Bearer stale", "Bearer fresh"), net.calls.map { it.headers["Authorization"] })
        assertTrue(asked.contains(true))
        val fresh = FirestoreRest(net, "test-project", { "fresh" })
        assertNull(fresh.get("posts/gone"))
        assertEquals(listOf("a1", "b2"), fresh.query(null, FsQuery("posts", limit = 2)).map { it.id })
        assertEquals(7L, fresh.count("users/u1", "followers"))
        fresh.commit(listOf(FsWrites.delete(fresh.name("posts/a1"))))
        assertEquals("""{"writes":[{"delete":"projects/test-project/databases/(default)/documents/posts/a1"}]}""", net.calls.last().body!!.decodeToString())
        val down = assertFailsWith<FirestoreException> { fresh.get("posts/other") }
        assertEquals("UNAVAILABLE", down.status)
        assertTrue(FirestoreCommunity.offline(down))
        assertTrue(FirestoreCommunity.mapped(down) is CommunityOfflineException)
        assertTrue(FirestoreCommunity.mapped(FirestoreException(403, "PERMISSION_DENIED", "No")) is CommunityRefusedException)
        assertTrue(FirestoreCommunity.mapped(FirestoreException(401, "UNAUTHENTICATED", "No")) is NotSignedInException)
        assertEquals("The community is busy. Try again later.", FirestoreCommunity.mapped(FirestoreException(429, "RESOURCE_EXHAUSTED", "No")).message)
        assertTrue(FirestoreCommunity.offline(NetException("No connection")))
        assertFalse(FirestoreCommunity.offline(FirestoreException(403, "PERMISSION_DENIED", "No")))
    }

    @Test fun writesWaitingAndDocumentsKept() = runTest {
        val outbox = CommunityOutbox(MemorySettingsFile())
        val a = CommunityOutbox.Entry("u1", "like", "pNaxos01", CommunityOutbox.writesText(listOf(FsWrites.delete("n"))))
        val b = CommunityOutbox.Entry("u1", "block", "u2", "[]", "trailnotes")
        outbox.add(a)
        outbox.add(b)
        outbox.add(CommunityOutbox.Entry("u9", "follow", "u1", "[]"))
        assertEquals(listOf(a, b), outbox.of("u1"))
        assertEquals("""[{"delete":"n"}]""", JsonArray(outbox.of("u1").first().commit).toString())
        outbox.remove(a)
        assertEquals(listOf(b), outbox.of("u1"))
        outbox.clear("u1")
        assertTrue(outbox.of("u1").isEmpty())
        assertEquals(1, outbox.of("u9").size)

        var clock = 0L
        val files = MemoryMediaFiles()
        val cache = CommunityCache(files, maxBytes = 4_000, now = { clock++ })
        fun doc(id: String, title: String) = FsValues.doc(
            Json.parseToJsonElement("""{"name":"projects/p/databases/(default)/documents/posts/$id","fields":{"title":{"stringValue":"$title"}}}""").jsonObject,
        )!!
        cache.putPage("latest", listOf(doc("a1", "Νάξος"), doc("b2", "Πάρος")))
        assertEquals(listOf("Νάξος", "Πάρος"), cache.page("latest")?.map { it.string("title") })
        assertEquals("Νάξος", cache.doc("posts/a1")?.string("title"))
        cache.dropDoc("posts/a1")
        assertNull(cache.doc("posts/a1"))
        assertEquals(listOf("b2"), cache.page("latest")?.map { it.id })
        // Past its size the least recently used go first; a page never kept is null, not empty.
        repeat(40) { cache.putDoc(doc("x$it", "x".repeat(200))) }
        assertNull(cache.doc("posts/x0"))
        assertNotNull(cache.doc("posts/x39"))
        assertNull(cache.page("top"))
        cache.clear()
        assertNull(cache.doc("posts/x39"))
        assertNull(cache.page("latest"))
    }
}
