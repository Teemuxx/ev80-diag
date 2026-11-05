package io.github.ev80diag.data

import android.content.SharedPreferences

/** The few SharedPreferences calls the stores need, so they run in JVM tests on [MapKeyValue]. */
interface KeyValue {
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getString(key: String): String?
    fun keys(): Set<String>
    fun putBoolean(key: String, v: Boolean)
    fun putFloat(key: String, v: Float)
    fun putLong(key: String, v: Long)
    fun putInt(key: String, v: Int)
    fun putString(key: String, v: String)
    fun remove(key: String)
}

class PrefsKeyValue(private val p: SharedPreferences) : KeyValue {
    override fun contains(key: String) = p.contains(key)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getString(key: String): String? = p.getString(key, null)
    override fun keys(): Set<String> = p.all.keys
    override fun putBoolean(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()
    override fun putFloat(key: String, v: Float) = p.edit().putFloat(key, v).apply()
    override fun putLong(key: String, v: Long) = p.edit().putLong(key, v).apply()
    override fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()
    override fun putString(key: String, v: String) = p.edit().putString(key, v).apply()
    override fun remove(key: String) = p.edit().remove(key).apply()
}

class MapKeyValue : KeyValue {
    private val m = HashMap<String, Any>()
    override fun contains(key: String) = key in m
    override fun getBoolean(key: String, default: Boolean) = m[key] as? Boolean ?: default
    override fun getFloat(key: String, default: Float) = m[key] as? Float ?: default
    override fun getLong(key: String, default: Long) = m[key] as? Long ?: default
    override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
    override fun getString(key: String) = m[key] as? String
    override fun keys(): Set<String> = m.keys.toSet()
    override fun putBoolean(key: String, v: Boolean) { m[key] = v }
    override fun putFloat(key: String, v: Float) { m[key] = v }
    override fun putLong(key: String, v: Long) { m[key] = v }
    override fun putInt(key: String, v: Int) { m[key] = v }
    override fun putString(key: String, v: String) { m[key] = v }
    override fun remove(key: String) { m.remove(key) }
}
