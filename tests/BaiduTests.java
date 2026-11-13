package cn.qingxing.reminder;
import android.app.*;import android.content.*;import android.content.pm.*;import android.location.*;import android.os.*;import android.widget.*;import android.service.notification.StatusBarNotification;import org.json.*;import java.util.*;

/** Actual native map/auth, on-demand location, and Android test-provider lifecycle. */
public final class BaiduTests extends Instrumentation {
 Bundle args;int n;Context c;MainActivity a;
 void check(boolean b,String m){n++;if(!b)throw new AssertionError(m);}
 void main(Runnable action){Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){failure[0]=t;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
 void shell(String command)throws Exception{try(ParcelFileDescriptor p=getUiAutomation().executeShellCommand(command);java.io.InputStream s=new ParcelFileDescriptor.AutoCloseInputStream(p)){byte[] b=new byte[1024];while(s.read(b)!=-1){}}}
 void waitFor(java.util.function.BooleanSupplier condition,long ms,String message)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);check(condition.getAsBoolean(),message);}
 @Override public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 @Override public void onStart(){Bundle out=new Bundle();c=getTargetContext();String scope=args.getString("scope");try{
 main(()->{Store s=new Store(c);s.enabled(false);c.stopService(new Intent(c,MonitorService.class));MockLocationService.stop(c);s.prefs.edit().putBoolean("firstGuidePending",false).putBoolean("locationPrivacy",false).commit();Planner.reset(c);});
 a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
 main(()->{check(!a.locationUnlocked,"location hidden on fresh launch");a.page=3;a.render();android.view.View secret=a.root.findViewWithTag("version_unlock");for(int i=0;i<10;i++)secret.performClick();check(a.locationUnlocked,"ten blank clicks unlock actual location tools");a.page=4;a.render();check(a.root.findViewWithTag("location_current")!=null&&a.root.findViewWithTag("location_virtual")!=null,"two location modes visible");check(a.locationPage.map==null,"map does not initialize without user consent");check(!MockLocationService.active,"page opening never starts mock location");});
 if("map".equals(scope))mapChecks();else if("location".equals(scope))locationChecks();else mockChecks();
 out.putString("stream","PASS "+n+" Baidu tool checks: scope="+scope+"; map/location/mock isolated from attendance");finish(-1,out);
 }catch(Throwable t){out.putString("stream","FAIL "+android.util.Log.getStackTraceString(t));finish(0,out);}finally{try{main(()->{MockLocationService.stop(c);if(a!=null){a.locationPage.close();a.finish();}Store s=new Store(c);s.enabled(false);s.prefs.edit().putBoolean("locationPrivacy",false).commit();c.stopService(new Intent(c,MonitorService.class));Planner.reset(c);});shell("appops set cn.qingxing.reminder android:mock_location deny");}catch(Exception ignored){}}}
 void dialog(String label)throws Exception{waitForIdleSync();long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText())){check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"dialog click "+label);waitForIdleSync();return;}Thread.sleep(150);}throw new AssertionError("dialog missing "+label);}

 void accept()throws Exception{main(()->a.locationPage.consent(a::render));dialog("同意");main(()->check(a.locationPage.map!=null,"native Baidu map created after actual consent"));}
 void mapChecks()throws Exception{
 main(()->a.locationPage.consent(a::render));dialog("取消");check(!a.store.prefs.getBoolean("locationPrivacy",false)&&!LocationPage.initialized,"cancel does not initialize SDK or consent");
 accept();waitFor(()->LocationPage.authCode==0,30000,"real Android AK authentication succeeds; code="+LocationPage.authCode);waitFor(()->LocationPage.mapLoaded,20000,"native map loaded callback");
 main(()->{check(a.locationPage.view.getWidth()>0&&a.locationPage.view.getHeight()>=a.dp(280),"small map has actual layout bounds");a.locationPage.virtual=true;a.render();a.locationPage.select(23.129,113.264);check(a.root.findViewWithTag("mock_start")!=null&&a.root.findViewWithTag("mock_stop")!=null,"explicit start/stop controls");check(a.root.findViewWithTag("mock_authorize")!=null,"developer options entry");check(!MockLocationService.active,"drag/select does not silently start simulation");});
 int[] bounds=new int[4];main(()->{a.locationPage.view.getLocationOnScreen(bounds);bounds[2]=a.locationPage.view.getWidth();bounds[3]=a.locationPage.view.getHeight();});
 shell("input swipe "+(bounds[0]+bounds[2]/2)+" "+(bounds[1]+bounds[3]/2)+" "+(bounds[0]+bounds[2]/2+120)+" "+(bounds[1]+bounds[3]/2+60)+" 600");Thread.sleep(1600);
 main(()->{check(Math.abs(a.locationPage.lat-23.129)>.00001,"touch drag updates selected map point");a.page=3;a.render();check(a.locationPage.map==null&&!a.locationPage.loading,"navigation destroys map and cancels location");});
 }
 void locationChecks()throws Exception{
 accept();main(()->a.locationPage.current());waitFor(()->!a.locationPage.loading,25000,"single current-location request finishes");
 main(()->{check(a.locationPage.message.contains("精度约"),"real Baidu fix returned: "+a.locationPage.message);check(Coordinates.valid(a.locationPage.lat,a.locationPage.lon),"fix coordinates valid");check(a.locationPage.map.getLocationData()!=null,"location marker rendered on map");check(a.locationPage.client==null,"client stopped after success");check(!MockLocationService.active,"current location keeps simulation stopped");
 a.locationPage.current();a.page=0;a.render();check(!a.locationPage.loading&&a.locationPage.client==null,"leaving page cancels delayed request");});Thread.sleep(800);check(a.locationPage.client==null,"late scheduled request cannot run after exit");
 }
 void mockChecks()throws Exception{
 double[][] points={{39.908823,116.397470},{23.129,113.264},{31.2304,121.4737},{0,0},{48.8566,2.3522}};
 for(double[] p:points){double[] b=Coordinates.wgsToBd(p[0],p[1]),w=Coordinates.bdToWgs(b[0],b[1]);check(Math.abs(w[0]-p[0])<.000003&&Math.abs(w[1]-p[1])<.000003,"BD09/WGS84 round trip within a metre");}
 check(!Coordinates.valid(Double.NaN,0)&&!Coordinates.valid(91,0)&&!Coordinates.valid(0,181),"invalid input rejected");
 LocationManager lm=c.getSystemService(LocationManager.class);
 shell("appops set cn.qingxing.reminder android:mock_location allow");
 main(()->MockLocationService.cleanup(c));
 Set<String> originalProviders=new HashSet<>(lm.getAllProviders());
 accept();main(()->{a.locationPage.virtual=true;a.render();double[] b=Coordinates.wgsToBd(23.129,113.264);a.locationPage.select(b[0],b[1]);a.locationPage.startMock();});waitFor(()->MockLocationService.active,4000,"mock foreground service starts with authorization");
 Thread.sleep(1200);
 for(String provider:new String[]{"gps","network"}){Location l=lm.getLastKnownLocation(provider);check(l!=null&&l.isFromMockProvider(),"system exposes mock flag: "+provider);check(Math.abs(l.getLatitude()-23.129)<.000003&&Math.abs(l.getLongitude()-113.264)<.000003,"system receives converted WGS84 coordinates: "+provider);}
 main(()->a.moveTaskToBack(true));long before=lm.getLastKnownLocation("gps").getElapsedRealtimeNanos();Thread.sleep(2200);check(MockLocationService.active&&lm.getLastKnownLocation("gps").getElapsedRealtimeNanos()>before,"injection continues while another app is foreground");
 main(()->{a.locationUnlocked=false;a.onNewIntent(new Intent(c,MainActivity.class).putExtra("locationPage",true));check(!a.locationUnlocked&&a.page==3,"notification content entry does not reveal hidden tab");check(MockLocationService.active,"hiding the entry does not silently stop an active simulation");});
 Notification notification=null;for(StatusBarNotification item:c.getSystemService(NotificationManager.class).getActiveNotifications())if(item.getId()==601)notification=item.getNotification();check(notification!=null&&(notification.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0,"visible foreground status notification");check(notification.actions!=null&&notification.actions.length==1,"one stop action");notification.actions[0].actionIntent.send();waitFor(()->!MockLocationService.active,3000,"notification stop ends injection");check(lm.getProvider("gps")!=null,"original GPS provider restored");
 main(()->{lm.addTestProvider("gps",false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);lm.setTestProviderEnabled("gps",true);MockLocationService.cleanup(c);check(lm.getProvider("gps")!=null&&!MockLocationService.active,"startup cleanup recovers an interrupted provider without auto-start");});
 exitChecks(lm,originalProviders);
 main(()->a.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));Thread.sleep(700);main(()->c.startForegroundService(new Intent(c,MockLocationService.class).putExtra("lat",22.5).putExtra("lon",114.0)));waitFor(()->MockLocationService.active,3000,"restart requires explicit user action");shell("appops set cn.qingxing.reminder android:mock_location deny");waitFor(()->!MockLocationService.active,3500,"revoked mock permission automatically stops service");check(!MockLocationService.error.isEmpty(),"permission failure is visible");
 main(()->{a.page=0;a.render();check(!MockLocationService.active,"navigation never restarts stopped simulation");check(!a.store.enabled()&&a.store.round().length()==0,"location tool does not start attendance");});
 }
 void exitChecks(LocationManager manager,Set<String> originalProviders)throws Exception{
     startExitSimulation();
     ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
     main(a::recreate);
     MainActivity rebuilt=(MainActivity)waitForMonitorWithTimeout(monitor,5000);
     removeMonitor(monitor);
     check(rebuilt!=null,"configuration recreation completes during simulation");
     a=rebuilt;
     waitForIdleSync();
     check(MockLocationService.active,"configuration recreation keeps simulation running");

     shell("input keyevent KEYCODE_HOME");
     Thread.sleep(1200);
     long before=manager.getLastKnownLocation("gps").getElapsedRealtimeNanos();
     try{
         shell("input keyevent KEYCODE_SLEEP");
         Thread.sleep(2200);
         check(MockLocationService.active&&manager.getLastKnownLocation("gps").getElapsedRealtimeNanos()>before,
             "Home and screen off keep injecting mock coordinates");
     }finally{
         shell("input keyevent KEYCODE_WAKEUP");
         shell("wm dismiss-keyguard");
     }
     main(()->a.startActivity(new Intent(c,MainActivity.class)
         .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
     waitForIdleSync();
     shell("input keyevent KEYCODE_BACK");
     waitFor(()->!MockLocationService.active,4000,"system Back stops simulation");
     assertSimulationStopped(manager,originalProviders,"Back exit");

     startExitSimulation();
     main(()->{
         ActivityManager activityManager=c.getSystemService(ActivityManager.class);
         java.util.List<ActivityManager.AppTask> tasks=activityManager.getAppTasks();
         check(!tasks.isEmpty(),"a real app task exists for removal");
         for(ActivityManager.AppTask task:tasks)task.finishAndRemoveTask();
     });
     waitFor(()->!MockLocationService.active,4000,"removing app task stops simulation");
     assertSimulationStopped(manager,originalProviders,"task removal");

     a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class)
         .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
     waitForIdleSync();
     Thread.sleep(1200);
     check(!MockLocationService.active,"reopening after exit does not restart simulation");
 }
 void startExitSimulation()throws Exception{
     a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class)
         .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
     waitForIdleSync();
     main(()->c.startForegroundService(new Intent(c,MockLocationService.class)
         .putExtra("lat",23.129).putExtra("lon",113.264)));
     waitFor(()->MockLocationService.active,3000,"explicit simulation starts before exit check");
 }
 void assertSimulationStopped(LocationManager manager,Set<String> originalProviders,String reason)throws Exception{
     Thread.sleep(1200);
     check(!MockLocationService.active,reason+" remains stopped after a tick interval");
     for(String provider:new String[]{"gps","network"}){
         boolean present=manager.getAllProviders().contains(provider);
         check(present==originalProviders.contains(provider),reason+" restores original provider state "+provider);
         Location location=present?manager.getLastKnownLocation(provider):null;
         check(location==null||!location.isFromMockProvider(),reason+" clears mock result "+provider);
     }
     for(StatusBarNotification item:c.getSystemService(NotificationManager.class).getActiveNotifications()){
         check(item.getId()!=601,reason+" clears the mock notification");
     }
 }
}
