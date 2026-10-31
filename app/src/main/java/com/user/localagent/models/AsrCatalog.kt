package com.user.localagent.models

object AsrCatalog {
    const val DEVICE_MODEL="asr-mlkit"
    const val DEVICE_PATH="device-mlkit.aicore"
    val modelIds=setOf("asr-qwen-06b","asr-qwen-17b")
    const val DEFAULT_MODEL="asr-qwen-17b"
    // Reviewed immutable official ggml-org conversions; weights remain separate downloads.
    val descriptors=mapOf(
        "asr-qwen-06b" to artifact("asr-qwen-06b","Qwen3-ASR 0.6B","ggml-org/Qwen3-ASR-0.6B-GGUF","928ab958557df9aa2ef1c93e0e83c7ad0933fae2","Qwen3-ASR-0.6B-Q8_0.gguf",804749248L,"bca259818b50ca7c4c05e9bdb35a5dc04fa039653a6d6f3f0f331f96f6aa1971"),
        "asr-qwen-06b-mmproj" to artifact("asr-qwen-06b-mmproj","Qwen3-ASR 0.6B 음성 인코더","ggml-org/Qwen3-ASR-0.6B-GGUF","928ab958557df9aa2ef1c93e0e83c7ad0933fae2","mmproj-Qwen3-ASR-0.6B-bf16.gguf",378575520L,"dae36c855f9a82a8916bea2238b24bda69a39d8da8b2f46dee7c103775656039"),
        "asr-qwen-17b" to artifact("asr-qwen-17b","Qwen3-ASR 1.7B","ggml-org/Qwen3-ASR-1.7B-GGUF","36a678687ba7d07a74ca70ccb0e36902e005fb80","Qwen3-ASR-1.7B-Q8_0.gguf",2165034944L,"58e22d0532d4eacaf034cfac17a6fed159f37c41390c710186783be439d1fc57"),
        "asr-qwen-17b-mmproj" to artifact("asr-qwen-17b-mmproj","Qwen3-ASR 1.7B 음성 인코더","ggml-org/Qwen3-ASR-1.7B-GGUF","36a678687ba7d07a74ca70ccb0e36902e005fb80","mmproj-Qwen3-ASR-1.7B-bf16.gguf",641773984L,"8882e9ddab3186f9aa71b1417c847177913e1466655ac944cf86e9b846735d62"),
    )
    private fun artifact(id:String,title:String,repository:String,revision:String,file:String,bytes:Long,sha:String)=
        ModelDescriptor(id,title,"https://huggingface.co/$repository/resolve/$revision/$file",sha,bytes,"gguf",revision,file)
}
data class DeviceAsrStatus(val available:Boolean=false,val downloadable:Boolean=false,val message:String="지원 여부 확인 필요")
data class AsrSelection(val modelId:String=AsrCatalog.DEFAULT_MODEL,val language:String="ko")
