import com.tvnext.app.DesktopBorderlessFullscreen;
import com.tvnext.app.DesktopWindowDiagnostics;
import kotlin.Unit;
import com.sun.jna.Native;
import java.awt.*;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real Windows AWT peer/monitor/focus transitions through the production controller.
 * Tests window UI painting and HWND continuity; does not claim a physical GPU or
 * complete Compose/libmpv composition. args: screenshot output directory.
 */
public class WindowsBorderlessSmoke {
    static JFrame window;
    static Canvas canvas;
    static DesktopBorderlessFullscreen fullscreen;
    static Robot robot;
    static long rootHandle,canvasHandle;
    static int paintCount;
    static void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);
        System.out.println("PASS "+label);
    }
    static void idle()throws Exception {Thread.sleep(300);robot.waitForIdle();}
    static void pixels(String phase)throws Exception {
        Point[] location=new Point[1];Dimension[] size=new Dimension[1];
        SwingUtilities.invokeAndWait(()->{canvas.repaint();location[0]=canvas.getLocationOnScreen();size[0]=canvas.getSize();});
        idle();
        int[] expected={0xff0000,0x00ff00,0x0000ff,0xffff00};
        for(int i=0;i<4;i++) {
            int x=location[0].x+size[0].width*(i%2==0?1:3)/4;
            int y=location[0].y+size[0].height*(i<2?1:3)/4;
            int actual=robot.getPixelColor(x,y).getRGB()&0xffffff;
            int distance=0;for(int shift:new int[]{16,8,0})distance+=Math.abs((actual>>shift&255)-(expected[i]>>shift&255));
            check(distance<60,phase+" UI corner "+i+" actual="+Integer.toHexString(actual));
        }
    }
    static void peers(String phase)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            check(Native.getComponentID(window)==rootHandle,phase+" keeps root HWND");
            check(Native.getComponentID(canvas)==canvasHandle,phase+" keeps rendering HWND");
            check(window.isDisplayable() && canvas.isDisplayable(),phase+" keeps peers displayable");
            check(window.getGraphicsConfiguration().getDevice().getFullScreenWindow()==null,phase+" avoids exclusive fullscreen");
        });
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("java.awt.headless","false");
        File directory=new File(args[0]);directory.mkdirs();System.setProperty("tvnext.data.dir",new File(directory,"data").getAbsolutePath());int result=0;
        try {
            SwingUtilities.invokeAndWait(()->{
                window=new JFrame("TVNext borderless validation");window.setUndecorated(true);
                canvas=new Canvas(){public void paint(Graphics g){
                    paintCount++;int w=getWidth(),h=getHeight();
                    g.setColor(Color.RED);g.fillRect(0,0,w/2,h/2);
                    g.setColor(Color.GREEN);g.fillRect(w/2,0,w-w/2,h/2);
                    g.setColor(Color.BLUE);g.fillRect(0,h/2,w/2,h-h/2);
                    g.setColor(Color.YELLOW);g.fillRect(w/2,h/2,w-w/2,h-h/2);
                }};
                window.add(canvas);window.setBounds(110,90,720,480);window.setVisible(true);
                fullscreen=DesktopBorderlessFullscreen.Companion.forWindow(window,event->{DesktopWindowDiagnostics.INSTANCE.record(event);return Unit.INSTANCE;});
                rootHandle=Native.getComponentID(window);canvasHandle=Native.getComponentID(canvas);
            });
            robot=new Robot();idle();pixels("initial");
            Rectangle[] original=new Rectangle[1];SwingUtilities.invokeAndWait(()->original[0]=window.getBounds());
            for(int cycle=0;cycle<4;cycle++) {
                final int step=cycle;
                SwingUtilities.invokeAndWait(()->{
                    fullscreen.enter();
                    check(fullscreen.isFullscreen(),"entered "+step);
                    check(window.getBounds().equals(window.getGraphicsConfiguration().getBounds()),"fills whole monitor "+step);
                    check(!window.isResizable(),"fullscreen resize handles disabled "+step);
                });
                idle();peers("full "+cycle);pixels("full "+cycle);
                if(cycle==0)ImageIO.write(robot.createScreenCapture(window.getBounds()),"png",new File(directory,"borderless-full.png"));
                SwingUtilities.invokeAndWait(()->fullscreen.exit());idle();
                peers("windowed "+cycle);pixels("windowed "+cycle);
                SwingUtilities.invokeAndWait(()->{
                    check(window.getBounds().equals(original[0]),"original bounds restored "+step);
                    check(window.isResizable(),"resize handles restored "+step);
                    check(!window.isAlwaysOnTop(),"topmost restored "+step);
                });
            }
            SwingUtilities.invokeAndWait(()->window.setExtendedState(Frame.MAXIMIZED_BOTH));idle();
            SwingUtilities.invokeAndWait(()->fullscreen.enter());idle();peers("full from maximized");pixels("full from maximized");
            SwingUtilities.invokeAndWait(()->fullscreen.exit());idle();
            SwingUtilities.invokeAndWait(()->check((window.getExtendedState()&Frame.MAXIMIZED_BOTH)==Frame.MAXIMIZED_BOTH,"maximized state restored"));
            SwingUtilities.invokeAndWait(()->window.setExtendedState(Frame.NORMAL));idle();
            SwingUtilities.invokeAndWait(()->check(window.getBounds().equals(original[0]),"floating bounds retained after unmaximizing"));
            peers("restored from maximized");pixels("restored from maximized");
            ImageIO.write(robot.createScreenCapture(window.getBounds()),"png",new File(directory,"borderless-windowed.png"));
            File log=new File(directory,"data/window-transitions.log");
            check(log.isFile() && log.length()>0 && log.length()<=128*1024,"bounded transition log written");
            check(paintCount>=9,"UI keeps repainting, count="+paintCount);
            System.out.println("WINDOWS_BORDERLESS_SMOKE_PASSED");
        }catch(Throwable error){result=1;error.printStackTrace();}
        finally {SwingUtilities.invokeAndWait(()->{if(fullscreen!=null)fullscreen.close();if(window!=null)window.dispose();});}
        System.exit(result);
    }
}
