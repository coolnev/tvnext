package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.*;

import android.app.Application;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runs the real Media3 Java + unchanged JNI + FFmpeg decoder, with the Linux host build. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class DolbyNativeDecodeTest {
  @Test public void libraryContainsOnlyTheRequiredDolbyDecoders() {
    assertTrue("Set TVNEXT_FFMPEG_HOST_LIBRARY after running tools/build_native.py --host",FfmpegLibrary.isAvailable());
    assertNotNull(FfmpegLibrary.getVersion());
    assertTrue(FfmpegLibrary.supportsFormat("audio/ac3"));
    assertTrue(FfmpegLibrary.supportsFormat("audio/eac3"));
    assertFalse(FfmpegLibrary.supportsFormat("audio/aac"));
    assertFalse(FfmpegLibrary.supportsFormat("video/hevc"));
  }
  @Test public void ac3FivePointOneReallyProducesNonSilentPcmWithTheDialogueInTheCentre() throws Exception { decode("audio/ac3","centre-5.1.ac3"); }
  @Test public void eac3FivePointOneReallyProducesNonSilentPcm() throws Exception { decode("audio/eac3","centre-5.1.eac3"); }
  private void decode(String mime,String resource) throws Exception {
    assertTrue(FfmpegLibrary.isAvailable());
    byte[] packet;
    try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)){assertNotNull(in);packet=in.readAllBytes();}
    Format format=new Format.Builder().setSampleMimeType(mime).setChannelCount(6).setSampleRate(48000).build();
    FfmpegAudioDecoder decoder=new FfmpegAudioDecoder(format,4,4,4096,false);
    try {
      DecoderInputBuffer input=null;
      for(int i=0;i<2000 && input==null;i++){input=decoder.dequeueInputBuffer();if(input==null)Thread.sleep(1);}
      assertNotNull(input);input.ensureSpaceForWrite(packet.length);input.data.put(packet);input.flip();decoder.queueInputBuffer(input);
      SimpleDecoderOutputBuffer output=null;
      for(int i=0;i<5000 && output==null;i++){output=decoder.dequeueOutputBuffer();if(output==null)Thread.sleep(1);}
      assertNotNull("Real decoder returned no PCM",output);
      try {
        assertEquals(C.ENCODING_PCM_16BIT,decoder.getEncoding());
        assertEquals(48000,decoder.getSampleRate());assertEquals(6,decoder.getChannelCount());
        ByteBuffer pcm=output.data.duplicate().order(ByteOrder.nativeOrder());
        assertTrue(pcm.remaining()>6*2*100);
        long[] energy=new long[6];int index=0;
        while(pcm.remaining()>=2){long sample=pcm.getShort();energy[index++%6]+=sample*sample;}
        assertTrue("AC3 centre/dialogue was silent",energy[2]>1_000_000L);
        for(int channel:new int[]{0,1,3,4,5})assertTrue("Unexpected channel layout",energy[channel]<energy[2]/100+1);
      } finally{output.release();}
    } finally{decoder.release();}
  }
}
