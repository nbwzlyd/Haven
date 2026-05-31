package sh.haven.core.reticulum.sftp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.reticulum.ReticulumExecSession
import sh.haven.core.reticulum.sftp.SftpV3Codec.ATTR_ACMODTIME
import sh.haven.core.reticulum.sftp.SftpV3Codec.ATTR_PERMISSIONS
import sh.haven.core.reticulum.sftp.SftpV3Codec.ATTR_SIZE
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_DATA
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_HANDLE
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_INIT
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_NAME
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_STATUS
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_VERSION
import sh.haven.core.reticulum.sftp.SftpV3Codec.FX_EOF
import sh.haven.core.reticulum.sftp.SftpV3Codec.FX_NO_SUCH_FILE
import sh.haven.core.reticulum.sftp.SftpV3Codec.FX_OK
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.IOException

class SftpV3ClientTest {

    private val HANDLE = byteArrayOf(1, 2, 3, 4)

    /** A fake exec session: reassembles client request packets, lets the test feed (optionally split) server bytes. */
    private class FakeExec : ReticulumExecSession {
        private val stdoutChan = Channel<ByteArray>(Channel.UNLIMITED)
        override val stdout: Flow<ByteArray> = stdoutChan.receiveAsFlow()
        override val stderr: Flow<ByteArray> = emptyFlow()
        override val exitCode = CompletableDeferred<Int>()
        var closeStdinCalled = false
        var closeCalled = false

        data class Req(val type: Int, val id: Int)
        private val reqChan = Channel<Req>(Channel.UNLIMITED)
        private var acc = ByteArray(0)

        override suspend fun writeStdin(data: ByteArray) {
            acc += data
            var pos = 0
            while (acc.size - pos >= 4) {
                val len = u32(acc, pos)
                if (acc.size - pos - 4 < len) break
                val body = acc.copyOfRange(pos + 4, pos + 4 + len); pos += 4 + len
                val din = DataInputStream(ByteArrayInputStream(body))
                val type = din.readUnsignedByte()
                val id = din.readInt() // for INIT this is the version field; harmless
                reqChan.trySend(Req(type, id))
            }
            acc = acc.copyOfRange(pos, acc.size)
        }

        override suspend fun closeStdin() { closeStdinCalled = true }
        override fun close() { closeCalled = true; stdoutChan.close() }

        suspend fun takeRequest(): Req = reqChan.receive()
        fun feed(bytes: ByteArray, chunk: Int = bytes.size) {
            var o = 0
            while (o < bytes.size) { val e = minOf(o + chunk, bytes.size); stdoutChan.trySend(bytes.copyOfRange(o, e)); o = e }
        }
        private fun u32(b: ByteArray, o: Int) =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }

    // --- server-side framed packet builders ---
    private fun frame(block: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream(); DataOutputStream(body).use { it.block() }
        val bb = body.toByteArray()
        val out = ByteArrayOutputStream(); DataOutputStream(out).use { it.writeInt(bb.size); it.write(bb) }
        return out.toByteArray()
    }
    private fun DataOutputStream.sstr(s: String) { val b = s.toByteArray(); writeInt(b.size); write(b) }
    private fun DataOutputStream.attrs(size: Long, perms: Int, mtime: Int) {
        writeInt(ATTR_SIZE or ATTR_PERMISSIONS or ATTR_ACMODTIME); writeLong(size); writeInt(perms); writeInt(mtime); writeInt(mtime)
    }
    private fun sVersion(v: Int) = frame { writeByte(FXP_VERSION); writeInt(v) }
    private fun sHandle(id: Int) = frame { writeByte(FXP_HANDLE); writeInt(id); writeInt(HANDLE.size); write(HANDLE) }
    private fun sStatus(id: Int, code: Int, msg: String = "") = frame { writeByte(FXP_STATUS); writeInt(id); writeInt(code); sstr(msg) }
    private fun sData(id: Int, d: ByteArray) = frame { writeByte(FXP_DATA); writeInt(id); writeInt(d.size); write(d) }
    private fun sName(id: Int, entries: List<Triple<String, Long, Int>>) = frame {
        writeByte(FXP_NAME); writeInt(id); writeInt(entries.size)
        for ((name, size, perms) in entries) { sstr(name); sstr("longname $name"); attrs(size, perms, 1_700_000_000) }
    }

    @Test
    fun `handshake completes on VERSION`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        val server = launch { val r = fake.takeRequest(); assertEquals(FXP_INIT, r.type); fake.feed(sVersion(3)) }
        withTimeout(5_000) { client.handshake() }
        server.cancel(); client.close()
    }

    @Test
    fun `handshake drops the listener's echoed request packets in any order`() = runBlocking {
        // The markqvist rnsh listener mirrors our stdin (whole request packets)
        // onto stdout; the echoed FXP_INIT can arrive before OR after VERSION.
        // Either way it must be ignored (the device-verify regression for #21).
        for (echoFirst in listOf(true, false)) {
            val fake = FakeExec(); val client = SftpV3Client(fake)
            val echoedInit = frame { writeByte(FXP_INIT); writeInt(3) }
            val server = launch {
                assertEquals(FXP_INIT, fake.takeRequest().type)
                if (echoFirst) { fake.feed(echoedInit); fake.feed(sVersion(3)) }
                else { fake.feed(sVersion(3)); fake.feed(echoedInit) }
            }
            withTimeout(5_000) { client.handshake() }
            server.cancel(); client.close()
        }
    }

    @Test
    fun `readDir loop returns names then null at EOF`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        val server = launch {
            assertEquals(FXP_INIT, fake.takeRequest().type); fake.feed(sVersion(3))
            fake.feed(sHandle(fake.takeRequest().id))             // OPENDIR -> handle
            fake.feed(sName(fake.takeRequest().id, listOf(        // READDIR -> 2 names
                Triple("sub", 4096L, 0x4000 or 0x1ED),
                Triple("f.txt", 123L, 0x8000 or 0x1A4),
            )))
            fake.feed(sStatus(fake.takeRequest().id, FX_EOF))     // READDIR -> EOF
        }
        client.handshake()
        val handle = client.openDir("/d")
        assertArrayEquals(HANDLE, handle)
        val first = client.readDir(handle)!!
        assertEquals(listOf("sub", "f.txt"), first.map { it.filename })
        assertTrue(first[0].attrs.isDirectory)
        assertEquals(123L, first[1].attrs.size)
        assertEquals(null, client.readDir(handle))
        server.cancel(); client.close()
    }

    @Test
    fun `read loop returns data then null at EOF`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        val payload = ByteArray(500) { (it and 0xFF).toByte() }
        val server = launch {
            assertEquals(FXP_INIT, fake.takeRequest().type); fake.feed(sVersion(3))
            fake.feed(sHandle(fake.takeRequest().id))           // OPEN -> handle
            fake.feed(sData(fake.takeRequest().id, payload))    // READ -> data
            fake.feed(sStatus(fake.takeRequest().id, FX_EOF))   // READ -> EOF
        }
        client.handshake()
        val h = client.openRead("/f")
        assertArrayEquals(payload, client.read(h, 0, 32768))
        assertEquals(null, client.read(h, 500, 32768))
        server.cancel(); client.close()
    }

    @Test
    fun `stat on missing maps to FileNotFoundException`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        val server = launch {
            assertEquals(FXP_INIT, fake.takeRequest().type); fake.feed(sVersion(3))
            fake.feed(sStatus(fake.takeRequest().id, FX_NO_SUCH_FILE, "No such file"))
        }
        client.handshake()
        var thrown = false
        try { client.stat("/nope") } catch (e: FileNotFoundException) { thrown = true }
        assertTrue("expected FileNotFoundException", thrown)
        server.cancel(); client.close()
    }

    @Test
    fun `reader reassembles a response split across 7-byte deltas`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        val server = launch {
            assertEquals(FXP_INIT, fake.takeRequest().type); fake.feed(sVersion(3), chunk = 7)
            fake.feed(sHandle(fake.takeRequest().id), chunk = 7)
            // a NAME packet (well over 7 bytes) fed 7 bytes at a time
            fake.feed(sName(fake.takeRequest().id, listOf(
                Triple("alpha", 1L, 0x8000 or 0x1A4),
                Triple("beta", 2L, 0x4000 or 0x1ED),
                Triple("gamma-with-a-longer-name.bin", 3L, 0x8000 or 0x1A4),
            )), chunk = 7)
            fake.feed(sStatus(fake.takeRequest().id, FX_EOF), chunk = 7)
        }
        withTimeout(5_000) { client.handshake() }
        val h = client.openDir("/d")
        val names = client.readDir(h)!!.map { it.filename }
        assertEquals(listOf("alpha", "beta", "gamma-with-a-longer-name.bin"), names)
        assertEquals(null, client.readDir(h))
        server.cancel(); client.close()
    }

    @Test
    fun `close tears down without a stdin EOF`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        client.close()
        assertTrue(fake.closeCalled)
        assertFalse("close() must never send a stdin-EOF", fake.closeStdinCalled)
        assertTrue(client.isClosed)
    }

    @Test
    fun `pending op fails (not hangs) when the link closes mid-request`() = runBlocking {
        val fake = FakeExec(); val client = SftpV3Client(fake)
        val server = launch {
            assertEquals(FXP_INIT, fake.takeRequest().type); fake.feed(sVersion(3))
            fake.takeRequest() // receive the stat request but never respond
            client.close()     // link drops mid-op
        }
        client.handshake()
        var failed = false
        try { withTimeout(5_000) { client.stat("/x") } } catch (e: IOException) { failed = true }
        assertTrue("op must fail when link closes", failed)
        server.cancel()
    }
}
