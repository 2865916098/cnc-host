package com.cnc.host.persistence

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room TypeConverter 集合。
 *
 * 用 Gson 序列化复杂类型（List / Map / 嵌套对象）。
 * 单例——Room 编译期需要可实例化的无参构造。
 */
class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: String): List<String> {
        return gson.fromJson(value, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    }

    @TypeConverter
    fun toStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun fromStringIntMap(value: String): Map<String, Int> {
        return gson.fromJson(value, object : TypeToken<Map<String, Int>>() {}.type) ?: emptyMap()
    }

    @TypeConverter
    fun toStringIntMap(value: Map<String, Int>): String = gson.toJson(value)

    @TypeConverter
    fun fromStringAnyMap(value: String): Map<String, String> {
        return gson.fromJson(value, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
    }

    @TypeConverter
    fun toStringAnyMap(value: Map<String, String>): String = gson.toJson(value)
}
