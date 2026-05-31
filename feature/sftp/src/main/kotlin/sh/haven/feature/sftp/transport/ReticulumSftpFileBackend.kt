package sh.haven.feature.sftp.transport

import sh.haven.core.reticulum.sftp.ReticulumSftpSession
import sh.haven.feature.sftp.SftpEntry
import java.io.InputStream

/**
 * [FileBackend] over a persistent [ReticulumSftpSession] (an exec'd
 * `sftp-server` spoken to in binary SFTP v3 over ONE Reticulum Link). The
 * throughput sibling of [ReticulumFileBackend] — used when the listener has an
 * `sftp-server` binary; otherwise [TransportSelector] falls back to the exec
 * backend (busybox/OpenWRT routers have none).
 *
 * Thin: re-fetches the session via [sessionProvider] per call (so a reopened
 * session after reconnect is picked up), mirroring `SftpTransport`. All
 * semantics — mkdir-p, recursive delete, symlink resolution, offset reads —
 * live in [ReticulumSftpSession].
 */
class ReticulumSftpFileBackend(
    private val sessionProvider: () -> ReticulumSftpSession,
) : FileBackend {

    override val label: String = "Reticulum (SFTP)"

    override suspend fun list(path: String): List<SftpEntry> =
        sessionProvider().list(path).map { it.toSftpEntry() }

    override suspend fun delete(path: String, isDirectory: Boolean) =
        sessionProvider().delete(path)

    override suspend fun mkdir(path: String) = sessionProvider().mkdir(path)

    override suspend fun rename(from: String, to: String) = sessionProvider().rename(from, to)

    override suspend fun readBytes(path: String): ByteArray = sessionProvider().readBytes(path)

    override suspend fun writeBytes(path: String, data: ByteArray) =
        sessionProvider().writeBytes(path, data)

    override suspend fun stat(path: String): SftpEntry = sessionProvider().stat(path).toSftpEntry()

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        sessionProvider().openInputStream(path, offset)

    private fun ReticulumSftpSession.Entry.toSftpEntry() = SftpEntry(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        modifiedTime = modifiedTime,
        permissions = permissions,
        owner = owner,
        group = group,
    )
}
