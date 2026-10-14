package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * T67: managing the server's sync users, from the command line and over HTTP.
 *
 * Command line (edits the config file, keeping its other keys; prints one JSON object; a running server
 * picks the change up at its next start, or use the HTTP routes):
 *
 *     server --config server.json --add-user alice      # makes a token, adds {"id", "token"} to users, prints it once
 *     server --config server.json --remove-user alice   # the replica file and its share links stay on disk
 *     server --config server.json --list-users          # ids, replica files and sizes (tokens never printed)
 *     server --config server.json --new-admin-token     # sets adminToken to a fresh token, prints it once
 *
 * HTTP (admin token only, through the gate): `GET /admin/users` lists users with their replica (file,
 * bytes, operations, tracks, routes, share links, last request), the replica files on disk and the
 * share-link counters; `POST /admin/users?id=<id>` adds a user at once and answers the token (also
 * written to the config file when the server has one: `persisted`); `DELETE /admin/users/<id>` removes
 * one and revokes their share links (the replica file stays).
 */
object UserAdmin {
    sealed class Command {
        data class Add(val id: String) : Command()
        data class Remove(val id: String) : Command()
        data object List : Command()
        data object NewAdminToken : Command()
    }

    /** Takes the user flags out of [args]: the command (or null) and the remaining arguments. */
    fun parse(args: Array<String>): Pair<Command?, Array<String>> {
        var cmd: Command? = null
        val rest = ArrayList<String>()
        var i = 0
        fun set(c: Command) {
            require(cmd == null) { "one of --add-user, --remove-user, --list-users, --new-admin-token at a time" }
            cmd = c
        }
        while (i < args.size) {
            when (val a = args[i]) {
                "--add-user" -> set(Command.Add(args.getOrNull(++i) ?: throw IllegalArgumentException("--add-user needs an id")))
                "--remove-user" -> set(Command.Remove(args.getOrNull(++i) ?: throw IllegalArgumentException("--remove-user needs an id")))
                "--list-users" -> set(Command.List)
                "--new-admin-token" -> set(Command.NewAdminToken)
                else -> rest += a
            }
            i++
        }
        return cmd to rest.toTypedArray()
    }

    /** Runs [cmd] against [configFile] (created as `{}` when missing, for `--add-user` and `--new-admin-token`); the JSON answer. */
    fun run(cmd: Command, configFile: File): Map<String, Any?> = synchronized(this) {
        when (cmd) {
            is Command.Add -> {
                val token = Users.newToken()
                edit(configFile) { root -> addUser(root, cmd.id, token) }
                linkedMapOf(
                    "added" to cmd.id, "token" to token, "config" to configFile.path,
                    "note" to "give the token to the user (the phone's sync settings, devtool sync with --token); a running server needs a restart, or use POST /admin/users",
                )
            }
            is Command.Remove -> {
                var removed = false
                edit(configFile) { root -> removeUser(root, cmd.id).also { removed = it.second }.first }
                linkedMapOf("removed" to if (removed) cmd.id else null, "found" to removed, "config" to configFile.path)
            }
            Command.List -> {
                val config = ServerConfig.load(configFile)
                val dir = (config.syncDb?.let { File(it) } ?: File(config.outDir, "sync/peer.db")).absoluteFile.parentFile
                linkedMapOf(
                    "config" to configFile.path, "adminToken" to (config.adminToken != null),
                    "users" to config.users.map { u -> File(dir, "${u.id}.db").let { f -> linkedMapOf("id" to u.id, "db" to f.path, "bytes" to f.takeIf { it.isFile }?.length()) } },
                )
            }
            Command.NewAdminToken -> {
                val token = Users.newToken()
                edit(configFile) { root -> JsonObject(root + ("adminToken" to JsonPrimitive(token))) }
                linkedMapOf("adminToken" to token, "config" to configFile.path, "note" to "a running server needs a restart")
            }
        }
    }

    /** Reads [file] as a JSON object, applies [change], checks the result is a valid config, writes it back. */
    fun edit(file: File, change: (JsonObject) -> JsonObject) {
        val root = if (file.isFile) ServerConfig.json.parseToJsonElement(file.readText()).jsonObject else JsonObject(emptyMap())
        val next = change(root)
        val text = ServerConfig.json.encodeToString(JsonElement.serializer(), next) + "\n"
        val config = ServerConfig.parse(text) // unknown keys and bad types throw here, before anything is written
        val problems = Users.problems(config.users.map { Users.User(it.id, it.token) }, config.adminToken, config.token)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        ConfigPermissions.ownerOnly(tmp.toPath()) // T92: it holds tokens
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.path}" }
        }
    }

    fun addUser(root: JsonObject, id: String, token: String): JsonObject {
        require(Users.validId(id)) { "user id must match ${Users.ID.pattern} and not be one of ${Users.RESERVED} (got '$id')" }
        val users = (root["users"] as? JsonArray).orEmpty()
        require(users.none { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content == id }) { "user '$id' exists" }
        val user = JsonObject(linkedMapOf("id" to JsonPrimitive(id), "token" to JsonPrimitive(token)))
        return JsonObject(root + ("users" to JsonArray(users + user)))
    }

    fun removeUser(root: JsonObject, id: String): Pair<JsonObject, Boolean> {
        val users = (root["users"] as? JsonArray).orEmpty()
        val kept = users.filter { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content != id }
        return JsonObject(root + ("users" to JsonArray(kept))) to (kept.size != users.size)
    }

    /** `/admin/users` (the gate has checked the admin token). [configFile]: where added users are persisted, if any. */
    fun handle(ex: HttpExchange, users: Users, configFile: File?) {
        val path = ex.requestURI.path
        fun json(status: Int, m: Map<String, Any?>) {
            val bytes = (JsonWriter.write(m) + "\n").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        try {
            when {
                path == "/admin/users" && ex.requestMethod == "GET" -> json(
                    200,
                    linkedMapOf(
                        "users" to users.ids().map { SyncRoutes.userSummary(it) },
                        "defaultReplica" to (users.legacyToken != null || users.open).takeIf { it }?.let { SyncRoutes.userSummary(Users.DEFAULT) },
                        "replicaFiles" to SyncRoutes.replicaFiles().map { linkedMapOf("file" to it.name, "bytes" to it.length()) },
                        "shareLinks" to SyncRoutes.shares.stats(),
                    ),
                )
                path == "/admin/users" && ex.requestMethod == "POST" -> {
                    val id = Gate.queryParam(ex.requestURI.rawQuery, "id") ?: return json(400, mapOf("error" to "id=<user id> required"))
                    val token = Users.newToken()
                    synchronized(this) {
                        if (configFile != null) edit(configFile) { addUser(it, id, token) } // validates the id and refuses a taken one
                        users.add(id, token)
                    }
                    json(200, linkedMapOf("added" to id, "token" to token, "persisted" to (configFile != null), "db" to SyncRoutes.replicaFile(id).name))
                }
                path.startsWith("/admin/users/") && ex.requestMethod == "DELETE" -> {
                    val id = path.removePrefix("/admin/users/")
                    val found = synchronized(this) {
                        if (configFile != null && users.has(id)) edit(configFile) { removeUser(it, id).first }
                        users.remove(id)
                    }
                    if (!found) return json(404, mapOf("error" to "no user '$id'"))
                    json(200, linkedMapOf("removed" to id, "linksRevoked" to SyncRoutes.shares.revokeAll(id), "persisted" to (configFile != null), "replicaKept" to SyncRoutes.replicaFile(id).name))
                }
                else -> json(404, mapOf("error" to "GET|POST /admin/users, DELETE /admin/users/<id>"))
            }
        } catch (e: IllegalArgumentException) {
            json(400, mapOf("error" to e.message))
        }
    }
}
