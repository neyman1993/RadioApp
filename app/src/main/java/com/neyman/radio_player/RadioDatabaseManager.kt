package com.neyman.radio_player

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.zip.GZIPInputStream

object RadioDatabaseManager {
    const val DATABASE_FILE_NAME = "radio_database.json"

    private val API_SERVERS = listOf(
        "all.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "de1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
        "at1.api.radio-browser.info",
        "fi1.api.radio-browser.info"
    )

    data class DbStation(
        val name: String,
        val url: String,
        val country: String,
        val tags: String = "",
        val bitrate: Int = 0,
        val codec: String = "",
        val clickCount: Int = 0,
        val votes: Int = 0
    ) {
        fun toStation(): Station = Station(name, url, country)
    }

    data class DatabaseStats(
        val stationsCount: Int,
        val countriesCount: Int,
        val genresCount: Int,
        val createdAt: Long = 0L
    )

    data class BatchResult(
        val rawCount: Int,
        val stations: List<DbStation>
    )

    @Volatile
    private var isLoaded = false
    private val stations = ArrayList<DbStation>()
    private val countries = ArrayList<Pair<String, Int>>()
    private val genres = ArrayList<Pair<String, Int>>()
    private var stats: DatabaseStats? = null

    @Volatile
    private var cancelRequested = false

    fun getDatabaseFile(context: Context): File {
        return File(context.filesDir, DATABASE_FILE_NAME)
    }

    fun hasDatabase(context: Context): Boolean {
        val f = getDatabaseFile(context)
        return f.exists() && f.length() > 512
    }

    fun deleteDatabase(context: Context): Boolean {
        synchronized(this) {
            val f = getDatabaseFile(context)
            val deleted = if (f.exists()) f.delete() else true
            val tmp = File(context.filesDir, "$DATABASE_FILE_NAME.tmp")
            if (tmp.exists()) tmp.delete()
            stations.clear()
            countries.clear()
            genres.clear()
            stats = null
            isLoaded = false
            return deleted
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    fun getStats(context: Context): DatabaseStats? {
        if (!hasDatabase(context)) return null
        if (!isLoaded) {
            ensureLoaded(context)
        }
        return stats ?: DatabaseStats(stations.size, countries.size, genres.size, 0L)
    }

    fun ensureLoaded(context: Context): Boolean {
        if (isLoaded) return true
        val f = getDatabaseFile(context)
        if (!f.exists() || f.length() <= 512) return false

        synchronized(this) {
            if (isLoaded) return true
            stations.clear()
            countries.clear()
            genres.clear()
            try {
                parseDatabaseFile(f, stations, countries, genres) { st ->
                    stats = st
                }
                isLoaded = true
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }
    }

    fun importDatabase(context: Context, inputStream: InputStream, isTr: Boolean = false): DatabaseStats {
        val tmpFile = File(context.filesDir, "$DATABASE_FILE_NAME.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        FileOutputStream(tmpFile).use { out ->
            val buf = ByteArray(64 * 1024)
            var len: Int
            while (inputStream.read(buf).also { len = it } != -1) {
                out.write(buf, 0, len)
            }
            out.flush()
        }

        val testStations = ArrayList<DbStation>()
        val testCountries = ArrayList<Pair<String, Int>>()
        val testGenres = ArrayList<Pair<String, Int>>()
        var testStats: DatabaseStats? = null

        try {
            parseDatabaseFile(tmpFile, testStations, testCountries, testGenres) { st ->
                testStats = st
            }
        } catch (e: Exception) {
            tmpFile.delete()
            val errPrefix = if (isTr) "radio_database.json dosya yapısı hatalı: " else "Ошибка в структуре файла radio_database.json: "
            throw IllegalArgumentException(errPrefix + (e.localizedMessage ?: e.toString()))
        }

        if (testStations.isEmpty()) {
            tmpFile.delete()
            val emptyMsg = if (isTr) "Seçilen dosyada radyo istasyonu bulunamadı" else "В выбранном файле не найдено радиостанций"
            throw IllegalArgumentException(emptyMsg)
        }

        val targetFile = getDatabaseFile(context)
        if (targetFile.exists()) targetFile.delete()
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }

        synchronized(this) {
            stations.clear()
            stations.addAll(testStations)
            countries.clear()
            countries.addAll(testCountries)
            genres.clear()
            genres.addAll(testGenres)
            stats = testStats ?: DatabaseStats(testStations.size, testCountries.size, testGenres.size, 0L)
            isLoaded = true
        }

        return stats!!
    }

    fun downloadDatabase(
        context: Context,
        isTr: Boolean = false,
        onProgress: (count: Int, message: String) -> Unit,
        onFinished: (success: Boolean, errorMsg: String?, stats: DatabaseStats?) -> Unit
    ) {
        cancelRequested = false
        Thread {
            val tmpFile = File(context.filesDir, "$DATABASE_FILE_NAME.tmp")
            try {
                if (tmpFile.exists()) tmpFile.delete()

                val connectMsg = if (isTr) "Radio Browser sunucularına bağlanılıyor..." else "Подключение к серверам Radio Browser..."
                onProgress(0, connectMsg)

                val downloadedStations = ArrayList<DbStation>()
                val pageLimit = 5000
                var offset = 0
                var activeServerIndex = 0

                while (true) {
                    if (cancelRequested) {
                        tmpFile.delete()
                        val cancelMsg = if (isTr) "İşlem kullanıcı tarafından iptal edildi" else "Загрузка отменена пользователем"
                        onFinished(false, cancelMsg, null)
                        return@Thread
                    }

                    var batchResult: BatchResult? = null
                    var lastErr = ""

                    for (shift in API_SERVERS.indices) {
                        val serverIdx = (activeServerIndex + shift) % API_SERVERS.size
                        val server = API_SERVERS[serverIdx]
                        val urlStr = "https://$server/json/stations?hidebroken=true&limit=$pageLimit&offset=$offset"

                        try {
                            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 10000
                                readTimeout = 30000
                                setRequestProperty("User-Agent", "Radio Player Local Database/2.0")
                                setRequestProperty("Accept-Encoding", "gzip")
                            }

                            if (conn.responseCode == 200) {
                                val inStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                                    GZIPInputStream(conn.inputStream)
                                } else {
                                    conn.inputStream
                                }

                                val result = parseStationsFromJsonStream(inStream)
                                inStream.close()
                                conn.disconnect()

                                batchResult = result
                                activeServerIndex = serverIdx
                                break
                            } else {
                                lastErr = "HTTP ${conn.responseCode}"
                                conn.disconnect()
                            }
                        } catch (e: Exception) {
                            lastErr = e.message ?: e.toString()
                        }
                    }

                    if (batchResult == null) {
                        tmpFile.delete()
                        val failMsg = if (isTr) "Veritabanı indirilemedi: $lastErr" else "Не удалось загрузить данные: $lastErr"
                        onFinished(false, failMsg, null)
                        return@Thread
                    }

                    val rawCount = batchResult.rawCount
                    downloadedStations.addAll(batchResult.stations)

                    val progressMsg = if (isTr) {
                        "İndirilen istasyon sayısı: ${downloadedStations.size}..."
                    } else {
                        "Скачано ${downloadedStations.size} станций..."
                    }
                    onProgress(downloadedStations.size, progressMsg)

                    // End reached if server returned less than requested pageLimit
                    if (rawCount < pageLimit) {
                        break
                    }

                    offset += pageLimit
                    try { Thread.sleep(120) } catch (e: Exception) {}
                }

                if (downloadedStations.isEmpty()) {
                    tmpFile.delete()
                    val emptyMsg = if (isTr) "Sunucudan istasyon listesi alınamadı" else "Сервер вернул пустой список станций"
                    onFinished(false, emptyMsg, null)
                    return@Thread
                }

                val savingMsg = if (isTr) "Yerel veritabanı işleniyor ve kaydediliyor..." else "Обработка и сохранение локальной базы..."
                onProgress(downloadedStations.size, savingMsg)

                writeDatabaseFile(tmpFile, downloadedStations)

                val targetFile = getDatabaseFile(context)
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }

                synchronized(this) {
                    isLoaded = false
                    ensureLoaded(context)
                }

                onFinished(true, null, stats)
            } catch (e: Exception) {
                tmpFile.delete()
                onFinished(false, e.localizedMessage ?: e.toString(), null)
            }
        }.start()
    }

    private fun parseStationsFromJsonStream(inputStream: InputStream): BatchResult {
        val list = ArrayList<DbStation>()
        var rawCount = 0
        val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))
        reader.beginArray()
        while (reader.hasNext()) {
            rawCount++
            reader.beginObject()
            var name = ""
            var url = ""
            var urlResolved = ""
            var country = ""
            var tags = ""
            var bitrate = 0
            var codec = ""
            var clickCount = 0
            var votes = 0

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                    "url" -> url = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                    "url_resolved" -> urlResolved = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                    "country" -> country = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                    "tags" -> tags = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                    "bitrate" -> bitrate = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                    "codec" -> codec = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                    "clickcount" -> clickCount = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                    "votes" -> votes = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val actualUrl = if (urlResolved.isNotEmpty()) urlResolved else url
            if (name.isNotEmpty() && actualUrl.isNotEmpty()) {
                list.add(DbStation(name, actualUrl, country, tags, bitrate, codec, clickCount, votes))
            }
        }
        reader.endArray()
        reader.close()
        return BatchResult(rawCount, list)
    }

    private fun parseDatabaseFile(
        file: File,
        outStations: ArrayList<DbStation>,
        outCountries: ArrayList<Pair<String, Int>>,
        outGenres: ArrayList<Pair<String, Int>>,
        onStats: (DatabaseStats) -> Unit
    ) {
        val reader = JsonReader(BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8"), 64 * 1024))
        var statStations = 0
        var statCountries = 0
        var statGenres = 0
        var createdAt = 0L

        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                parseSingleStation(reader)?.let { outStations.add(it) }
            }
            reader.endArray()
        } else {
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                when (field) {
                    "created_at" -> createdAt = try { reader.nextLong() } catch (e: Exception) { reader.skipValue(); 0L }
                    "stats" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stations" -> statStations = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                                "countries" -> statCountries = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                                "genres" -> statGenres = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    "countries" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var cName = ""
                            var cCount = 0
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> cName = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                                    "stationcount" -> cCount = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (cName.isNotEmpty()) {
                                outCountries.add(Pair(cName, cCount))
                            }
                        }
                        reader.endArray()
                    }
                    "genres" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var gName = ""
                            var gCount = 0
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> gName = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                                    "stationcount" -> gCount = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (gName.isNotEmpty()) {
                                outGenres.add(Pair(gName, gCount))
                            }
                        }
                        reader.endArray()
                    }
                    "stations" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            parseSingleStation(reader)?.let { outStations.add(it) }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.close()

        if (outCountries.isEmpty() && outStations.isNotEmpty()) {
            val cMap = HashMap<String, Int>()
            for (s in outStations) {
                if (s.country.isNotEmpty()) {
                    cMap[s.country] = (cMap[s.country] ?: 0) + 1
                }
            }
            for ((name, cnt) in cMap) {
                outCountries.add(Pair(name, cnt))
            }
            outCountries.sortBy { it.first.lowercase(Locale.getDefault()) }
        }

        if (outGenres.isEmpty() && outStations.isNotEmpty()) {
            val gMap = HashMap<String, Int>()
            for (s in outStations) {
                if (s.tags.isNotEmpty()) {
                    for (t in s.tags.split(",")) {
                        val clean = t.trim().lowercase(Locale.ROOT)
                        if (clean.isNotEmpty()) {
                            gMap[clean] = (gMap[clean] ?: 0) + 1
                        }
                    }
                }
            }
            for ((name, cnt) in gMap) {
                outGenres.add(Pair(name, cnt))
            }
            outGenres.sortBy { it.first.lowercase(Locale.getDefault()) }
        }

        val sCount = if (statStations > 0) statStations else outStations.size
        val cCount = if (statCountries > 0) statCountries else outCountries.size
        val gCount = if (statGenres > 0) statGenres else outGenres.size
        onStats(DatabaseStats(sCount, cCount, gCount, createdAt))
    }

    private fun parseSingleStation(reader: JsonReader): DbStation? {
        reader.beginObject()
        var name = ""
        var url = ""
        var urlResolved = ""
        var country = ""
        var tags = ""
        var bitrate = 0
        var codec = ""
        var clickCount = 0
        var votes = 0

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                "url" -> url = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                "url_resolved" -> urlResolved = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                "country" -> country = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                "tags" -> tags = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                "bitrate" -> bitrate = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                "codec" -> codec = try { reader.nextString().trim() } catch (e: Exception) { reader.skipValue(); "" }
                "clickcount" -> clickCount = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                "votes" -> votes = try { reader.nextInt() } catch (e: Exception) { reader.skipValue(); 0 }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val actualUrl = if (urlResolved.isNotEmpty()) urlResolved else url
        if (name.isNotEmpty() && actualUrl.isNotEmpty()) {
            return DbStation(name, actualUrl, country, tags, bitrate, codec, clickCount, votes)
        }
        return null
    }

    private fun writeDatabaseFile(file: File, list: List<DbStation>) {
        val countryMap = HashMap<String, Int>()
        val genreMap = HashMap<String, Int>()

        for (s in list) {
            if (s.country.isNotEmpty()) {
                countryMap[s.country] = (countryMap[s.country] ?: 0) + 1
            }
            if (s.tags.isNotEmpty()) {
                for (t in s.tags.split(",")) {
                    val tagClean = t.trim().lowercase(Locale.ROOT)
                    if (tagClean.isNotEmpty()) {
                        genreMap[tagClean] = (genreMap[tagClean] ?: 0) + 1
                    }
                }
            }
        }

        val countriesList = countryMap.map { Pair(it.key, it.value) }.sortedBy { it.first.lowercase(Locale.ROOT) }
        val genresList = genreMap.map { Pair(it.key, it.value) }.sortedBy { it.first.lowercase(Locale.ROOT) }

        val writer = JsonWriter(BufferedWriter(OutputStreamWriter(FileOutputStream(file), "UTF-8"), 64 * 1024))
        writer.beginObject()

        writer.name("version").value(1)
        writer.name("created_at").value(System.currentTimeMillis() / 1000)
        writer.name("source").value("radio-browser.info")

        writer.name("stats").beginObject()
        writer.name("stations").value(list.size)
        writer.name("countries").value(countriesList.size)
        writer.name("genres").value(genresList.size)
        writer.endObject()

        writer.name("countries").beginArray()
        for (c in countriesList) {
            writer.beginObject()
            writer.name("name").value(c.first)
            writer.name("stationcount").value(c.second)
            writer.endObject()
        }
        writer.endArray()

        writer.name("genres").beginArray()
        for (g in genresList) {
            writer.beginObject()
            writer.name("name").value(g.first)
            writer.name("stationcount").value(g.second)
            writer.endObject()
        }
        writer.endArray()

        writer.name("stations").beginArray()
        for (s in list) {
            writer.beginObject()
            writer.name("name").value(s.name)
            writer.name("url").value(s.url)
            writer.name("url_resolved").value(s.url)
            writer.name("country").value(s.country)
            writer.name("tags").value(s.tags)
            writer.name("bitrate").value(s.bitrate)
            writer.name("codec").value(s.codec)
            writer.name("clickcount").value(s.clickCount)
            writer.name("votes").value(s.votes)
            writer.endObject()
        }
        writer.endArray()

        writer.endObject()
        writer.flush()
        writer.close()
    }

    private fun deduplicate(list: List<DbStation>): List<DbStation> {
        if (list.isEmpty()) return emptyList()
        val groups = HashMap<String, ArrayList<DbStation>>()
        for (station in list) {
            val name = station.name.trim()
            if (name.isEmpty()) continue
            var cleanName = StationDeduplicator.normalizeName(name)
            if (cleanName.isEmpty()) cleanName = name.lowercase(Locale.getDefault())
            if (!groups.containsKey(cleanName)) groups[cleanName] = ArrayList()
            groups[cleanName]?.add(station)
        }
        val finalList = ArrayList<DbStation>()
        val seenUrls = HashMap<String, String>()
        for ((cleanName, versions) in groups) {
            versions.sortByDescending { it.clickCount * 100 + it.bitrate + (if (it.url.startsWith("https")) 10000 else 0) }
            val best = versions.firstOrNull() ?: continue
            val urlLower = best.url.trim().lowercase(Locale.ROOT)
            if (!seenUrls.containsKey(urlLower) || seenUrls[urlLower] != cleanName) {
                seenUrls[urlLower] = cleanName
                finalList.add(best)
            }
        }
        return finalList
    }

    fun getCountries(context: Context, hideDuplicates: Boolean): List<String> {
        ensureLoaded(context)
        synchronized(this) {
            return countries.map { "${it.first} (${it.second})" }
        }
    }

    fun getGenres(context: Context, mainGenres: List<String>): List<String> {
        ensureLoaded(context)
        synchronized(this) {
            val map = HashMap<String, Int>()
            for (g in genres) {
                map[g.first.lowercase(Locale.ROOT)] = g.second
            }
            val result = ArrayList<String>()
            for (genre in mainGenres) {
                val key = genre.lowercase(Locale.ROOT)
                val count = map[key] ?: 0
                val displayName = genre.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                if (count > 0) {
                    result.add("$displayName ($count)")
                } else {
                    result.add(displayName)
                }
            }
            return result
        }
    }

    fun getStationsByCountry(context: Context, country: String, minBitrate: Int, hideDuplicates: Boolean): List<Station> {
        ensureLoaded(context)
        val target = country.trim().lowercase(Locale.ROOT)
        var list: List<DbStation>
        synchronized(this) {
            list = stations.filter {
                it.country.trim().lowercase(Locale.ROOT) == target && it.bitrate >= minBitrate
            }
        }
        if (hideDuplicates) {
            list = deduplicate(list)
        }
        return list.sortedBy { it.name.lowercase(Locale.getDefault()) }.map { it.toStation() }
    }

    fun getStationsByGenre(context: Context, genre: String, minBitrate: Int, hideDuplicates: Boolean): List<Station> {
        ensureLoaded(context)
        val target = genre.trim().lowercase(Locale.ROOT)
        var list: List<DbStation>
        synchronized(this) {
            list = stations.filter { s ->
                if (s.bitrate < minBitrate) return@filter false
                val tags = s.tags.split(",")
                for (t in tags) {
                    if (t.trim().lowercase(Locale.ROOT) == target) return@filter true
                }
                false
            }
        }
        if (hideDuplicates) {
            list = deduplicate(list)
        }
        return list.sortedBy { it.name.lowercase(Locale.getDefault()) }.map { it.toStation() }
    }

    fun searchStations(context: Context, query: String, minBitrate: Int, hideDuplicates: Boolean): List<Station> {
        ensureLoaded(context)
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return emptyList()
        var list: List<DbStation>
        synchronized(this) {
            list = stations.filter { s ->
                if (s.bitrate < minBitrate) return@filter false
                s.name.lowercase(Locale.getDefault()).contains(q) ||
                s.country.lowercase(Locale.getDefault()).contains(q) ||
                s.tags.lowercase(Locale.getDefault()).contains(q)
            }
        }
        if (hideDuplicates) {
            list = deduplicate(list)
        }
        return list.sortedBy { it.name.lowercase(Locale.getDefault()) }.map { it.toStation() }
    }

    fun getPopularStations(context: Context, limit: Int = 100, minBitrate: Int, hideDuplicates: Boolean): List<Station> {
        ensureLoaded(context)
        var list: List<DbStation>
        synchronized(this) {
            list = stations.filter { it.bitrate >= minBitrate }
        }
        if (hideDuplicates) {
            list = deduplicate(list)
        }
        return list.sortedByDescending { it.clickCount + it.votes }.take(limit).map { it.toStation() }
    }
}
