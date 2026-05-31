package sh.haven.core.reticulum.sftp

import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_ATTRS
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_DATA
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_HANDLE
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_NAME
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_OPEN
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_STATUS
import sh.haven.core.reticulum.sftp.SftpV3Codec.FXP_VERSION
import sh.haven.core.reticulum.sftp.SftpV3Codec.SftpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException

class SftpV3CodecTest {

    // --- helpers: build a server-shaped packet BODY (type + payload, no length prefix) ---
    private fun body(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }

    private fun DataOutputStream.sstr(s: String) { val b = s.toByteArray(); writeInt(b.size); write(b) }
    private fun DataOutputStream.sbin(b: ByteArray) { writeInt(b.size); write(b) }

    @Test
    fun `buildInit emits length, type, version`() {
        val pkt = SftpV3Codec.buildInit()
        val din = DataInputStream(ByteArrayInputStream(pkt))
        assertEquals(pkt.size - 4, din.readInt())   // length covers everything after the prefix
        assertEquals(SftpV3Codec.FXP_INIT, din.readUnsignedByte())
        assertEquals(SftpV3Codec.PROTOCOL_VERSION, din.readInt())
    }

    @Test
    fun `buildOpen frames type, id, path, pflags, empty attrs`() {
        val pkt = SftpV3Codec.buildOpen(9, "/a/b", SftpV3Codec.FXF_READ or SftpV3Codec.FXF_WRITE)
        val din = DataInputStream(ByteArrayInputStream(pkt))
        assertEquals(pkt.size - 4, din.readInt())
        assertEquals(FXP_OPEN, din.readUnsignedByte())
        assertEquals(9, din.readInt())
        val n = din.readInt(); val pb = ByteArray(n); din.readFully(pb)
        assertEquals("/a/b", String(pb))
        assertEquals(SftpV3Codec.FXF_READ or SftpV3Codec.FXF_WRITE, din.readInt())
        assertEquals(0, din.readInt()) // empty attrs
    }

    @Test
    fun `buildRealPath round-trips a UTF-8 multibyte path`() {
        val path = "/файл/日本/a.txt"
        val pkt = SftpV3Codec.buildRealPath(3, path)
        val din = DataInputStream(ByteArrayInputStream(pkt))
        din.readInt() // length
        assertEquals(SftpV3Codec.FXP_REALPATH, din.readUnsignedByte())
        assertEquals(3, din.readInt())
        val n = din.readInt(); val pb = ByteArray(n); din.readFully(pb)
        assertEquals(path, String(pb, Charsets.UTF_8))
    }

    @Test
    fun `buildWrite carries a data slice at offset`() {
        val data = ByteArray(10) { it.toByte() }
        val pkt = SftpV3Codec.buildWrite(1, byteArrayOf(0xAB.toByte()), 4096L, data, 2, 5)
        val din = DataInputStream(ByteArrayInputStream(pkt))
        din.readInt()
        assertEquals(SftpV3Codec.FXP_WRITE, din.readUnsignedByte())
        assertEquals(1, din.readInt())
        val hl = din.readInt(); din.skipBytes(hl)
        assertEquals(4096L, din.readLong())
        val dl = din.readInt(); val d = ByteArray(dl); din.readFully(d)
        assertArrayEquals(byteArrayOf(2, 3, 4, 5, 6), d)
    }

    @Test
    fun `parses VERSION with extensions`() {
        val pkt = SftpV3Codec.parsePacket(body {
            writeByte(FXP_VERSION); writeInt(3)
            sstr("posix-rename@openssh.com"); sstr("1")
            sstr("hardlink@openssh.com"); sstr("1")
        }) as SftpPacket.Version
        assertEquals(3, pkt.version)
        assertEquals("1", pkt.extensions["posix-rename@openssh.com"])
        assertEquals(2, pkt.extensions.size)
    }

    @Test
    fun `parses STATUS and maps codes to exceptions`() {
        val s = SftpV3Codec.parsePacket(body {
            writeByte(FXP_STATUS); writeInt(7); writeInt(SftpV3Codec.FX_NO_SUCH_FILE); sstr("No such file")
        }) as SftpPacket.Status
        assertEquals(7, s.id)
        assertTrue(SftpV3Codec.statusToException(s) is FileNotFoundException)

        val denied = SftpPacket.Status(1, SftpV3Codec.FX_PERMISSION_DENIED, "nope")
        val ex = SftpV3Codec.statusToException(denied)
        assertFalse(ex is FileNotFoundException)
        assertTrue(ex.message!!.contains("permission denied"))
    }

    @Test
    fun `parses HANDLE and DATA as binary`() {
        val h = SftpV3Codec.parsePacket(body { writeByte(FXP_HANDLE); writeInt(1); sbin(byteArrayOf(0, 1, 2, 3)) }) as SftpPacket.Handle
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), h.handle)

        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        val d = SftpV3Codec.parsePacket(body { writeByte(FXP_DATA); writeInt(2); sbin(payload) }) as SftpPacket.Data
        assertEquals(2, d.id)
        assertArrayEquals(payload, d.data)
    }

    @Test
    fun `parses NAME with attrs - dir vs file, size, mtime`() {
        val mtimeDir = 1_700_000_000
        val pkt = SftpV3Codec.parsePacket(body {
            writeByte(FXP_NAME); writeInt(5); writeInt(2)
            // dir "sub", mode 0755
            sstr("sub"); sstr("drwxr-xr-x 2 ian ian 4096 sub")
            writeInt(SftpV3Codec.ATTR_SIZE or SftpV3Codec.ATTR_PERMISSIONS or SftpV3Codec.ATTR_ACMODTIME)
            writeLong(4096L); writeInt(0x4000 or 0x1ED); writeInt(mtimeDir); writeInt(mtimeDir)
            // file "f.txt", mode 0644
            sstr("f.txt"); sstr("-rw-r--r-- 1 ian ian 123 f.txt")
            writeInt(SftpV3Codec.ATTR_SIZE or SftpV3Codec.ATTR_PERMISSIONS or SftpV3Codec.ATTR_ACMODTIME)
            writeLong(123L); writeInt(0x8000 or 0x1A4); writeInt(mtimeDir + 1); writeInt(mtimeDir + 1)
        }) as SftpPacket.Name

        assertEquals(listOf("sub", "f.txt"), pkt.names.map { it.filename })
        val dir = pkt.names[0]
        assertTrue(dir.attrs.isDirectory)
        assertEquals(4096L, dir.attrs.size)
        assertEquals("drwxr-xr-x", dir.attrs.permString())
        assertEquals(mtimeDir * 1000L, dir.attrs.modifiedTimeMillis)
        val file = pkt.names[1]
        assertFalse(file.attrs.isDirectory)
        assertEquals(123L, file.attrs.size)
        assertEquals("-rw-r--r--", file.attrs.permString())
    }

    @Test
    fun `parses ATTRS and detects symlink`() {
        val pkt = SftpV3Codec.parsePacket(body {
            writeByte(FXP_ATTRS)
            writeInt(11)
            writeInt(SftpV3Codec.ATTR_SIZE or SftpV3Codec.ATTR_PERMISSIONS)
            writeLong(7L); writeInt(0xA000 or 0x1FF)
        }) as SftpPacket.Attrs
        assertEquals(11, pkt.id)
        assertTrue(pkt.attrs.isSymlink)
        assertFalse(pkt.attrs.isDirectory)
        assertEquals(7L, pkt.attrs.size)
    }

    @Test
    fun `attrs with no flags yields unknown permString and zero mtime`() {
        val pkt = SftpV3Codec.parsePacket(body { writeByte(FXP_ATTRS); writeInt(1); writeInt(0) }) as SftpPacket.Attrs
        assertEquals("", pkt.attrs.permString())
        assertEquals(0L, pkt.attrs.modifiedTimeMillis)
        assertFalse(pkt.attrs.isDirectory)
    }
}
