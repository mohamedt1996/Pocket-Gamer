// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.steam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts like Steam's content servers: AES-ECB(IV) + AES-CBC/PKCS7 body. */
internal fun steamEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray = ByteArray(16) { (it * 7).toByte() }): ByteArray {
    val k = SecretKeySpec(key, "AES")
    val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
    val cbc = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, k, IvParameterSpec(iv)) }
    return ecb.doFinal(iv) + cbc.doFinal(plain)
}

internal fun zipSingle(name: String, data: ByteArray, deflate: Boolean = true): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        val e = ZipEntry(name)
        if (!deflate) {
            e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
            e.crc = CRC32().apply { update(data) }.value
        }
        z.putNextEntry(e); z.write(data); z.closeEntry()
    }
    return out.toByteArray()
}

class ProtoAndContentTest {
    @Test fun varintsAndNegativeInt32() {
        val bytes = proto { uint32(1, 300); int32(2, -1); uint64(3, Long.MIN_VALUE); bool(4, true); string(5, "") }
        var a = 0; var b = 0; var c = 0L; var d = false
        bytes.proto().fields { f -> when (f) { 1 -> a = int32(); 2 -> b = int32(); 3 -> c = varint(); 4 -> d = bool(); else -> skip() } }
        assertEquals(300, a); assertEquals(-1, b); assertEquals(Long.MIN_VALUE, c); assertTrue(d)
        // int32 -1 is a ten-byte varint, as protobuf requires.
        assertEquals(1 + 2 + 1 + 10 + 1 + 10 + 2, bytes.size)
    }

    @Test fun headerAlwaysCarriesSteamIDAndSession() {
        val h = ProtoHeader().serialize()
        var fields = 0
        h.proto().fields { _ -> fields++; skip() }
        assertEquals(2, fields)
        val full = ProtoHeader(steamID = 76561197960265728L, sessionID = -5, jobSource = 7, targetJobName = "X#1", eresult = 2)
        val back = ProtoHeader.parse(full.serialize().proto())
        assertEquals(full, back)
    }

    @Test fun codecRoundTripAndOldHeaders() {
        val data = MessageCodec.encodeClientMessage(EMsg.CLIENT_HELLO, CmMessages.hello(), SteamSession.PRE_LOGON_STEAM_ID, 0, 42)
        val m = MessageCodec.decode(data)
        assertEquals(EMsg.CLIENT_HELLO, m.emsg); assertTrue(m.isProtobuf); assertEquals(42L, m.header.jobSource)
        assertEquals(SteamSession.PRE_LOGON_STEAM_ID, m.header.steamID)
        // Simple header: EMsg, target job, source job.
        val simple = ByteArrayOutputStream().apply {
            u32le(751); for (i in 0 until 8) write(if (i == 0) 9 else 0); for (i in 0 until 8) write(0xFF); write(byteArrayOf(5, 0, 0, 0))
        }.toByteArray()
        val s = MessageCodec.decode(simple)
        assertFalse(s.isProtobuf); assertEquals(9L, s.header.jobTarget); assertEquals(NO_JOB, s.header.jobSource); assertEquals(4, s.body.size)
        // Extended header (36 bytes).
        val ext = ByteArray(40).also { b ->
            b[0] = 0x10; b[4] = 36; b[5] = 2
            for (i in 0 until 8) b[7 + i] = 0xFF.toByte()
            b[15] = 3
            b[24] = 1; b[31] = 0x01
            b[32] = 77
        }
        val e = MessageCodec.decode(ext)
        assertEquals(NO_JOB, e.header.jobTarget); assertEquals(3L, e.header.jobSource); assertEquals(77, e.header.sessionID)
        assertEquals(4, e.body.size)
        try { MessageCodec.decode(byteArrayOf(1, 0)); fail("short message accepted") } catch (_: SteamException) {}
    }

    @Test fun lengthPastEndThrows() {
        val bad = byteArrayOf(0x0A, 0x7F, 1, 2)
        try { bad.proto().fields { _ -> bytes() }; fail("accepted") } catch (e: SteamException) { assertTrue(e.error is SteamError.Protobuf) }
    }

    @Test fun lzmaVZipChunk() {
        val vz = Base64.getDecoder().decode(Vectors.VZ)
        val out = ContentDecryptor.decompress(vz, Vectors.PLAIN_SIZE)
        assertEquals(Vectors.PLAIN_SIZE, out.size)
        assertEquals(Vectors.PLAIN_SHA1, ContentDecryptor.sha1(out).hex())
        assertEquals(Vectors.PLAIN_ADLER0, ContentDecryptor.adler32(out))
        // A footer size that disagrees with the manifest is refused.
        try { ContentDecryptor.decompress(vz, Vectors.PLAIN_SIZE - 1); fail("size mismatch accepted") } catch (e: SteamException) {}
        // Corrupt stream: an error, never a crash.
        val broken = vz.copyOf(); for (i in 40 until 80) broken[i] = 0x55
        try { ContentDecryptor.decompress(broken, Vectors.PLAIN_SIZE) } catch (_: SteamException) {}
    }

    @Test fun zstdVSZaChunk() {
        System.getProperty("zstd.lib")?.let { Zstd.load(it) }
        if (!Zstd.available) { println("  (zstd native decoder not loaded on this JVM: skipped)"); return }
        val vsz = Base64.getDecoder().decode(Vectors.VSZ)
        val out = ContentDecryptor.decompress(vsz, Vectors.PLAIN_SIZE)
        assertEquals(Vectors.PLAIN_SHA1, ContentDecryptor.sha1(out).hex())
        val broken = vsz.copyOf(); for (i in 20 until 60) broken[i] = 0x13
        try { ContentDecryptor.decompress(broken, Vectors.PLAIN_SIZE); } catch (_: SteamException) {}
        // Too small a buffer is an error, not a crash.
        try { ContentDecryptor.decompress(vsz, 100); fail("undersized accepted") } catch (_: SteamException) {}
    }

    @Test fun aesChunkPipelineAndAdler() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "Hello, depot!".repeat(100).toByteArray()
        val zip = zipSingle("z", plain)
        val enc = steamEncrypt(zip, key)
        val out = ContentDecryptor.processChunk(enc, key, ContentDecryptor.adler32(plain), plain.size)
        assertArrayEquals(plain, out)
        try { ContentDecryptor.processChunk(enc, key, 12345, plain.size); fail("bad crc accepted") } catch (e: SteamException) { assertEquals(SteamError.ChecksumMismatch, e.error) }
        try { ContentDecryptor.decrypt(enc, ByteArray(16)); fail("short key accepted") } catch (_: SteamException) {}
        // Stored zip and plain data of the expected size.
        assertArrayEquals(plain, ContentDecryptor.decompress(zipSingle("z", plain, deflate = false), plain.size))
        assertArrayEquals(plain, ContentDecryptor.decompress(plain, plain.size))
        // Adler-32 seeded with 0, matching zlib's adler32(0, ...).
        assertEquals(0, ContentDecryptor.adler32(ByteArray(0)))
        assertEquals(0x00610061, ContentDecryptor.adler32(byteArrayOf(0x61)))
    }

    @Test fun manifestWithEncryptedNames() {
        val key = ByteArray(32) { (it * 3).toByte() }
        fun encName(n: String) = Base64.getEncoder().encodeToString(steamEncrypt(n.toByteArray() + ByteArray(3), key))
        val payload = proto {
            message(1) {
                string(1, encName("bin\\Game.exe")); uint64(2, 5000); uint32(3, 0x80)
                message(6) { bytes(1, ByteArray(20) { 1 }); fixed32(2, 99); uint64(3, 0); uint32(4, 4000); uint32(5, 1200) }
                message(6) { bytes(1, ByteArray(20) { 2 }); fixed32(2, 98); uint64(3, 4000); uint32(4, 1000); uint32(5, 700) }
            }
            message(1) { string(1, encName("data")); uint32(3, 0x40) }
        }
        val wrapped = byteArrayOf(0x71, 0x2F, 0x4C, 0x71) + ByteArray(4).also { val n = payload.size; for (i in 0 until 4) it[i] = (n ushr (8 * i)).toByte() } + payload + ByteArray(16)
        val m = DepotManifest.parse(5, 77, DepotManifest.unzipPayload(zipSingle("z", wrapped)), key)
        assertEquals(2, m.files.size)
        assertEquals("bin/Game.exe", m.files[0].filename)
        assertTrue(m.files[0].isCustomExecutable)
        assertEquals(2, m.files[0].chunks.size)
        assertEquals(4000, m.files[0].chunks[0].uncompressedSize)
        assertEquals(1200, m.files[0].chunks[0].compressedSize)
        assertEquals(1900L, m.totalCompressedSize)
        assertTrue(m.files[1].isDirectory)
        // Without the key, names stay as they are.
        assertEquals(encName("x").length, DepotManifest.decryptFilename(encName("x"), null).length)
        assertNull(null)
    }

    @Test fun downloaderParsesWholeEncryptedManifest() {
        val key = ByteArray(32) { 9 }
        val payload = proto { message(1) { string(1, "a.txt"); uint64(2, 3); message(6) { bytes(1, ByteArray(20)); uint32(4, 3); uint32(5, 3) } } }
        val (m, bytes) = DepotDownloader.parseManifestKeepingPayload(steamEncrypt(payload, key), 1, 2, key)
        assertEquals("a.txt", m.files.single().filename)
        assertArrayEquals(payload, bytes)
    }
}
