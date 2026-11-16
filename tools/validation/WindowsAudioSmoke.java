import com.tvnext.app.core.player.*;
import java.io.File;
public class WindowsAudioSmoke {
 static void await(java.util.function.BooleanSupplier f)throws Exception {long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){if(f.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("Audio output did not initialize");}
 static void mark(String name,long duration)throws Exception {System.out.println("AUDIT "+name+"_start "+System.currentTimeMillis());System.out.flush();Thread.sleep(duration);System.out.println("AUDIT "+name+"_end "+System.currentTimeMillis());System.out.flush();}
 public static void main(String[] args)throws Exception {
  System.setProperty("java.awt.headless","true");MpvPlaybackController p=new MpvPlaybackController();
  p.updateSurfaceSize(640,360);p.updateDecodeMode(VideoDecodeMode.SOFTWARE);
  try {
   p.play(new PlaybackItem("aac","AAC",new File(args[0]).toURI().toString(),PlaybackContentKind.VOD,null));
   await(()->"wasapi".equals(p.getAudioOutput().getValue().getDriver()));
   System.out.println("OUTPUT "+p.getAudioOutput().getValue());mark("aac",3000);
   p.setMuted(true);Thread.sleep(1500);mark("muted",2500);
   p.setMuted(false);p.setVolume(.35f);Thread.sleep(700);mark("quiet",2500);
   p.setVolume(1f);
   p.play(new PlaybackItem("ac3","AC3",new File(args[1]).toURI().toString(),PlaybackContentKind.VOD,null));
   await(()->"wasapi".equals(p.getAudioOutput().getValue().getDriver())&&p.getState().getValue().getDiagnostics()!=null);
   System.out.println("OUTPUT "+p.getAudioOutput().getValue());mark("ac3",3000);
   p.stop();System.out.println("WINDOWS_WASAPI_AUDIO_PASSED");
  } finally {
    Thread watchdog=new Thread(()->{try{Thread.sleep(12000);}catch(Exception ignored){return;}
      System.err.println("AUDIT_RELEASE_OR_SHUTDOWN_TIMEOUT");Thread.getAllStackTraces().forEach((t,stack)->{System.err.println(t.getName());for(StackTraceElement e:stack)System.err.println("  "+e);});Runtime.getRuntime().halt(2);
    });watchdog.setDaemon(true);watchdog.start();
    System.out.println("RELEASING_NATIVE_PLAYER");p.release();System.out.println("NATIVE_PLAYER_RELEASED");
  }
  System.exit(0);
 }
}
