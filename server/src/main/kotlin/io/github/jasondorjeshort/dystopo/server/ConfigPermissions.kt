package io.github.jasondorjeshort.dystopo.server

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * T92: the config file holds every bearer token in plain text (T67), so the server checks at start that
 * nobody but its owner can read it, and warns (`config_permissions` in the log) when
 * others can. A warning, not a refusal: a container mounts the file read-only from the host, where
 * the server cannot fix it. Fix: `chmod 600 server.json` (POSIX), or on Windows remove the read
 * grants for Everyone, Users and Authenticated Users (`icacls server.json /inheritance:r /grant:r "%USERNAME%:F"`).
 * [UserAdmin] writes the file owner-only on POSIX.
 */
object ConfigPermissions {
    /** Principals that stand for everyone on Windows (the name after any `DOMAIN\` prefix). */
    private val WIDE = setOf("everyone", "users", "authenticated users")

    private fun wide(principal: String) = principal.substringAfterLast('\\').lowercase() in WIDE

    /** Why [file] is readable by others (empty: only its owner, or it holds no token, or it cannot be checked). */
    fun problems(file: File): List<String> {
        val path = file.toPath()
        if (!Files.isRegularFile(path)) return emptyList()
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let { view ->
            val perms = runCatching { view.readAttributes().permissions() }.getOrNull() ?: return emptyList()
            val wide = perms.filter { it == PosixFilePermission.GROUP_READ || it == PosixFilePermission.OTHERS_READ }
            return if (wide.isEmpty()) emptyList() else listOf(
                "${file.path} is ${PosixFilePermissions.toString(perms)}: readable by " +
                    wide.joinToString(" and ") { if (it == PosixFilePermission.GROUP_READ) "its group" else "every user" } +
                    "; it holds bearer tokens, so chmod 600 it",
            )
        }
        Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.let { view ->
            val acl = runCatching { view.acl }.getOrNull() ?: return emptyList()
            val readers = acl.filter { e ->
                e.type() == AclEntryType.ALLOW && AclEntryPermission.READ_DATA in e.permissions() &&
                    wide(e.principal().name)
            }.map { it.principal().name }.distinct()
            return if (readers.isEmpty()) emptyList() else listOf(
                "${file.path} is readable by ${readers.joinToString(", ")}; it holds bearer tokens, so remove those grants " +
                    "(icacls \"${file.path}\" /inheritance:r /grant:r \"%USERNAME%:F\")",
            )
        }
        return emptyList()
    }

    /** True when the config text sets any token (only then do its permissions matter). */
    fun holdsTokens(config: ServerConfig): Boolean = config.token != null || config.adminToken != null || config.users.isNotEmpty()

    /** Makes [path] readable and writable by its owner only, where the file system has POSIX permissions. */
    fun ownerOnly(path: Path) {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java) ?: return
        runCatching { view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
    }
}
