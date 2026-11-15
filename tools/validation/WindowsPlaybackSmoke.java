import com.tvnext.app.core.player.*;
import java.io.File;
import java.util.function.BooleanSupplier;

/** Runs against the packaged Windows JRE, DLLs and JARs. No provider credentials. */
public class WindowsPlaybackSmoke {
    static void check(boolean ok,String label) {if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
    static void await(BooleanSupplier condition,String label) throws Exception {
        long end=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<end) {if(condition.getAsBoolean()){System.out.println("PASS "+label);return;}Thread.sleep(100);}
        throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        MpvPlaybackController player=new MpvPlaybackController();
        int result=0;
        try {
            player.updateDecodeMode(VideoDecodeMode.SOFTWARE);
            player.updateAudioOutputMode(AudioOutputMode.PCM_STEREO);
            player.updateSurfaceSize(640,360);
            for(int i=0;i<4;i++) {
                String file=args[i%2];
                PlaybackItem item=new PlaybackItem("qa-"+i,"Fixture "+i,new File(file).toURI().toString(),PlaybackContentKind.VOD,null);
                player.play(item);
                await(()->player.getState().getValue().getDiagnostics()!=null && player.getFrames().getValue()!=null,"decoded video "+i);
                PlayerState state=player.getState().getValue();
                System.out.println("FACTS "+state.getDiagnostics());
                check(state.getDiagnostics().getFps()!=null,"source FPS available");
                check(state.getDiagnostics().getAudio()!=null,"audio discovered");
                check(state.getDiagnostics().getActualMode().equals(PlayerDiagnostics.MODE_SOFTWARE),"actual software decode");
                Thread.sleep(1200);
                player.pause();Thread.sleep(1200);
                long before=player.getState().getValue().getPositionMs();Thread.sleep(1400);
                check(player.getState().getValue().getState()==PlaybackState.PAUSED,"native pause persists");
                check(Math.abs(player.getState().getValue().getPositionMs()-before)<300,"paused clock stable");
                player.resume();await(()->player.getState().getValue().getPositionMs()>before+500,"native resume");
                player.seekTo(5000);await(()->player.getState().getValue().getPositionMs()>=4500,"seek");
            }
            player.stop();check(player.getState().getValue().getState()==PlaybackState.IDLE,"stop");
            System.out.println("WINDOWS_PLAYBACK_SMOKE_PASSED");
        } catch(Throwable failure) {
            result=1;System.err.println("LAST_STATE "+player.getState().getValue());failure.printStackTrace();
        } finally {
            Thread watchdog=new Thread(()->{try{Thread.sleep(12000);}catch(InterruptedException ignored){return;}
                System.err.println("SHUTDOWN_TIMEOUT");Thread.getAllStackTraces().forEach((thread,stack)->{
                    System.err.println(thread.getName());for(StackTraceElement frame:stack)System.err.println("  "+frame);
                });Runtime.getRuntime().halt(2);
            },"release-watchdog");watchdog.setDaemon(true);watchdog.start();
            player.release();System.out.println("PASS native release");
        }
        System.exit(result);
    }
}
