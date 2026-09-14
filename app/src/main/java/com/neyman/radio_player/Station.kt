package com.neyman.radio_player

import org.json.JSONObject

data class Station(val name: String, val url: String, val country: String) {
    fun toJson() = JSONObject().apply { put("name", name); put("url", url); put("country", country) }
    companion object {
        fun fromJson(j: JSONObject) = Station(j.optString("name"), j.optString("url"), j.optString("country"))
    }
}