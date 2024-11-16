package com.github.zjh7890.gpttools.llm.custom.sse

class SSE(val data: String) {
    fun toBytes(): ByteArray {
        return String.format("data: %s\n\n", this.data).toByteArray()
    }

    val isDone: Boolean
        get() = DONE_DATA.equals(this.data, ignoreCase = true)

    companion object {
        private const val DONE_DATA = "[DONE]"
    }
}
