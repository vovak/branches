package org.researchgroup.git2neo.util

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by on 09/11/16.
 */
class StringIntern(concurrent: Boolean) {
    val map: MutableMap<String, String> = if (concurrent) ConcurrentHashMap() else HashMap()
    fun intern(str: String): String {
        if (str in map) return map[str]!!
        map[str] = str
        return str
    }
}