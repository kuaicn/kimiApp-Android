package com.kimi.app.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.strNotBlank(key: String): String? = str(key)?.takeIf { it.isNotBlank() }

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** ISO 时间字符串比较之外的统一展示：2026-07-28T04:57:22.463Z → 04:57 或 07-28 04:57 */
fun formatTimeShort(iso: String): String {
    if (iso.length < 16) return ""
    return try {
        val instant = java.time.Instant.parse(iso)
        val zone = java.time.ZoneId.systemDefault()
        val time = java.time.ZonedDateTime.ofInstant(instant, zone)
        val now = java.time.ZonedDateTime.now(zone)
        if (time.toLocalDate() == now.toLocalDate()) {
            "%02d:%02d".format(time.hour, time.minute)
        } else {
            "%02d-%02d %02d:%02d".format(time.monthValue, time.dayOfMonth, time.hour, time.minute)
        }
    } catch (e: Exception) {
        ""
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
