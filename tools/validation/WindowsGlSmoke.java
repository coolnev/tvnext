import org.jetbrains.skiko.*;
import com.tvnext.app.core.player.*;
import com.sun.jna.*;
import java.lang.reflect.*;
import java.io.*;
import javax.swing.*;
import kotlin.Unit;
/** Low-level Windows ANGLE/libmpv check. Not a physical GPU or Skia compositor test.
 * args: native fixture path, optional PNG output directory. Decode is forced to software.
 */
public class WindowsGlSmoke {
 static File outputDir;
 public interface GL extends MpvGpuRenderer.Gles {void glReadPixels(int x,int y,int w,int h,int format,int type,Pointer data);Pointer glGetString(int key);}
 static SkiaLayer layer;static boolean started;
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 static void run(String fixture)throws Exception {
  Object redraw=layer.getRedrawer$skiko();Method deviceMethod=redraw.getClass().getDeclaredMethod("getDevice");deviceMethod.setAccessible(true);
  long device=(Long)deviceMethod.invoke(redraw);
  Method activate=Class.forName("org.jetbrains.skiko.redrawer.AngleRedrawerKt").getDeclaredMethod("access$makeCurrent",long.class);activate.invoke(null,device);
  GL gl=Native.load("libGLESv2",GL.class);System.out.println("GL "+gl.glGetString(0x1F02).getString(0));
  int[] names=new int[1];gl.glGenTextures(1,names);int texture=names[0];gl.glBindTexture(0x0DE1,texture);
  gl.glTexParameteri(0x0DE1,0x2801,0x2601);gl.glTexParameteri(0x0DE1,0x2800,0x2601);
  gl.glTexImage2D(0x0DE1,0,0x8058,640,360,0,0x1908,0x1401,null);
  gl.glGenFramebuffers(1,names);int fbo=names[0];gl.glBindFramebuffer(0x8D40,fbo);
  gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,texture,0);
  check(gl.glCheckFramebufferStatus(0x8D40)==0x8CD5,"GPU framebuffer complete");
  Mpv api=Mpv.Companion.load();Pointer client=api.mpv_create();
  api.mpv_set_option_string(client,"config","no");api.mpv_set_option_string(client,"vo","libmpv");
  api.mpv_set_option_string(client,"hwdec","no");api.mpv_set_option_string(client,"ao","null");
  api.mpv_set_option_string(client,"keep-open","yes");api.mpv_request_log_messages(client,"warn");
  check(api.mpv_initialize(client)>=0,"mpv initialized");
  MpvGlContext render=new MpvGlContext(api,client,()->Unit.INSTANCE);
  System.out.println("LOAD "+new File(fixture).exists()+" rc="+api.mpv_command(client,new String[]{"loadfile",fixture}));
  for(int round=0;round<2;round++) {
   int frames=0;long deadline=System.nanoTime()+4_000_000_000L;
   while(System.nanoTime()<deadline){
    Pointer event=api.mpv_wait_event(client,0);
    if(event!=null){int id=event.getInt(0);Pointer data=event.getPointer(16);if(id==2 && data!=null)System.out.println("MPV "+data.getPointer(0).getString(0)+" "+data.getPointer(16).getString(0));else if(id!=0)System.out.println("EVENT "+id+" err="+event.getInt(4));}
    if(render.dirty()){render.render(fbo,640,360);frames++;}Thread.sleep(20);
   }
   Pointer pos=api.mpv_get_property_string(client,"time-pos");System.out.println("POSITION "+(pos==null?null:pos.getString(0)));if(pos!=null)api.mpv_free(pos);
   check(frames>5,"GPU frames round "+round+" count="+frames);
   gl.glBindFramebuffer(0x8D40,fbo);
   try(Memory pixels=new Memory(640*360*4)){
    gl.glReadPixels(0,0,640,360,0x1908,0x1401,pixels);
    byte[] data=pixels.getByteArray(0,640*360*4);boolean colored=false;
    for(int i=0;i<data.length;i+=4*100)if((data[i]&255)>30 || (data[i+1]&255)>30 || (data[i+2]&255)>30)colored=true;
    check(colored,"video pixels produced by GPU");
    java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(640,360,java.awt.image.BufferedImage.TYPE_INT_RGB);
    for(int y=0;y<360;y++)for(int x=0;x<640;x++){int i=((359-y)*640+x)*4;image.setRGB(x,y,((data[i]&255)<<16)|((data[i+1]&255)<<8)|(data[i+2]&255));}
    javax.imageio.ImageIO.write(image,"png",new File(outputDir,"gl-frame-"+round+".png"));
   }
   render.close();
   if(round==0){render=new MpvGlContext(api,client,()->Unit.INSTANCE);System.out.println("RESET VID "+api.mpv_set_property_string(client,"vid","no")+" / "+api.mpv_set_property_string(client,"vid","auto"));}
  }
  api.mpv_terminate_destroy(client);System.out.println("WINDOWS_GL_SMOKE_PASSED");
 }
 public static void main(String[] args)throws Exception {
  outputDir=new File(args.length>1?args[1]:".");outputDir.mkdirs();
  WindowsGpuRuntime.INSTANCE.prepare();System.setProperty("skiko.renderApi","ANGLE");
  SwingUtilities.invokeAndWait(()->{
   layer=new SkiaLayer(c->null,false,false,FrameBuffering.DOUBLE,GraphicsApi.ANGLE,new SkiaLayerAnalytics(){},org.jetbrains.skia.PixelGeometry.UNKNOWN);
   layer.setRenderDelegate(new SkiaLayerRenderDelegate(layer,(canvas,w,h,time)->{if(started)return;started=true;try {run(args[0]);System.exit(0);}catch(Throwable error){error.printStackTrace();System.exit(1);}}));
   JFrame frame=new JFrame();frame.add(layer);frame.setSize(800,500);frame.setVisible(true);
  });
 }
}
