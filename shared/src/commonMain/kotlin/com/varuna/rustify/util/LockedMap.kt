// shared/src/commonMain/kotlin/com/varuna/rustify/util/LockedMap.kt
package com.varuna.rustify.util

/**
 * A map whose every call holds one [Lock]: `Collections.synchronizedMap(mutableMapOf())` in common
 * code, for the caches the screens fill from the main thread and the repository clears from IO. As
 * with that one, walking [keys], [values] or [entries] is not covered by the lock.
 */
class LockedMap<K, V> : MutableMap<K, V> {
    private val lock = Lock()
    private val map = LinkedHashMap<K, V>()

    override val size: Int get() = lock.locked { map.size }
    override fun isEmpty(): Boolean = lock.locked { map.isEmpty() }
    override fun containsKey(key: K): Boolean = lock.locked { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = lock.locked { map.containsValue(value) }
    override fun get(key: K): V? = lock.locked { map[key] }
    override fun put(key: K, value: V): V? = lock.locked { map.put(key, value) }
    override fun remove(key: K): V? = lock.locked { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = lock.locked { map.putAll(from) }
    override fun clear() = lock.locked { map.clear() }

    override val keys: MutableSet<K> get() = map.keys
    override val values: MutableCollection<V> get() = map.values
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries

    override fun equals(other: Any?): Boolean = other === this || lock.locked { map == other }
    override fun hashCode(): Int = lock.locked { map.hashCode() }
    override fun toString(): String = lock.locked { map.toString() }
}
