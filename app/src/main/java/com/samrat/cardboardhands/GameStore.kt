package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The PhoneXR app store: games are files in Supabase Storage, bucket "vr_games".
 *
 * Layout the store understands:
 *   vr_games/Game.apk                     a game as a single file (.apk or .pxr)
 *   vr_games/Game Name/game.apk           a game in its own folder, which may also hold
 *   vr_games/Game Name/icon.png           an icon (icon.png / icon.jpg / icon.webp)
 *   vr_games/Game Name/description.txt    and a description shown in the store
 *   vr_games/game.json                    a link: the APK lives elsewhere (GitHub Releases), so it
 *                                         may be larger than Supabase's 50 MB per file. Fields:
 *                                         {"name", "url", "icon"?, "description"?, "size"?}
 *
 * The same link files also work from the root of the PhoneXR GitHub repository (e.g. opensaber.json),
 * so a game can be published without touching Supabase at all.
 */
object GameStore {
    const val URL_BASE = "https://fjiostsfwfennbovpolc.supabase.co"
    /** Publishable key: made to ship inside apps, it only allows what storage policies permit. */
    private const val KEY = "sb_publishable_0we8-Uw_XxKmUINy6KOjDA_lySgpLiA"
    const val FOLDER = "vr_games"
    /** GitHub repository whose root *.json link files are store games too. */
    private const val GITHUB_REPO = "samrat1games/phonexr"

    /**
     * Where the store may live: its own bucket, or a folder inside a common one. Supabase allows
     * both spellings of the name, so a bucket made by hand may be "vr_games" or "vr-games": both
     * are tried, so an older store bucket keeps working.
     */
    private val locations = listOf(FOLDER, "vr-games").flatMap { folder ->
        listOf(Location(folder, "")) + listOf("files", "public", "storage").map { Location(it, "$folder/") }
    }

    private data class Location(val bucket: String, val prefix: String)

    data class Item(
        val title: String,
        /** Path inside the bucket. */
        val path: String,
        val bucket: String,
        val size: Long,
        val iconPath: String?,
        val descriptionPath: String?,
        /** For a link entry: where the APK is downloaded from, its icon and description. */
        val url: String? = null,
        val iconUrl: String? = null,
        val descriptionText: String? = null,
    ) {
        val extension get() = (url?.substringBefore('?') ?: path).substringAfterLast('.', "apk").lowercase()
            .takeIf { it in setOf("apk", "pxr") } ?: "apk"
    }

    class StoreException(message: String) : Exception(message)

    private val installable = setOf("apk", "pxr")
    private val iconNames = setOf("icon.png", "icon.jpg", "icon.jpeg", "icon.webp")
    @Volatile private var found: List<Location>? = null

    /** Network call, run off the main thread. Supabase and GitHub each fill in what they can. */
    fun list(): List<Item> {
        val github = runCatching { githubLinks() }.getOrDefault(emptyList())
        val supabase = runCatching { supabaseItems() }
        if (supabase.isFailure && github.isEmpty()) throw supabase.exceptionOrNull()!!
        val items = supabase.getOrDefault(emptyList()) + github
        return items.distinctBy { it.url ?: it.path }.sortedBy { it.title.lowercase() }
    }

    /** Link files (*.json with a "url") in the root of the PhoneXR GitHub repository. */
    private fun githubLinks(): List<Item> {
        val listing = openUrl("https://api.github.com/repos/$GITHUB_REPO/contents/").use {
            JSONArray(it.inputStream.readBytes().toString(Charsets.UTF_8))
        }
        return (0 until listing.length()).map { listing.getJSONObject(it) }
            .filter { it.optString("type") == "file" && it.getString("name").endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    val text = openUrl(file.getString("download_url")).use { it.inputStream.readBytes().toString(Charsets.UTF_8) }
                    parseLink(text, "github:" + file.getString("name"), "", file.getString("name").substringBeforeLast('.'))
                }.getOrNull()
            }
    }

    /**
     * Network call: Minecraft mods from the store's "minecraft_mods" folder (.mcaddon, .mcpack,
     * .mcworld, .mctemplate), plus link files there (JSON with "name" and "url") for big ones.
     */
    fun mods(): List<Item> {
        val location = locations().firstOrNull() ?: return emptyList()
        val folder = location.prefix + MinecraftMods.FOLDER + "/"
        val entries = runCatching { listFolder(location.bucket, folder) }.getOrDefault(emptyList())
        val items = ArrayList<Item>()
        for (entry in entries) {
            val name = entry.getString("name")
            if (entry.isNull("id")) continue
            when {
                MinecraftMods.isMod(name) -> items += Item(
                    title = name.substringBeforeLast('.').replace('_', ' '),
                    path = folder + name,
                    bucket = location.bucket,
                    size = entry.size(),
                    iconPath = null,
                    descriptionPath = null,
                )
                entry.fileExtension() == "json" -> link(location.bucket, folder + name, name.substringBeforeLast('.'))
                    ?.takeIf { MinecraftMods.isMod(it.url?.substringBefore('?') ?: "") }?.let { items += it }
            }
        }
        return items.sortedBy { it.title.lowercase() }
    }

    private fun supabaseItems(): List<Item> = locations().flatMap { location ->
        runCatching { itemsIn(location) }.getOrDefault(emptyList())
    }

    /** Games of one bucket (or of one folder inside a shared bucket). */
    private fun itemsIn(location: Location): List<Item> {
        val items = mutableListOf<Item>()
        for (entry in listFolder(location.bucket, location.prefix)) {
            val name = entry.getString("name")
            if (entry.isNull("id")) {
                // A folder: one game with its files.
                val folder = location.prefix + name + "/"
                val files = listFolder(location.bucket, folder)
                val game = files.firstOrNull { it.fileExtension() in installable }
                if (game == null) {
                    // A folder with a link file instead of the APK itself.
                    files.firstOrNull { it.fileExtension() == "json" }
                        ?.let { link(location.bucket, folder + it.getString("name"), name) }
                        ?.let { items += it }
                    continue
                }
                val names = files.map { it.getString("name") }
                items += Item(
                    title = name,
                    path = folder + game.getString("name"),
                    bucket = location.bucket,
                    size = game.size(),
                    iconPath = names.firstOrNull { it.lowercase() in iconNames }?.let { folder + it },
                    descriptionPath = names.firstOrNull { it.equals("description.txt", true) }?.let { folder + it }
                )
            } else if (entry.fileExtension() in installable && name != "pwa.json") {
                items += Item(
                    title = name.substringBeforeLast('.').replace('_', ' '),
                    path = location.prefix + name,
                    bucket = location.bucket,
                    size = entry.size(),
                    iconPath = null,
                    descriptionPath = null
                )
            } else if (entry.fileExtension() == "json" && name != "pwa.json") {
                link(location.bucket, location.prefix + name, name.substringBeforeLast('.'))?.let { items += it }
            }
        }
        return items
    }

    /** Reads a link file; broken ones are skipped so one typo does not empty the store. */
    private fun link(bucket: String, path: String, fallbackTitle: String): Item? = runCatching {
        parseLink(open(bucket, path).use { it.inputStream.readBytes().toString(Charsets.UTF_8) }, path, bucket, fallbackTitle)
    }.getOrNull()

    /** A link file's JSON, or null when it is not a game link (no http(s) "url"). */
    private fun parseLink(text: String, path: String, bucket: String, fallbackTitle: String): Item? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val url = json.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return Item(
            title = json.optString("name").ifBlank { fallbackTitle },
            path = path,
            bucket = bucket,
            size = json.optLong("size", -1L),
            iconPath = null,
            descriptionPath = null,
            url = url,
            iconUrl = json.optString("icon").takeIf { it.startsWith("http") },
            descriptionText = json.optString("description").takeIf { it.isNotBlank() },
        )
    }

    /** Network call: a text file from the store folder, such as pwa.json. */
    fun readText(name: String): String {
        val failures = mutableListOf<Throwable>()
        for (location in locations()) {
            runCatching { open(location.bucket, location.prefix + name).use { it.inputStream.readBytes().toString(Charsets.UTF_8) } }
                .onSuccess { return it }
                .onFailure { failures += it }
        }
        throw failures.firstOrNull() ?: FileNotFoundException("Файл «$name» не найден в магазине")
    }

    fun description(item: Item): String? = item.descriptionText ?: item.descriptionPath?.let { path ->
        runCatching { open(item.bucket, path).use { it.inputStream.readBytes().toString(Charsets.UTF_8).trim() } }.getOrNull()
    }

    fun icon(item: Item): Bitmap? {
        item.iconUrl?.let { url ->
            return runCatching { openUrl(url).use { BitmapFactory.decodeStream(it.inputStream) } }.getOrNull()
        }
        return item.iconPath?.let { path ->
            runCatching { open(item.bucket, path).use { BitmapFactory.decodeStream(it.inputStream) } }.getOrNull()
        }
    }

    /** Downloads [item] into [directory], reporting progress 0..1 (or -1 when the size is unknown). */
    fun download(item: Item, directory: File, onProgress: (Float) -> Unit): File {
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        // A half-downloaded game is the usual reason a store download "just stops": say it first.
        if (item.size > 0 && directory.usableSpace in 1 until item.size + (64L shl 20)) {
            throw StoreException(
                "На телефоне не хватает места: нужно ${megabytes(item.size)}, свободно ${megabytes(directory.usableSpace)}"
            )
        }
        // Mods keep their own extension: Minecraft recognises them by it.
        val extension = (item.url?.substringBefore('?') ?: item.path).substringAfterLast('.', "").lowercase()
            .takeIf { MinecraftMods.isMod("x.$it") } ?: item.extension
        val target = File(directory, item.title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + "." + extension)
        val source = item.url?.let { openUrl(it) } ?: open(item.bucket, item.path)
        source.use { response ->
            val total = response.connection.contentLengthLong.takeIf { it > 0 } ?: item.size
            response.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        }
        onProgress(1f)
        return target
    }

    /**
     * Every place the store actually has files in. Supabase answers a listing it may not show with an
     * empty list rather than an error, so an empty one simply carries no games. Both spellings of the
     * bucket are read, so games put in either one are all in the store.
     */
    private fun locations(): List<Location> {
        found?.let { return it }
        var reachable = false
        val live = mutableListOf<Location>()
        for (location in locations) {
            val (code, body) = request("POST", "/storage/v1/object/list/${location.bucket}", listBody(location.prefix))
            if (code != 200) continue
            reachable = true
            if (JSONArray(body).length() > 0) live += location
        }
        if (!reachable) throw StoreException("Сервер магазина недоступен. Проверьте интернет.")
        return live.ifEmpty { listOf(locations.first()) }.also { found = it }
    }

    /** Shown when the store is empty: either there are no games yet, or reading is not allowed. */
    const val EMPTY_HINT = "Файлы не найдены. Положите игры в bucket «$FOLDER» Supabase и разрешите всем " +
        "чтение в Storage → Policies (SELECT для anon)."

    private fun listFolder(bucket: String, prefix: String): List<JSONObject> {
        val (code, body) = request("POST", "/storage/v1/object/list/$bucket", listBody(prefix))
        if (code != 200) throw StoreException("Магазин недоступен (код $code): ${errorText(body)}")
        val array = JSONArray(body)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filterNot { it.getString("name") == ".emptyFolderPlaceholder" }
    }

    private fun listBody(prefix: String) = JSONObject()
        .put("prefix", prefix)
        .put("limit", 1000)
        .put("offset", 0)
        .put("sortBy", JSONObject().put("column", "name").put("order", "asc"))
        .toString()

    private fun JSONObject.fileExtension() = getString("name").substringAfterLast('.', "").lowercase()

    private fun JSONObject.size() = optJSONObject("metadata")?.optLong("size", -1L) ?: -1L

    private class Response(val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    /** Public buckets serve files without a policy; private ones need the key and a read policy. */
    private fun open(bucket: String, path: String): Response {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        for (endpoint in listOf("public/$bucket/$encoded", "authenticated/$bucket/$encoded")) {
            val connection = connect("GET", "/storage/v1/object/$endpoint")
            if (connection.responseCode == 200) return Response(connection)
            connection.disconnect()
        }
        throw FileNotFoundException("Файл «$path» не скачивается из Supabase")
    }

    /** A file outside Supabase, e.g. a GitHub release asset (which redirects to its CDN). */
    private fun openUrl(address: String): Response {
        var url = URL(address)
        repeat(5) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                url = URL(url, next ?: throw FileNotFoundException("Пустая переадресация: $address"))
            } else if (code == 200) {
                return Response(connection)
            } else {
                connection.disconnect()
                throw FileNotFoundException("Файл по ссылке не скачивается (код $code): $address")
            }
        }
        throw FileNotFoundException("Слишком много переадресаций: $address")
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val connection = connect(method, path, body)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(method: String, path: String, body: String? = null): HttpURLConnection {
        val connection = URL(URL_BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("apikey", KEY)
        connection.setRequestProperty("Authorization", "Bearer $KEY")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return connection
    }

    private fun megabytes(bytes: Long) = "%.0f МБ".format(bytes / (1L shl 20).toDouble())

    private fun errorText(body: String) = runCatching { JSONObject(body).optString("message", body) }.getOrDefault(body)
}
