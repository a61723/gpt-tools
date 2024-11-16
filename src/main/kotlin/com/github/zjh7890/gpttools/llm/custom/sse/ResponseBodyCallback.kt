// MIT License
//
//Copyright (c) [year] [fullname]
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in all
//copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//SOFTWARE.
package com.github.zjh7890.gpttools.llm.custom.sse

import com.intellij.openapi.diagnostic.logger
import io.reactivex.rxjava3.core.FlowableEmitter
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets


/**
 * Callback to parse Server Sent Events (SSE) from raw InputStream and
 * emit the events with io.reactivex.FlowableEmitter to allow streaming of
 * SSE.
 */
class ResponseBodyCallback(private val emitter: FlowableEmitter<SSE>, private val emitDone: Boolean) : Callback {
    override fun onResponse(call: Call, response: Response) {
        var reader: BufferedReader? = null
        try {
            if (!response.isSuccessful) {
                if (response.body == null) {
                    throw GptToolsHttpException("Response body is null", response.code)
                } else {
                    throw GptToolsHttpException(response.body?.string() ?: "", response.code)
                }
            }

            val inputStream = response.body!!.byteStream()
            reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            var line: String? = null
            var sse: SSE? = null
            while (!emitter.isCancelled && reader.readLine().also { line = it } != null) {
//                logger<ResponseBodyCallback>().warn("SSE Read line: $line")
                sse = when {
                    line!!.startsWith("data:") -> {
                        val data = line!!.substring(5).trim { it <= ' ' }
                        SSE(data)
                    }

                    line == "" && sse != null -> {
                        if (sse.isDone) {
                            if (emitDone) {
                                emitter.onNext(sse)
                            }
                            break
                        }
                        emitter.onNext(sse)
                        null
                    }
                    // starts with event:
                    line!!.startsWith("event:") -> {
                        // https://github.com/sysid/sse-starlette/issues/16
                        val eventName = line!!.substring(6).trim { it <= ' ' }
                        if (eventName == "ping") {
                            // skip ping event and data
                            emitter.onNext(sse!!)
                            emitter.onNext(sse)
                        }

                        null
                    }

                    // skip `: ping` comments for: https://github.com/sysid/sse-starlette/issues/16
                    line!!.startsWith(": ping") -> {
                        null
                    }

                    else -> {
                        when {
                            // sometimes the server maybe returns empty line
                            line == "" -> {
                                null
                            }

                            // : is comment
                            // https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream
                            line!!.startsWith(":") -> {
                                null
                            }

                            else -> {
                                throw SSEFormatException("Invalid sse format! '$line'")
                            }
                        }
                    }
                }
            }

            emitter.onComplete()
        } catch (t: Throwable) {
            logger<ResponseBodyCallback>().error("Error while reading SSE", t)
            onFailure(call, IOException(t))
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (e: IOException) {
                    // do nothing
                }
            }
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        emitter.onError(e)
    }
}
