package com.user.localagent.core

import kotlinx.serialization.Serializable

/** Explicit named presets. Capacity never silently evicts an owner's saved recipe. */
@Serializable
data class ImageConsistencyLibrary(val schemaVersion:Int=2,val recipes:List<ImageConsistencyRecipe> = emptyList()) {
    fun validate():ImageConsistencyLibrary=also {
        require(schemaVersion==2&&recipes.size<=MAX_PRESETS)
        recipes.forEach{it.validate()}
        require(recipes.map{it.title.trim()}.distinct().size==recipes.size)
    }
    fun save(recipe:ImageConsistencyRecipe):ImageConsistencyLibrary {
        validate()
        val normalized=recipe.copy(title=recipe.title.trim()).validate()
        return copy(recipes=listOf(normalized)+recipes.filterNot{it.title.trim()==normalized.title}).validate()
    }
    fun remove(expected:ImageConsistencyRecipe):ImageConsistencyLibrary {
        validate()
        val actual=recipes.firstOrNull{it.title.trim()==expected.title.trim()}
        check(actual==expected){"시리즈 조건이 변경되었습니다. 최신 목록에서 다시 확인해주세요."}
        return copy(recipes=recipes.filterNot{it.title.trim()==expected.title.trim()}).validate()
    }
    companion object {const val MAX_PRESETS=12}
}
