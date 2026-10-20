// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.steam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CloudTest {
    private fun sha(text: String) = ContentDecryptor.sha1(text.toByteArray()).hex()

    @Test fun listingAndPaths() {
        val body = proto {
            uint64(1, 77)
            message(2) { string(1, "save1.sav"); bytes(2, ContentDecryptor.sha1("a".toByteArray())); uint64(3, 100); uint64(4, 1); int32Always(7, 0) }
            message(2) { string(1, "gone.sav"); uint32(5, 2) }
            string(4, "%WinMyDocuments%My Games/X/")
        }
        val listing = CloudListing.parse(body)
        assertEquals(77L, listing.changeNumber)
        assertEquals("%WinMyDocuments%My Games/X/save1.sav", listing.files[0].path)
        assertEquals(Pair<String?, String>("WinMyDocuments", "My Games/X/save1.sav"), CloudPaths.split(listing.files[0].path))
        assertEquals(Pair<String?, String>(null, "plain.dat"), CloudPaths.split("plain.dat"))
        val root = File("/drive")
        val paths = CloudPaths(root, File(root, "users/steamuser"), File(root, "game"), File(root, "remote"), 76561198000000001L,
            listOf(RootOverride("WinAppDataLocal", "Windows", "WinMyDocuments", "Extra")))
        assertEquals(File("/drive/users/steamuser/Documents"), paths.location("%WinAppDataLocal%a.sav")!!.first)
        assertEquals(listOf("Extra", "a.sav"), paths.location("%WinAppDataLocal%a.sav")!!.second)
        assertEquals(listOf("u", "76561198000000001", "39734273"), paths.components("u/{64BitSteamID}/{Steam3AccountID}"))
        assertNull(paths.location("%MacHome%x"))
        assertNull(paths.location("%GameInstall%../x"))
        assertTrue(CloudAudit.matches("*.SAV", "slot1.sav"))
        assertTrue(!CloudAudit.matches("slot?.sav", "slot10.sav"))
    }

    @Test fun planDecisions() {
        fun e(path: String, kind: CloudEntry.Kind, cloud: String = "", local: String = "") = CloudEntry(path, kind, cloudSHA = cloud, localSHA = local)
        val audit = CloudAudit(1, 5, listOf(
            e("same", CloudEntry.Kind.SAME, "a", "a"),
            e("up", CloudEntry.Kind.DIFFER, "a", "b"),
            e("down", CloudEntry.Kind.DIFFER, "c", "a"),
            e("both", CloudEntry.Kind.DIFFER, "c", "d"),
            e("new-cloud", CloudEntry.Kind.CLOUD_ONLY, "z"),
            e("lost", CloudEntry.Kind.CLOUD_ONLY, "y"),
            e("new-local", CloudEntry.Kind.LOCAL_ONLY, local = "q"),
            e("deleted-elsewhere", CloudEntry.Kind.LOCAL_ONLY, local = "q"),
        ), emptyList())
        val plan = CloudPlan.make(audit, mapOf("up" to "a", "down" to "a", "both" to "a", "lost" to "y", "deleted-elsewhere" to "q"))
        assertEquals(listOf("up", "new-local"), plan.upload.map { it.path })
        assertEquals(listOf("down", "new-cloud"), plan.download.map { it.path })
        assertEquals(listOf("both", "lost"), plan.conflicts.map { it.path })
        assertEquals("missing:y", plan.settled["lost"])
        assertEquals("a", plan.settled["same"])
    }

    @Test fun auditFindsSavesBothSides() {
        val drive = Files.createTempDirectory("drive").toFile()
        try {
            val docs = File(drive, "users/steamuser/Documents/My Games/X").apply { mkdirs() }
            File(docs, "Slot1.sav").writeText("one")
            File(docs, "slot2.sav").writeText("two-local")
            File(docs, "notes.txt").writeText("ignored")
            val paths = CloudPaths(drive, CloudPaths.userFolder(drive)!!, File(drive, "game"), File(drive, "remote"), 1, emptyList())
            val listing = CloudListing(5, listOf(
                CloudFile("%WinMyDocuments%My Games/X/", "slot1.sav", ContentDecryptor.sha1("one".toByteArray()), 1, 3, 0),
                CloudFile("%WinMyDocuments%My Games/X/", "slot2.sav", ContentDecryptor.sha1("two".toByteArray()), 1, 3, 0),
                CloudFile("%WinMyDocuments%My Games/X/", "slot3.sav", ContentDecryptor.sha1("3".toByteArray()), 1, 1, 0),
            ))
            File(docs, "slot4.sav").writeText("four")
            val audit = CloudAudit.run(listing, listOf(SaveFile("WinMyDocuments", "My Games/X", "*.sav", false, emptyList())), paths)
            val kinds = audit.entries.associate { it.name to it.kind }
            assertEquals(CloudEntry.Kind.SAME, kinds["slot1.sav"])
            assertEquals(CloudEntry.Kind.DIFFER, kinds["slot2.sav"])
            assertEquals(CloudEntry.Kind.CLOUD_ONLY, kinds["slot3.sav"])
            assertEquals(CloudEntry.Kind.LOCAL_ONLY, kinds["slot4.sav"])
            assertEquals("%WinMyDocuments%My Games/X/slot4.sav", audit.entries.first { it.name == "slot4.sav" }.path)
            assertEquals(4, audit.entries.size)
            val backup = File(drive, "backup")
            CloudTransfer.place("new".toByteArray(), File(docs, "Slot1.sav"), backup, listOf("WinMyDocuments", "Slot1.sav"), 1000)
            assertEquals("one", File(backup, "WinMyDocuments/Slot1.sav").readText())
            assertEquals("new", File(docs, "Slot1.sav").readText())
            assertEquals(1_000_000L, File(docs, "Slot1.sav").lastModified())
        } finally { drive.deleteRecursively() }
    }

    @Test fun downloadInfoUploadBlocksAndZip() {
        val info = CloudDownloadInfo.parse(proto {
            uint64(2, 10); uint64(3, 20); string(7, "cloud-3.steamusercontent.com"); string(8, "ugc/1"); bool(9, true)
            message(10) { string(1, "x-a"); string(2, "b") }
        })
        assertEquals("https://cloud-3.steamusercontent.com/ugc/1", info.url)
        assertEquals(listOf("x-a" to "b"), info.headers)
        val (encrypt, blocks) = CloudUploadBlock.parse(proto {
            message(2) { string(1, "h.example.com"); string(2, "/p"); bool(3, true); uint32(4, 3); uint64(6, 0); uint64(7, 5) }
        })
        assertTrue(!encrypt); assertEquals(3, blocks[0].method); assertEquals(5L, blocks[0].length)
        val data = "hello cloud".repeat(30).toByteArray()
        assertArrayEquals(data, CloudTransfer.unzipFirstEntry(zipSingle("f", data), data.size))
        assertArrayEquals(data, CloudTransfer.unzipFirstEntry(zipSingle("f", data, deflate = false), data.size))
        assertNull(CloudTransfer.unzipFirstEntry(zipSingle("f", data), data.size + 1))
    }
}
