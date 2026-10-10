package io.github.jasondorjeshort.dystopo.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * T67: who a bearer token belongs to on the server. Three kinds of token:
 * - **user tokens** (`users: [{id, token}]` in the server config): each user has their own sync replica
 *   (`out/sync/<id>.db`) and their own share links; `/sync*` is scoped to that replica.
 * - the **admin token** (`adminToken`): `/admin/users` (list, add, remove users) and, with `?user=<id>`,
 *   any user's `/sync*` routes; it is not a sync user itself.
 * - the single **legacy token** of T52 (`token`): still accepted, and reaches the default replica
 *   (`peer.db`, user key [DEFAULT]), so a server configured before T67 behaves exactly as it did.
 *
 * With no token of any kind configured the server is [open]: everyone is [Principal.Anonymous] on the
 * default replica (also how `devtool serve` runs). User ids are lowercase `[a-z0-9][a-z0-9_-]{0,31}`
 * (they name a file); [RESERVED] ids are refused. Token comparison is constant-time per token and every
 * configured token is compared, so the time does not say which one nearly matched.
 */
class Users(users: List<User> = emptyList(), adminToken: String? = null, val legacyToken: String? = null) {
    data class User(val id: String, val token: String)

    sealed class Principal {
        /** A configured user: their own replica and share links. */
        data class Member(val id: String) : Principal()
        /** The admin token. */
        data object Admin : Principal()
        /** The T52 single token: the default replica. */
        data object Legacy : Principal()
        /** No token configured anywhere: the default replica, no checks. */
        data object Anonymous : Principal()
    }

    @Volatile private var list: List<User> = users.toList()
    @Volatile var adminToken: String? = adminToken?.trim()?.takeIf { it.isNotEmpty() }
        private set

    /** True when no token at all is configured (every route open, one replica). */
    val open: Boolean get() = list.isEmpty() && adminToken == null && legacyToken == null

    fun all(): List<User> = list

    fun ids(): List<String> = list.map { it.id }

    fun has(id: String): Boolean = list.any { it.id == id }

    /** The principal for an `Authorization` header, or null when the header does not carry a known token. */
    fun resolve(authorization: String?): Principal? {
        if (open) return Principal.Anonymous
        val given = bearer(authorization)?.toByteArray() ?: return null
        var found: Principal? = null
        // Compare against every token (no early exit), each in constant time.
        for (u in list) if (MessageDigest.isEqual(given, u.token.toByteArray()) && found == null) found = Principal.Member(u.id)
        adminToken?.let { if (MessageDigest.isEqual(given, it.toByteArray()) && found == null) found = Principal.Admin }
        legacyToken?.let { if (MessageDigest.isEqual(given, it.toByteArray()) && found == null) found = Principal.Legacy }
        return found
    }

    /** Adds a user with a fresh token (or [token]); throws IllegalArgumentException on a bad or taken id. */
    @Synchronized
    fun add(id: String, token: String = newToken()): User {
        require(validId(id)) { "user id must match ${ID.pattern} and not be one of $RESERVED (got '$id')" }
        require(!has(id)) { "user '$id' exists" }
        require(token.length >= MIN_TOKEN && list.none { it.token == token } && token != adminToken && token != legacyToken) { "token too short or already in use" }
        val u = User(id, token)
        list = list + u
        return u
    }

    /** Removes a user (their replica file and share links stay until removed by hand). */
    @Synchronized
    fun remove(id: String): Boolean {
        val before = list.size
        list = list.filter { it.id != id }
        return list.size != before
    }

    companion object {
        /** The replica key of the default replica (`peer.db`): the legacy token, an open server, `devtool serve`. */
        const val DEFAULT = "peer"

        /** The exchange attribute the gate sets to the user key a `/sync` request runs as. */
        const val ATTR = "dystopo.user"

        val ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        val RESERVED = setOf(DEFAULT, "admin", "shares")
        const val MIN_TOKEN = 16

        fun validId(id: String): Boolean = ID.matches(id) && id !in RESERVED

        private val random = SecureRandom()

        /** 32 random bytes as base64url without padding (43 characters). */
        fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        /** The token in `Authorization: Bearer <token>` (scheme case-insensitive), else null. */
        fun bearer(header: String?): String? =
            header?.trim()?.takeIf { it.length > 7 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

        /** Config problems for a list of users (ids, duplicates, token lengths), for the server's validation. */
        fun problems(users: List<User>, adminToken: String?, legacyToken: String?): List<String> = buildList {
            users.forEachIndexed { i, u ->
                if (!validId(u.id)) add("users[$i].id must match ${ID.pattern} and not be one of $RESERVED (got '${u.id}')")
                if (u.token.trim().length < MIN_TOKEN) add("users[$i].token must be at least $MIN_TOKEN characters (server --add-user makes one)")
            }
            users.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("user id '$it' appears more than once") }
            val tokens = users.map { it.token } + listOfNotNull(adminToken, legacyToken)
            if (tokens.size != tokens.toSet().size) add("every token (users, adminToken, token) must be different")
            if (adminToken != null && adminToken.trim().length < MIN_TOKEN) add("adminToken must be at least $MIN_TOKEN characters")
        }
    }
}
