import com.tvnext.app.core.player.*;
import java.io.File;
import javax.swing.*;
import java.util.function.BooleanSupplier;
/** Exercises the production controller with an unsupported graphics host and real Windows MPV. */
public class WindowsFallbackSmoke {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS "+message);}
 static void await(BooleanSupplier condition,String message)throws Exception {long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){if(condition.getAsBoolean()){System.out.println("PASS "+message);return;}Thread.sleep(100);}throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  System.setProperty("tvnext.mpv.audioOutput","null");WindowsGpuRuntime.INSTANCE.prepare();
  check(WindowsGpuRuntime.INSTANCE.getPrepared(),"shared ANGLE native directory detected");
  MpvPlaybackController player=new MpvPlaybackController();JFrame[] host=new JFrame[1];int result=0;
  try {
   SwingUtilities.invokeAndWait(()->{host[0]=new JFrame("Unsupported GPU host fixture");host[0].setSize(640,360);host[0].setVisible(true);player.attachGpuWindow(host[0]);});
   player.updateSurfaceSize(640,360);player.updateWindowsVideoMode(WindowsVideoMode.AUTO);
   player.play(new PlaybackItem("fallback","Fixture",new File(args[0]).toURI().toString(),PlaybackContentKind.VOD,null));
   await(()->player.getRenderStatus().getValue().contains("ANGLE / GPU недоступен"),"automatic unsupported GPU detection");
   await(()->!player.getGpuOutput().getValue() && player.getFrames().getValue()!=null && player.getState().getValue().getPositionMs()>500,"compatibility playback produces frames after GPU fallback");
   await(()->player.getState().getValue().getDiagnostics()!=null && player.getState().getValue().getDiagnostics().getVideoOutput().contains("SW"),"diagnostics report actual software output");
   player.updateWindowsVideoMode(WindowsVideoMode.HARDWARE_COMPATIBLE);
   await(()->player.getRenderStatus().getValue().equals("Совместимый вывод"),"compatible mode remains usable");
   player.updateWindowsVideoMode(WindowsVideoMode.SOFTWARE);
   await(()->player.getState().getValue().getDiagnostics()!=null && player.getState().getValue().getDiagnostics().getActualMode().equals(PlayerDiagnostics.MODE_SOFTWARE),"software mode uses CPU decoder");
   player.updateWindowsVideoMode(WindowsVideoMode.HARDWARE_GPU);
   await(()->player.getGpuOutput().getValue(),"explicit GPU mode attempts GPU output");
   await(()->!player.getGpuOutput().getValue() && player.getFrames().getValue()!=null,"repeated fallback restores video track");
   long before=player.getState().getValue().getPositionMs();await(()->player.getState().getValue().getPositionMs()>before+500,"clock advances after output recreation");
   System.out.println("WINDOWS_FALLBACK_SMOKE_PASSED");
  }catch(Throwable error){result=1;System.err.println("LAST_STATE "+player.getState().getValue());System.err.println("RENDER "+player.getRenderStatus().getValue());error.printStackTrace();}
  finally {Thread watchdog=new Thread(()->{try{Thread.sleep(10000);}catch(Exception ignored){}System.err.println("RELEASE_TIMEOUT");Runtime.getRuntime().halt(2);});watchdog.setDaemon(true);watchdog.start();player.release();SwingUtilities.invokeAndWait(()->{if(host[0]!=null)host[0].dispose();});System.out.println("PASS release");}
  System.exit(result);
 }
}
