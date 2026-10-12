package com.lightphone.audiobooks.library

import org.json.JSONObject
import java.io.File

/**
 * Small org.json file helpers for the tool's durable state. The stores live
 * in the tool's own `lightContext.filesDir` (the SDK's sanctioned in-process
 * storage — no Context in this module), written atomically: the payload goes
 * to a sibling `.tmp` file that is renamed over the target, so a crash mid
 * write never truncates the store.
 */
internal fun readJson(file: File): JSONObject? = runCatching {
    if (!file.isFile) return null
    file.readText().takeIf(String::isNotBlank)?.let(::JSONObject)
}.getOrNull()

internal fun writeJsonAtomically(file: File, json: JSONObject) {
    runCatching {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
