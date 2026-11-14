package com.tvnext.app.core.player

import kotlin.test.*

class AutoFrameRateTest {
    private fun mode(id:Int,hz:Double,w:Int=3840,h:Int=2160,alternatives:List<Double> = emptyList())=VideoDisplayMode(id,w,h,hz,alternatives)
    private val original=mode(1,60.0)
    private val prefs=AutoFrameRatePreferences(enabled=true)
    @Test fun exactCadencesKeepFractionalAndIntegerFamiliesSeparate() {
        for((fps,hz) in listOf(24000.0/1001 to 23.976,24.0 to 24.0,25.0 to 50.0,30000.0/1001 to 59.94,30.0 to 60.0,50.0 to 100.0,60000.0/1001 to 119.88))
            assertTrue(cadenceMatches(hz,fps),"$fps -> $hz")
        assertFalse(cadenceMatches(24.0,24000.0/1001));assertFalse(cadenceMatches(60.0,30000.0/1001))
        assertFalse(cadenceMatches(60.0,25.0));assertFalse(cadenceMatches(Double.NaN,25.0))
    }
    @Test fun rateMatchingChooses50And5994AndDoesNotForceAlreadyCompatibleModes() {
        val modes=listOf(original,mode(2,50.0),mode(3,59.94),mode(4,23.976),mode(5,24.0))
        for((fps,id) in listOf(25.0 to 2,29.97 to 3,23.976 to 4,24.0 to 5,30.0 to 1,50.0 to 2))
            assertEquals(id,chooseVideoDisplayMode(modes,original,original,VideoFrameSignal(1920,1080,fps),prefs)?.id)
    }
    @Test fun resolutionChangesAreOptInAndFollowVideoWithoutDroppingItsPixels() {
        val modes=listOf(original,mode(2,50.0),mode(3,50.0,1920,1080),mode(4,50.0,1280,720))
        val video=VideoFrameSignal(1920,1080,25.0)
        assertEquals(2,chooseVideoDisplayMode(modes,original,original,video,prefs)?.id)
        assertEquals(3,chooseVideoDisplayMode(modes,original,original,video,prefs.copy(matchResolution=true))?.id)
        assertEquals(2,chooseVideoDisplayMode(modes.filter{it.id!=3},original,original,video,prefs.copy(matchResolution=true))?.id)
        assertEquals(4,chooseVideoDisplayMode(modes,original,original,VideoFrameSignal(720,576,25.0),prefs.copy(matchResolution=true))?.id)
        val mixed=listOf(original,mode(3,60.0,1920,1080),mode(4,50.0,1280,720))
        assertEquals(3,chooseVideoDisplayMode(mixed,original,original,video,prefs.copy(matchResolution=true))?.id,
            "Do not lose HD pixels just to obtain a matching rate at 720p")
    }
    @Test fun resolutionOffReturnsToTheOriginalGeometryAfterPreviousSwitch() {
        val current=mode(3,50.0,1920,1080)
        val modes=listOf(original,mode(2,50.0),current)
        assertEquals(2,chooseVideoDisplayMode(modes,current,original,VideoFrameSignal(1920,1080,25.0),prefs)?.id)
    }
    @Test fun seamlessModeOnlyUsesReportedSameResolutionAlternatives() {
        val current=mode(1,60.0,alternatives=listOf(50.0))
        val modes=listOf(current,mode(2,50.0),mode(3,24.0),mode(4,50.0,1920,1080))
        val seamless=prefs.copy(strategy=FrameRateSwitchStrategy.SEAMLESS,matchResolution=true)
        assertEquals(2,chooseVideoDisplayMode(modes,current,current,VideoFrameSignal(1920,1080,25.0),seamless)?.id)
        assertNull(chooseVideoDisplayMode(modes,current,current,VideoFrameSignal(1920,1080,24.0),seamless))
        assertNull(chooseVideoDisplayMode(modes,original,original,VideoFrameSignal(1920,1080,25.0),seamless))
    }
    @Test fun doublingIsOptionalButNeverDiscardsTheOnlyMatchingRate() {
        val modes=listOf(original,mode(2,24.0),mode(3,48.0),mode(4,120.0))
        assertEquals(3,chooseVideoDisplayMode(modes,original,original,VideoFrameSignal(fps=24.0),prefs)?.id)
        assertEquals(2,chooseVideoDisplayMode(modes,original,original,VideoFrameSignal(fps=24.0),prefs.copy(doubleFrameRate=false))?.id)
        assertEquals(2,chooseVideoDisplayMode(modes.take(2),original,original,VideoFrameSignal(fps=24.0),prefs)?.id)
        assertEquals(4,chooseVideoDisplayMode(modes,modes.last(),original,VideoFrameSignal(fps=24.0),prefs)?.id)
    }
    @Test fun unsupportedCadenceAndUnknownFpsDoNotGuessARefreshRate() {
        assertNull(chooseVideoDisplayMode(listOf(original),original,original,VideoFrameSignal(fps=25.0),prefs))
        assertNull(chooseVideoDisplayMode(listOf(original),original,original,VideoFrameSignal(fps=Double.NaN),prefs))
        assertNull(chooseVideoDisplayMode(listOf(original),original,original,VideoFrameSignal(fps=30.0),prefs.copy(enabled=false)))
        val smaller=mode(2,60.0,1920,1080)
        assertEquals(2,chooseVideoDisplayMode(listOf(original,smaller),original,original,VideoFrameSignal(1920,1080),prefs.copy(matchResolution=true))?.id)
    }
    @Test fun estimatorUsesPresentationTimestampsAndDistinguishes23976From24() {
        for(fps in listOf(24000.0/1001,24.0,25.0,30000.0/1001,50.0,60000.0/1001)) {
            val estimator=VideoCadenceEstimator();var result:Double?=null
            for(i in 0..70)estimator.frame((i*1_000_000.0/fps).toLong())?.let{result=it}
            assertNotNull(result);assertEquals(fps,result!!,.003)
            estimator.reset();assertNull(estimator.frame(0));assertNull(estimator.frame(40_000))
        }
    }
    @Test fun estimatorRejectsVariableCadenceDiscontinuitiesAndDuplicateTimestamps() {
        val estimator=VideoCadenceEstimator();var stamp=0L
        for(i in 0..100){stamp+=if(i%2==0)40000 else 33367;assertNull(estimator.frame(stamp))}
        estimator.reset();assertNull(estimator.frame(0));assertNull(estimator.frame(5_000_000));assertNull(estimator.frame(5_000_000))
    }
    @Test fun roundedProviderRateCanBeCorrectedBeforeTheSwitchAndDroppedFramesDoNotHalveIt() {
        val sampler=VideoCadenceEstimator();var estimate:Double?=null
        for(i in 0..12)sampler.frame((i*1_000_000.0/(24000.0/1001)).toLong(),12)?.let{estimate=it}
        assertNotNull(estimate);assertEquals(24000.0/1001,verifiedVideoFrameRate(24.0,estimate!!),.003)
        assertEquals(50.0,verifiedVideoFrameRate(50.0,25.0))
        assertEquals(60000.0/1001,verifiedVideoFrameRate(60.0,60000.0/1001),.0001)
    }
    @Test fun androidUsesTheUhdSurfaceForAnimationAsWellAsSteadyVideo() {
        for(animated in listOf(false,true))assertFalse(useTextureForVideoTransition(animated,true))
        assertTrue(useTextureForVideoTransition(true,false))
    }
    @Test fun stereoMixKeepsDialogueInBothSpeakersAndCannotOverflowFullScale() {
        for(channels in 3..8) {
            val matrix=stereoMixCoefficients(channels)
            val centre=if(channels==4)0 else 2
            if(channels!=4) {assertTrue(matrix[centre*2]>0);assertEquals(matrix[centre*2],matrix[centre*2+1])}
            assertTrue((0 until channels).sumOf{matrix[it*2].toDouble()}<=1.00001)
            assertTrue((0 until channels).sumOf{matrix[it*2+1].toDouble()}<=1.00001)
        }
    }
    @Test fun stereoIdentityAndMonoDuplicationDoNotLoseAnAudioChannel() {
        assertContentEquals(floatArrayOf(1f,0f,0f,1f),stereoMixCoefficients(2))
        assertContentEquals(floatArrayOf(1f,1f),stereoMixCoefficients(1))
    }
    @Test fun defaultsPreserveExistingOutputAndInvalidDelayIsNormalized() {
        assertFalse(AutoFrameRatePreferences().enabled);assertFalse(AutoFrameRatePreferences().matchResolution)
        assertEquals(1000,AutoFrameRatePreferences(delayMs=-7).normalized().delayMs)
        assertEquals("23.976",frameRateNumber(24000.0/1001));assertEquals("25",frameRateNumber(25.0))
    }
}
