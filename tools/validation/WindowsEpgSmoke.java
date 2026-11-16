import com.tvnext.app.MainKt;
import com.tvnext.app.core.database.DatabaseFactory;
import com.tvnext.app.core.iptv.data.*;
import com.tvnext.app.core.iptv.domain.Channel;
import com.tvnext.app.core.common.result.AppResult;
import kotlin.coroutines.*;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.*;
import org.koin.core.Koin;
import kotlin.jvm.JvmClassMappingKt;
import java.sql.*;
import java.util.*;
public class WindowsEpgSmoke {
 interface Suspended {Object call(Continuation continuation);}
 static Object blocking(Suspended call)throws Exception {
  return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,new Function2<CoroutineScope,Continuation<? super Object>,Object>() {
   public Object invoke(CoroutineScope scope,Continuation<? super Object> continuation){return call.call(continuation);}
  });
 }
 static <T>T get(Koin k,Class<T> type){return k.get(JvmClassMappingKt.getKotlinClass(type),null,null);}
 public static void main(String[] args)throws Exception {
  System.setProperty("java.awt.headless","true");Objects.requireNonNull(System.getProperty("tvnext.data.dir"),"Use an isolated QA data directory");new DatabaseFactory().createDriver().close();
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+System.getProperty("tvnext.data.dir")+"/tvnext.db")) {
   try(PreparedStatement p=c.prepareStatement("INSERT OR REPLACE INTO playlist(id,name,url,enabled,url_tvg,type,is_default) VALUES('native','Native EPG','http://fixture.invalid/list',1,?,'LIVE',1)")){p.setString(1,args[0]);p.executeUpdate();}
   c.createStatement().execute("INSERT OR REPLACE INTO channel(id,playlist_id,number,name,stream_url,category,tvg_id) VALUES('native-1','native',1,'Native channel','http://fixture.invalid/live','Test','native')");
  }
  Koin k=MainKt.startDesktopDependencies();
  EpgRepository first=get(k,EpgRepository.class);Object result=blocking(c->first.refreshIfStale(args.length<2,c));
  if(!(result instanceof AppResult.Success))throw new AssertionError(result);
  ChannelsRepository channels=get(k,ChannelsRepository.class);
  java.util.List<Channel> roster=(java.util.List<Channel>)blocking(channels::getChannels);
  Channel channel=roster.get(0);long now=System.currentTimeMillis();
  NowNext a=(NowNext)blocking(c->first.getNowNext(channel,now,c));
  if(a.getCurrent()==null||!a.getCurrent().getTitle().equals("Native compressed EPG"))throw new AssertionError(a);
  EpgRepository reopened=new DesktopEpgRepository(get(k,io.ktor.client.HttpClient.class),get(k,PlaylistRepository.class),channels,get(k,EpgConfigProvider.class));
  result=blocking(c->reopened.refreshIfStale(false,c));if(!(result instanceof AppResult.Success))throw new AssertionError(result);
  NowNext b=(NowNext)blocking(c->reopened.getNowNext(channel,now,c));
  if(b.getCurrent()==null||!b.getCurrent().getTitle().equals("Native compressed EPG"))throw new AssertionError(b);
  System.out.println("WINDOWS_GZIP_EPG_PERSISTENCE_PASSED "+first.observeState().getValue().getProgrammeCount());System.exit(0);
 }
}
