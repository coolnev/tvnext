package com.tvnext.app.core.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One source owns all its manifest/segment calls, including ones created after cancellation. */
internal class AndroidPlaybackNetworkSession(context:Context) {
    private val canceled=AtomicBoolean(false)
    private val calls=ConcurrentHashMap.newKeySet<Call>()
    private val client=sharedClient.newBuilder().eventListener(object:EventListener() {
        override fun callEnd(call:Call) {calls.remove(call)}
        override fun callFailed(call:Call,ioe:IOException) {calls.remove(call)}
    }).build()
    private val callFactory=object:Call.Factory {
        override fun newCall(request:Request):Call = client.newCall(request).also {call->
            calls.add(call)
            if(canceled.get()) {calls.remove(call);call.cancel()}
        }
    }
    val factory:DataSource.Factory=DefaultDataSource.Factory(context,
        OkHttpDataSource.Factory(callFactory).setUserAgent(AndroidPlaybackDataSources.USER_AGENT))

    /** Call.cancel closes sockets; interrupting HttpURLConnection reads alone does not. */
    fun cancel() {
        canceled.set(true)
        calls.forEach {it.cancel()}
        calls.clear()
    }

    private companion object {
        // Sessions share the pool/dispatcher, but cancel only their own calls.
        val sharedClient=OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
            .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    }
}
