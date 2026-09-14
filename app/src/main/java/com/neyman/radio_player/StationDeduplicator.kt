package com.neyman.radio_player

import org.json.JSONObject

object StationDeduplicator {
    
    private fun normalizeName(name: String): String {
        var n = name.lowercase()
        n = n.replace(Regex("\\d+\\.\\d+"), "")
        n = n.replace(Regex("\\d+"), "")
        val words = listOf("fm", "radio", "tv", "online", "broadcast", "digital")
        for (w in words) {
            n = n.replace(w, "")
        }
        n = n.replace(Regex("[^\\w\\s]"), "")
        return n.replace(" ", "")
    }

    private fun getStationScore(station: JSONObject): Int {
        var score = 0
        val url = station.optString("url_resolved", station.optString("url", "")).trim().lowercase()
        
        if (station.optString("lastcheckok", "0") == "1") score += 5000000
        score += station.optInt("clickcount", 0) * 100
        
        if (url.contains(".smil") || url.contains("videoonly")) score -= 10000000
        if (url.endsWith(".m3u") || url.endsWith(".pls")) score -= 50000
        
        score += station.optInt("bitrate", 0)
        if (url.startsWith("https")) score += 10000
        
        return score
    }

    fun removeDuplicates(stations: List<JSONObject>): List<JSONObject> {
        if (stations.isEmpty()) return emptyList()
        
        val groups = HashMap<String, ArrayList<JSONObject>>()
        for (station in stations) {
            val name = station.optString("name", "").trim()
            if (name.isEmpty()) continue
            
            var cleanName = normalizeName(name)
            if (cleanName.isEmpty()) cleanName = name.lowercase()
            
            if (!groups.containsKey(cleanName)) groups[cleanName] = ArrayList()
            groups[cleanName]?.add(station)
        }
        
        val finalList = ArrayList<JSONObject>()
        val seenUrls = HashMap<String, String>()
        
        for ((cleanName, versions) in groups) {
            versions.sortByDescending { getStationScore(it) }
            val bestVersion = versions.firstOrNull() ?: continue
            val url = bestVersion.optString("url_resolved", "").trim().lowercase()
            
            if (!seenUrls.containsKey(url) || seenUrls[url] != cleanName) {
                seenUrls[url] = cleanName
                finalList.add(bestVersion)
            }
        }
        return finalList
    }
}