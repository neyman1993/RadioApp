package com.neyman.radio_player

import org.json.JSONObject

data class Station(val name: String, val url: String, val country: String) {
    fun toJson() = JSONObject().apply { put("name", name); put("url", url); put("country", country) }
    companion object {
        fun fromJson(j: JSONObject): Station {
            val name = j.optString("name", "")
            // Prefer url_resolved (the actual playable URL) over url
            val urlResolved = j.optString("url_resolved", "")
            val url = if (urlResolved.isNotEmpty()) urlResolved else j.optString("url", "")
            val country = j.optString("country", "")
            return Station(name, url, country)
        }
    }
}
