package org.researchgroup.git2neo.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Created by  on 3/27/17.
 */
class FixedSizeCache<K,V>(val sizeLimit: Int) {
    private val map: MutableMap<K, V> = ConcurrentHashMap()

    fun containsKey(key: K) = map.containsKey(key)

    fun put(key: K, value: V): Unit {
        if (map.size > sizeLimit) {
            println("Clearing cache: exceeded $sizeLimit entries")
            map.clear()
        }
        map[key] = value
    }

    fun get(key: K): V? {
        return map[key]
    }
}