package sh.haven.core.reticulum.sftp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Minimal SSH File Transfer Protocol **version 3** codec (the OpenSSH default).
 *
 * Pure and stateless: it builds fully-framed request packets and parses one
 * already-complete response packet. It has no I/O and no coroutines — the
 * reassembly of length-prefixed packets from an arbitrary byte stream lives in
 * [SftpV3Client]. This mirrors `core/usb/UsbProxyProtocol`'s big-endian
 * `DataOutputStream`/`DataInputStream` framing idiom.
 *
 * Wire format (RFC draft-ietf-secsh-filexfer-02 / SFTP v3): every packet is
 * `uint32 length · byte type · [uint32 request-id] · type-specific`. All
 * integers are big-endian (Java/`DataOutputStream` default). An SFTP "string"
 * is `uint32 length · bytes` (NOT NUL-terminated); file handles are opaque
 * binary strings kept as [ByteArray] end to end.
 */
object SftpV3Codec {

    const val PROTOCOL_VERSION = 3

    /** Guard against a desynced stream claiming an absurd packet length. */
    const val MAX_PACKET = 1 shl 18 // 256 KiB

    // Packet types — requests (client → server)
    const val FXP_INIT = 1
    const val FXP_VERSION = 2
    const val FXP_OPEN = 3
    const val FXP_CLOSE = 4
    const val FXP_READ = 5
    const val FXP_WRITE = 6
    const val FXP_LSTAT = 7
    const val FXP_FSTAT = 8
    const val FXP_OPENDIR = 11
    const val FXP_READDIR = 12
    const val FXP_REMOVE = 13
    const val FXP_MKDIR = 14
    const val FXP_RMDIR = 15
    const val FXP_REALPATH = 16
    const val FXP_STAT = 17
    const val FXP_RENAME = 18

    // Packet types — responses (server → client)
    const val FXP_STATUS = 101
    const val FXP_HANDLE = 102
    const val FXP_DATA = 103
    const val FXP_NAME = 104
    const val FXP_ATTRS = 105

    // Open flags (pflags)
    const val FXF_READ = 0x00000001
    const val FXF_WRITE = 0x00000002
    const val FXF_APPEND = 0x00000004
    const val FXF_CREAT = 0x00000008
    const val FXF_TRUNC = 0x00000010
    const val FXF_EXCL = 0x00000020

    // ATTRS flags
    const val ATTR_SIZE = 0x00000001
    const val ATTR_UIDGID = 0x00000002
    const val ATTR_PERMISSIONS = 0x00000004
    const val ATTR_ACMODTIME = 0x00000008
    const val ATTR_EXTENDED = -0x80000000 // 0x80000000 as a signed Int

    // Status codes
    const val FX_OK = 0
    const val FX_EOF = 1
    const val FX_NO_SUCH_FILE = 2
    const val FX_PERMISSION_DENIED = 3
    const val FX_FAILURE = 4
    const val FX_BAD_MESSAGE = 5
    const val FX_NO_CONNECTION = 6
    const val FX_CONNECTION_LOST = 7
    const val FX_OP_UNSUPPORTED = 8

    // POSIX mode type bits (in the ATTRS permissions field)
    private const val S_IFMT = 0xF000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000

    // ---- Models ----------------------------------------------------------

    /** SFTP v3 file attributes (only the fields the FileBackend surface needs). */
    data class SftpAttrs(
        val size: Long = 0,
        val uid: Int = 0,
        val gid: Int = 0,
        val permissions: Int = 0,
        val atime: Long = 0,
        val mtime: Long = 0,
        val flags: Int = 0,
    ) {
        val hasPermissions: Boolean get() = flags and ATTR_PERMISSIONS != 0
        val isDirectory: Boolean get() = hasPermissions && (permissions and S_IFMT) == S_IFDIR
        val isSymlink: Boolean get() = hasPermissions && (permissions and S_IFMT) == S_IFLNK

        /** modified-time as epoch millis (v3 mtime is u32 epoch seconds). */
        val modifiedTimeMillis: Long get() = if (flags and ATTR_ACMODTIME != 0) mtime * 1000L else 0L
        /** mtime as epoch SECONDS (SFTP v3 native unit; 0 when unknown). */
        val modifiedTimeSeconds: Long get() = if (flags and ATTR_ACMODTIME != 0) mtime else 0L

        /** A `drwxr-xr-x`-style mode string for display, or "" if unknown. */
        fun permString(): String {
            if (!hasPermissions) return ""
            val type = when (permissions and S_IFMT) {
                S_IFDIR -> 'd'
                S_IFLNK -> 'l'
                0x1000 -> 'p' // FIFO
                0x2000 -> 'c' // char dev
                0x6000 -> 'b' // block dev
                0xC000 -> 's' // socket
                else -> '-'
            }
            val sb = StringBuilder(10).append(type)
            var bit = 0x100
            "rwxrwxrwx".forEach { ch ->
                sb.append(if (permissions and bit != 0) ch else '-')
                bit = bit shr 1
            }
            return sb.toString()
        }
    }

    /** One entry from an FXP_NAME (READDIR / REALPATH) response. */
    data class SftpName(
        val filename: String,
        val longname: String,
        val attrs: SftpAttrs,
    )

    /** A parsed SFTP response packet. */
    sealed interface SftpPacket {
        data class Version(val version: Int, val extensions: Map<String, String>) : SftpPacket
        data class Status(val id: Int, val code: Int, val message: String) : SftpPacket
        data class Handle(val id: Int, val handle: ByteArray) : SftpPacket
        data class Data(val id: Int, val data: ByteArray) : SftpPacket
        data class Name(val id: Int, val names: List<SftpName>) : SftpPacket
        data class Attrs(val id: Int, val attrs: SftpAttrs) : SftpPacket
    }

    /** The request-id a response correlates to, or null for the id-less VERSION. */
    fun SftpPacket.requestId(): Int? = when (this) {
        is SftpPacket.Version -> null
        is SftpPacket.Status -> id
        is SftpPacket.Handle -> id
        is SftpPacket.Data -> id
        is SftpPacket.Name -> id
        is SftpPacket.Attrs -> id
    }

    // ---- Request builders (each returns a fully framed packet) -----------

    fun buildInit(): ByteArray = frame {
        writeByte(FXP_INIT)
        writeInt(PROTOCOL_VERSION)
    }

    fun buildRealPath(id: Int, path: String) = frame { req(FXP_REALPATH, id); str(path) }
    fun buildOpenDir(id: Int, path: String) = frame { req(FXP_OPENDIR, id); str(path) }
    fun buildReadDir(id: Int, handle: ByteArray) = frame { req(FXP_READDIR, id); bin(handle) }
    fun buildClose(id: Int, handle: ByteArray) = frame { req(FXP_CLOSE, id); bin(handle) }
    fun buildStat(id: Int, path: String) = frame { req(FXP_STAT, id); str(path) }
    fun buildLStat(id: Int, path: String) = frame { req(FXP_LSTAT, id); str(path) }
    fun buildRemove(id: Int, path: String) = frame { req(FXP_REMOVE, id); str(path) }
    fun buildRmdir(id: Int, path: String) = frame { req(FXP_RMDIR, id); str(path) }
    fun buildMkdir(id: Int, path: String) = frame { req(FXP_MKDIR, id); str(path); writeInt(0) /* empty attrs */ }
    fun buildRename(id: Int, from: String, to: String) = frame { req(FXP_RENAME, id); str(from); str(to) }

    fun buildOpen(id: Int, path: String, pflags: Int) = frame {
        req(FXP_OPEN, id); str(path); writeInt(pflags); writeInt(0) /* empty attrs */
    }

    fun buildRead(id: Int, handle: ByteArray, offset: Long, len: Int) = frame {
        req(FXP_READ, id); bin(handle); writeLong(offset); writeInt(len)
    }

    fun buildWrite(id: Int, handle: ByteArray, offset: Long, data: ByteArray, dataOff: Int, dataLen: Int) = frame {
        req(FXP_WRITE, id); bin(handle); writeLong(offset)
        writeInt(dataLen); write(data, dataOff, dataLen)
    }

    // ---- Response parsing ------------------------------------------------

    /**
     * Parse one complete packet body — everything after the `uint32 length`
     * prefix, i.e. starting with the type byte. The caller ([SftpV3Client]'s
     * reader) is responsible for having buffered exactly `length` bytes.
     */
    /**
     * True for the msgType codes a server SENDS (VERSION + the 101–105 response
     * range). Requests and responses occupy disjoint ranges, so this lets the
     * client drop echoed *request* packets: the markqvist rnsh listener mirrors
     * our stdin onto stdout ("forward input immediately for visibility"), so any
     * request-typed packet in the inbound stream is echo and must be ignored.
     */
    fun isResponseType(type: Int): Boolean =
        type == FXP_VERSION || type in FXP_STATUS..FXP_ATTRS

    fun parsePacket(body: ByteArray): SftpPacket {
        val din = DataInputStream(ByteArrayInputStream(body))
        return when (val type = din.readUnsignedByte()) {
            FXP_VERSION -> {
                val version = din.readInt()
                val ext = LinkedHashMap<String, String>()
                while (din.available() > 0) {
                    val name = readStr(din)
                    val data = if (din.available() > 0) readStr(din) else ""
                    ext[name] = data
                }
                SftpPacket.Version(version, ext)
            }
            FXP_STATUS -> {
                val id = din.readInt()
                val code = din.readInt()
                val msg = if (din.available() > 0) readStr(din) else ""
                SftpPacket.Status(id, code, msg)
            }
            FXP_HANDLE -> SftpPacket.Handle(din.readInt(), readBin(din))
            FXP_DATA -> SftpPacket.Data(din.readInt(), readBin(din))
            FXP_NAME -> {
                val id = din.readInt()
                val count = din.readInt()
                require(count in 0..1_000_000) { "absurd FXP_NAME count $count" }
                val names = ArrayList<SftpName>(count.coerceAtMost(1024))
                repeat(count) {
                    val filename = readStr(din)
                    val longname = readStr(din)
                    val attrs = readAttrs(din)
                    names.add(SftpName(filename, longname, attrs))
                }
                SftpPacket.Name(id, names)
            }
            FXP_ATTRS -> SftpPacket.Attrs(din.readInt(), readAttrs(din))
            else -> throw IOException("unexpected SFTP packet type $type")
        }
    }

    /** Map a non-OK/EOF status to the exception the FileBackend contract expects. */
    fun statusToException(status: SftpPacket.Status): IOException {
        val detail = status.message.ifBlank { "SFTP status ${status.code}" }
        return when (status.code) {
            FX_NO_SUCH_FILE -> FileNotFoundException(detail)
            FX_PERMISSION_DENIED -> IOException("permission denied: $detail")
            else -> IOException(detail)
        }
    }

    // ---- internal helpers ------------------------------------------------

    private fun readAttrs(din: DataInputStream): SftpAttrs {
        val flags = din.readInt()
        var size = 0L; var uid = 0; var gid = 0; var perms = 0; var atime = 0L; var mtime = 0L
        if (flags and ATTR_SIZE != 0) size = din.readLong()
        if (flags and ATTR_UIDGID != 0) { uid = din.readInt(); gid = din.readInt() }
        if (flags and ATTR_PERMISSIONS != 0) perms = din.readInt()
        if (flags and ATTR_ACMODTIME != 0) {
            atime = din.readInt().toLong() and 0xFFFFFFFFL
            mtime = din.readInt().toLong() and 0xFFFFFFFFL
        }
        if (flags and ATTR_EXTENDED != 0) {
            val count = din.readInt()
            repeat(count.coerceIn(0, 4096)) { readStr(din); readStr(din) }
        }
        return SftpAttrs(size, uid, gid, perms, atime, mtime, flags)
    }

    private fun readStr(din: DataInputStream): String = String(readBin(din), Charsets.UTF_8)

    private fun readBin(din: DataInputStream): ByteArray {
        val n = din.readInt()
        require(n in 0..MAX_PACKET) { "SFTP string length $n out of range" }
        val b = ByteArray(n)
        din.readFully(b)
        return b
    }

    // DSL shims used by the builders.
    private fun DataOutputStream.req(type: Int, id: Int) { writeByte(type); writeInt(id) }
    private fun DataOutputStream.str(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataOutputStream.bin(b: ByteArray) { writeInt(b.size); write(b) }

    /** Build a packet body and prefix it with its `uint32 length`. */
    private inline fun frame(block: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { it.block() }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(bodyBytes.size + 4)
        DataOutputStream(out).use { it.writeInt(bodyBytes.size); it.write(bodyBytes) }
        return out.toByteArray()
    }
}
