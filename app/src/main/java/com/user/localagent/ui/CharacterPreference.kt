package com.user.localagent.ui

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The same appearance is restored across the native host and offline chat page. */
internal data class CharacterPreference(val variant:String,val color:String,val expr:String) {
    fun toJson()=buildJsonObject {
        put("variant",variant)
        put("color",color)
        put("expr",expr)
    }

    fun update(field:String,value:String):CharacterPreference = when(field) {
        "variant"->copy(variant=value)
        "color"->copy(color=value)
        "expr"->copy(expr=value)
        else->this
    }

    // Atlas rows are colors. Each variant occupies a consecutive group of expression columns.
    fun portraitCell(): Pair<Int, Int> {
        val variants = options.getValue("variant")
        val expressions = options.getValue("expr")
        val column = variants.indexOf(variant) * expressions.size + expressions.indexOf(expr)
        return column to options.getValue("color").indexOf(color)
    }

    fun save(prefs:SharedPreferences) {
        prefs.edit().putString("variant",variant).putString("color",color).putString("expr",expr).apply()
    }

    companion object {
        private val options=mapOf(
            "variant" to listOf("bear","cat","bunny"),
            "color" to listOf("butter","peach","strawberry","sky","matcha","grey","cocoa","snow"),
            "expr" to listOf("basic","smile","focus","surprise","sleepy","wink")
        )
        fun accepts(field:String,value:String):Boolean = options[field]?.contains(value)==true
        private val default=CharacterPreference("bear","butter","basic")

        fun parse(value:JsonObject):CharacterPreference? {
            if(value.keys!=options.keys)return null
            fun text(key:String)= (value[key] as? JsonPrimitive)?.takeIf{it.isString}?.content.orEmpty()
            val choice=CharacterPreference(text("variant"),text("color"),text("expr"))
            return choice.takeIf{accepts("variant",it.variant)&&accepts("color",it.color)&&accepts("expr",it.expr)}
        }

        fun load(prefs:SharedPreferences):CharacterPreference = parse(buildJsonObject {
            for(field in options.keys) put(field,prefs.getString(field,null))
        })?:default
    }
}
