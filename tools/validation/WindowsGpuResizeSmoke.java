import com.tvnext.app.core.player.*;
import com.sun.jna.*;
import java.io.File;
import java.lang.reflect.Method;
import javax.swing.*;
import org.jetbrains.skia.BackendRenderTarget;
import org.jetbrains.skiko.*;
import kotlin.Unit;

/** Exercises real ANGLE window-surface resize, video FBOs and the production GL
 * state guard. No physical GPU or full Compose/Skia compositor is claimed.
 * args: native Windows fixture path; optional "unsafe" reproduces the old leak.
 */
public class WindowsGpuResizeSmoke {
    public interface GL extends MpvGpuRenderer.Gles {
        void glReadPixels(int x,int y,int w,int h,int format,int type,Pointer data);
        void glDisable(int capability);
    }
    static boolean unsafe,started;
    static SkiaLayer layer;
    static int texture,fbo;
    static void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);
        System.out.println("PASS "+label);
    }
    static int value(GL gl,int key) {int[] result=new int[1];gl.glGetIntegerv(key,result);return result[0];}
    static GlFramebufferState preserve(GL gl) {return unsafe?null:GlFramebufferState.Companion.capture(gl);}
    static void state(GL gl,int width,int height,String stage) {
        check(value(gl,0x8CA6)==0 && value(gl,0x8CAA)==0,stage+": window draw/read framebuffer restored");
        int[] viewport=new int[4];gl.glGetIntegerv(0x0BA2,viewport);
        check(viewport[0]==0 && viewport[1]==0 && viewport[2]==width && viewport[3]==height,stage+": window viewport restored");
    }
    static void videoTarget(GL gl,int width,int height) {
        try(GlFramebufferState saved=preserve(gl)) {
            if(fbo!=0)gl.glDeleteFramebuffers(1,new int[]{fbo});
            if(texture!=0)gl.glDeleteTextures(1,new int[]{texture});
            int[] id=new int[1];gl.glGenTextures(1,id);texture=id[0];gl.glBindTexture(0x0DE1,texture);
            gl.glTexParameteri(0x0DE1,0x2801,0x2601);gl.glTexParameteri(0x0DE1,0x2800,0x2601);
            gl.glTexImage2D(0x0DE1,0,0x8058,width,height,0,0x1908,0x1401,null);
            gl.glGenFramebuffers(1,id);fbo=id[0];gl.glBindFramebuffer(0x8D40,fbo);
            gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,texture,0);
            check(gl.glCheckFramebufferStatus(0x8D40)==0x8CD5,"video target complete "+width+"x"+height);
            gl.glViewport(0,0,width,height);
        }
    }
    static int pixel(GL gl,int x,int y) {
        try(Memory data=new Memory(4)) {
            gl.glReadPixels(x,y,1,1,0x1908,0x1401,data);
            return (data.getByte(0)&255)<<16 | (data.getByte(1)&255)<<8 | (data.getByte(2)&255);
        }
    }
    static boolean closeColor(int actual,int expected) {
        int distance=0;for(int shift:new int[]{16,8,0})distance+=Math.abs((actual>>shift&255)-(expected>>shift&255));
        return distance<90;
    }
    static double position(Mpv api,Pointer client) {
        Pointer value=api.mpv_get_property_string(client,"time-pos");
        if(value==null)throw new AssertionError("Missing playback position");
        try{return Double.parseDouble(value.getString(0));}finally{api.mpv_free(value);}
    }
    static void ui(GL gl,int round) {
        gl.glDisable(0x0C11);gl.glClearColor(.08f,.65f,.16f,1f);gl.glClear(0x4000);
        // Read the actual native window backbuffer, independently of the draw target.
        gl.glBindFramebuffer(0x8CA8,0);
        check(closeColor(pixel(gl,16,16),0x14a629),"window UI pixels visible after resize "+round);
    }
    static void video(GL gl,int width,int height,int round) {
        try(GlFramebufferState saved=preserve(gl)) {
            gl.glBindFramebuffer(0x8CA8,fbo);
            int[] x={width/4,width*3/4,width/4,width*3/4};
            int[] y={height*3/4,height*3/4,height/4,height/4};
            int[] expected={0xff0000,0x00ff00,0x0000ff,0xffff00};
            for(int i=0;i<4;i++)check(closeColor(pixel(gl,x[i],y[i]),expected[i]),"upright video corner "+i+" after resize "+round);
        }
    }
    static void run(String fixture)throws Exception {
        Object redraw=layer.getRedrawer$skiko();
        Method getDevice=redraw.getClass().getDeclaredMethod("getDevice");getDevice.setAccessible(true);
        long device=(Long)getDevice.invoke(redraw);
        Method activate=Class.forName("org.jetbrains.skiko.redrawer.AngleRedrawerKt").getDeclaredMethod("access$makeCurrent",long.class);
        activate.invoke(null,device);
        Method resize=redraw.getClass().getMethod("makeRenderTarget",int.class,int.class);
        GL gl=Native.load("libGLESv2",GL.class);
        gl.glBindFramebuffer(0x8D40,0);gl.glViewport(0,0,640,360);
        Mpv api=Mpv.Companion.load();Pointer client=api.mpv_create();MpvGlContext render=null;
        try {
            api.mpv_set_option_string(client,"config","no");api.mpv_set_option_string(client,"vo","libmpv");
            api.mpv_set_option_string(client,"hwdec","no");api.mpv_set_option_string(client,"ao","null");
            api.mpv_set_option_string(client,"loop-file","inf");
            check(api.mpv_initialize(client)>=0,"native player initialized");
            try(GlFramebufferState saved=preserve(gl)){render=new MpvGlContext(api,client,()->Unit.INSTANCE);}
            check(api.mpv_command(client,new String[]{"loadfile",new File(fixture).getPath()})>=0,"fixture opened");
            int[][] windows={{640,360},{1280,720},{640,360},{1920,1080},{640,360},{960,540},{640,360}};
            for(int round=0;round<windows.length;round++) {
                int width=windows[round][0],height=windows[round][1];
                int videoWidth=round%2==0?320:640,videoHeight=videoWidth*9/16;
                boolean paused=round==3;
                if(paused){check(api.mpv_set_property_string(client,"pause","yes")>=0,"pause before fullscreen-sized resize");Thread.sleep(150);render.dirty();}
                videoTarget(gl,videoWidth,videoHeight);
                // The native Skiko resize queries this binding for its new window target.
                int inherited=value(gl,0x8CA6);System.out.println("NATIVE_RESIZE "+width+"x"+height+" inherited framebuffer="+inherited);
                try(BackendRenderTarget target=(BackendRenderTarget)resize.invoke(redraw,width,height)) {
                    check(inherited==0,"Skiko native resize selects window framebuffer, actual="+inherited);
                    int frames=0;
                    if(paused) {
                        double before=position(api,client);
                        // Redraw the existing frame into the new target even without FRAME.
                        try(GlFramebufferState saved=preserve(gl)){render.render(fbo,videoWidth,videoHeight);}
                        frames++;
                        Thread.sleep(200);
                        check(Math.abs(position(api,client)-before)<.1,"paused playback clock stays still across resize");
                    }
                    long deadline=System.nanoTime()+(paused?0L:850_000_000L);
                    while(System.nanoTime()<deadline) {
                        api.mpv_wait_event(client,0);
                        if(render.dirty()) {
                            try(GlFramebufferState saved=preserve(gl)){render.render(fbo,videoWidth,videoHeight);}
                            frames++;
                        }
                        Thread.sleep(12);
                    }
                    if(paused)check(frames==1,"paused frame redrawn after resize "+round);
                    else check(frames>2,"video advances after resize "+round+", frames="+frames);
                    state(gl,width,height,"video "+round);
                    try(GlFramebufferState saved=preserve(gl)){render.skip();}
                    state(gl,width,height,"hidden video "+round);
                    video(gl,videoWidth,videoHeight,round);
                    ui(gl,round);
                    state(gl,width,height,"UI "+round);
                    if(paused)check(api.mpv_set_property_string(client,"pause","no")>=0,"resume after fullscreen-sized resize");
                }
            }
            try(GlFramebufferState saved=preserve(gl)){render.close();render=null;gl.glDeleteFramebuffers(1,new int[]{fbo});gl.glDeleteTextures(1,new int[]{texture});}
            state(gl,640,360,"cleanup");ui(gl,windows.length);
            System.out.println("WINDOWS_GPU_RESIZE_SMOKE_PASSED");
        } finally {
            if(render!=null)render.close();api.mpv_terminate_destroy(client);
        }
    }
    public static void main(String[] args)throws Exception {
        unsafe=args.length>1 && args[1].equals("unsafe");WindowsGpuRuntime.INSTANCE.prepare();System.setProperty("skiko.renderApi","ANGLE");
        SwingUtilities.invokeAndWait(()->{
            layer=new SkiaLayer(c->null,false,false,FrameBuffering.DOUBLE,GraphicsApi.ANGLE,new SkiaLayerAnalytics(){},org.jetbrains.skia.PixelGeometry.UNKNOWN);
            layer.setRenderDelegate(new SkiaLayerRenderDelegate(layer,(canvas,w,h,time)->{
                if(started)return;started=true;
                try{run(args[0]);System.exit(0);}catch(Throwable error){error.printStackTrace();System.exit(1);}
            }));
            JFrame host=new JFrame();host.add(layer);host.setSize(800,500);host.setVisible(true);
        });
    }
}
