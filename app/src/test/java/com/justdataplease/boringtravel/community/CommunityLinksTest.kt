package com.justdataplease.boringtravel.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityLinksTest {
    @Test fun aPostsLinkCarriesItsIdAfterTheHash() {
        assertEquals("https://justdataplease.com/apps/boringtravel/post/#Ab3dE6gH9jKl2nOp4rSt", CommunityLinks.url("Ab3dE6gH9jKl2nOp4rSt"))
    }

    @Test fun linksAreFoundInTextAndOpenedLinks() {
        assertEquals("Ab3dE6gH9j", CommunityLinks.find("Look: https://justdataplease.com/apps/boringtravel/post/#Ab3dE6gH9j nice"))
        assertEquals("Ab3dE6gH9j", CommunityLinks.find("https://www.justdataplease.com/apps/boringtravel/post#Ab3dE6gH9j"))
        assertEquals("Ab3dE6gH9j", CommunityLinks.find("https://justdataplease.com/apps/boringtravel/post/?id=Ab3dE6gH9j"))
        assertNull(CommunityLinks.find("https://justdataplease.com/apps/boringtravel/join/#v=1&t=x"))
        assertNull(CommunityLinks.find("https://example.com/apps/boringtravel/post/#Ab3dE6gH9j"))
        assertNull(CommunityLinks.find(null))
    }

    @Test fun onlyIdsTheAppMakesArePostIds() {
        assertEquals(true, Post.isId("Ab3dE6gH9jKl2nOp4rSt"))
        assertEquals(true, Post.isId("header")) // valid, which is why lists key posts as "post:<id>"
        assertEquals(true, Post.isId("a-b_c"))
        for (bad in listOf(null, "", "a.b", "a b", "ä1", "x".repeat(65))) assertEquals(bad, false, Post.isId(bad))
    }

    @Test fun shareTextNamesThePostAndItsPoster() {
        val post = Post("Ab3dE6gH9j", "u1", "alexa", PostKind.Trip, "Patagonia")
        assertEquals(
            "Patagonia · @alexa on BoringTravel\nhttps://justdataplease.com/apps/boringtravel/post/#Ab3dE6gH9j",
            CommunityLinks.shareText(post),
        )
    }
}
