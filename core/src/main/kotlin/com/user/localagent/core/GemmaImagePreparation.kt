package com.user.localagent.core

/** Artifact identity for explicit image-helper requests; normal conversation never uses this override. */
object GemmaImagePreparation {
    val hashes=mapOf("gemma-e2b" to "0796d58372742ef8ddc76dd64cd2fde217b7ef32e3dc58e10873253e569cad6b",
        "gemma-e4b" to "d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96")
    fun modelId(fileName:String):String {
        val match=Regex("^(gemma-e[24]b)-([a-f0-9]{12,64})\\.gguf$").matchEntire(fileName)
            ?:error("이미지 보조용 Gemma E2B/E4B 모델 파일이 필요합니다.")
        val id=match.groupValues[1]
        require(hashes.getValue(id).startsWith(match.groupValues[2])){"Gemma 모델 식별 해시가 다릅니다."}
        return id
    }
}
