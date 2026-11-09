package com.tvnext.probe;
import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import java.lang.reflect.*;
import java.io.*;

public class ReleaseProbe extends Instrumentation {
 private volatile Activity activity;
 private final StringBuilder report=new StringBuilder();
 private Object beforePlayer,beforeView;
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){try{
  Application app=(Application)getTargetContext().getApplicationContext();
  app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
   public void onActivityResumed(Activity a){if(a.getClass().getName().endsWith("MainActivity"))activity=a;}
   public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
   public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}
   public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
  });
  open();for(int i=0;activity==null&&i<300;i++)SystemClock.sleep(100);
  check(activity!=null,"Release Activity opened");
  awaitPlaying();main(()->{beforePlayer=player();beforeView=view(activity.getWindow().getDecorView());});
  check(beforeView!=null,"Native video view present");
  Object[] surface=new Object[1];main(()->surface[0]=call(beforeView,"getVideoSurfaceView"));
  check(surface[0] instanceof TextureView,"Animation remains enabled with TextureView");
  for(String tab:new String[]{"HOME","TVG","CHANNELS"}){
   main(()->select(tab));SystemClock.sleep(2500);
   main(()->{check(player()==beforePlayer,"Same player during "+tab);check(view(activity.getWindow().getDecorView())==beforeView,"Same native surface during "+tab);});
  }
  for(int cycle=1;cycle<=2;cycle++){
   shell("input keyevent 223");SystemClock.sleep(1500);
   main(()->check(player()==null,"Screen-off released decoder"));
   shell("input keyevent 224");SystemClock.sleep(500);open();awaitPlaying();SystemClock.sleep(1500);
   main(()->{
    Object next=player(),nativeView=view(activity.getWindow().getDecorView());
    check(next!=null&&next!=beforePlayer,"Wake creates new decoder session");
    check(nativeView!=null&&nativeView!=beforeView,"Wake creates fresh native surface");
    beforePlayer=next;beforeView=nativeView;
   });
  }
  main(()->{try{android.graphics.Bitmap b=getUiAutomation().takeScreenshot();FileOutputStream f=new FileOutputStream(new File(getTargetContext().getCacheDir(),"apk-wake-101.png"));b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);f.close();}catch(Exception e){throw new RuntimeException(e);}});
 }catch(Throwable e){report.append("ERROR "+e+"\n");}finally{Bundle b=new Bundle();b.putString("report",report.toString());finish(report.indexOf("ERROR")<0?Activity.RESULT_OK:Activity.RESULT_CANCELED,b);}}
 private void open(){getTargetContext().startActivity(new Intent().setClassName("com.tvnext.app","com.tvnext.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
 private Object call(Object o,String name){try{Method m=o.getClass().getMethod(name);m.setAccessible(true);return m.invoke(o);}catch(Exception e){throw new RuntimeException(e);}}
 private Object player(){try{Field f=activity.getClass().getDeclaredField("playbackViewModel");f.setAccessible(true);Object vm=f.get(activity);Field c=vm.getClass().getDeclaredField("controller");c.setAccessible(true);Object controller=c.get(vm);for(Method m:controller.getClass().getMethods())if(m.getName().startsWith("getPlayer")){m.setAccessible(true);return call(m.invoke(controller),"getValue");}throw new Exception("No player flow");}catch(Exception e){throw new RuntimeException(e);}}
 private Object view(View v){if(v.getClass().getName().equals("androidx.media3.ui.PlayerView"))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Object x=view(g.getChildAt(i));if(x!=null)return x;}}return null;}
 private void select(String name){try{Field f=activity.getClass().getDeclaredField("appComponent");f.setAccessible(true);Object root=f.get(activity);Class c=Class.forName("com.tvnext.app.core.app.tab.AppTab");Method m=root.getClass().getMethod("selectTab",c);m.setAccessible(true);m.invoke(root,Enum.valueOf(c,name));}catch(Exception e){throw new RuntimeException(e);}}
 private void awaitPlaying(){boolean[] ok={false};for(int i=0;i<600;i++){main(()->{Object p=player();ok[0]=p!=null&&(Boolean)call(p,"isPlaying");});if(ok[0]){check(true,"Video playing");return;}SystemClock.sleep(100);}check(false,"Video playing within 60 s");}
 private void main(Runnable r){runOnMainSync(r);}
 private void check(boolean ok,String label){report.append((ok?"PASS ":"FAIL ")+label+"\n");if(!ok)throw new AssertionError(label);}
 private void shell(String command){try{ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);FileInputStream in=new FileInputStream(fd.getFileDescriptor());byte[] b=new byte[1024];while(in.read(b)>0){}in.close();fd.close();}catch(Exception e){throw new RuntimeException(e);}}
}
