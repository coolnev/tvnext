package com.tvnext.app.core.player

/** Dolby PCM fallback is independent from the selected VIDEO decoder. */
enum class AudioOutputMode { AUTO, PCM_STEREO, PASSTHROUGH }

fun stereoMixCoefficients(channels:Int):FloatArray {
    require(channels in 1..8)
    if(channels==1)return floatArrayOf(1f,1f)
    val coefficients=FloatArray(channels*2)
    coefficients[0]=1f;coefficients[3]=1f
    fun put(input:Int,left:Float,right:Float){coefficients[input*2]=left;coefficients[input*2+1]=right}
    when(channels) {
        3->{put(2,.7071f,.7071f)}
        4->{put(2,.7071f,0f);put(3,0f,.7071f)}
        5->{put(2,.7071f,.7071f);put(3,.7071f,0f);put(4,0f,.7071f)}
        6->{put(2,.7071f,.7071f);put(3,.5f,.5f);put(4,.7071f,0f);put(5,0f,.7071f)}
        7->{put(2,.7071f,.7071f);put(3,.5f,.5f);put(4,.5f,.5f);put(5,.7071f,0f);put(6,0f,.7071f)}
        8->{put(2,.7071f,.7071f);put(3,.5f,.5f);put(4,.7071f,0f);put(5,0f,.7071f);put(6,.7071f,0f);put(7,0f,.7071f)}
    }
    val maxSum=maxOf((0 until channels).sumOf{coefficients[it*2].toDouble()},(0 until channels).sumOf{coefficients[it*2+1].toDouble()}).toFloat()
    if(maxSum>1f)for(i in coefficients.indices)coefficients[i]/=maxSum
    return coefficients
}
