package cn.qingxing.reminder;

import android.app.*;import android.content.*;import android.location.*;import android.os.*;
import java.util.*;

/** Only explicitly started by the user; no boot/sticky restart. */
public final class MockLocationService extends Service {
    static final String CHANNEL="mock_location_v1",STOP="cn.qingxing.reminder.STOP_MOCK";
    static volatile boolean active;static volatile String error="";
    static volatile double latitude,longitude;
    final Handler handler=new Handler(Looper.getMainLooper());
    final List<String> providers=new ArrayList<>();LocationManager manager;PowerManager.WakeLock wake;
    final Runnable tick=new Runnable(){public void run(){try{inject();handler.postDelayed(this,1000);}catch(RuntimeException e){fail("模拟已停止：授权或定位服务发生变化");}}};
    static boolean allowed(Context c){try{AppOpsManager ops=c.getSystemService(AppOpsManager.class);return ops.checkOpNoThrow("android:mock_location",android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;}catch(RuntimeException e){return false;}}
    static void cleanup(Context c){if(active||!allowed(c))return;LocationManager lm=c.getSystemService(LocationManager.class);for(String name:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})try{lm.removeTestProvider(name);}catch(RuntimeException ignored){}}
    static void stop(Context c){c.stopService(new Intent(c,MockLocationService.class));cleanup(c);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();manager=getSystemService(LocationManager.class);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null||STOP.equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            if(!allowed(this))throw new SecurityException("请在开发者选项中将轻醒选为模拟位置信息应用");
            if(!manager.isLocationEnabled())throw new SecurityException("请先打开手机的位置服务");
            double lat=i.getDoubleExtra("lat",Double.NaN),lon=i.getDoubleExtra("lon",Double.NaN);
            if(!Coordinates.valid(lat,lon))throw new IllegalArgumentException("请选择有效位置");
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(!nm.areNotificationsEnabled())throw new SecurityException("请先允许轻醒通知，以便随时停止模拟");
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"虚拟位置运行状态",NotificationManager.IMPORTANCE_LOW));
            if(nm.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE)throw new SecurityException("请允许虚拟位置运行状态通知");
            PendingIntent stop=PendingIntent.getService(this,601,new Intent(this,MockLocationService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open=PendingIntent.getActivity(this,602,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("locationPage",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            startForeground(601,new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("虚拟位置正在运行").setContentText("其他应用可能读取此位置；点击停止可结束模拟").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"停止模拟",stop).build()).build());
            latitude=lat;longitude=lon;error="";
            if(providers.isEmpty())for(String name:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
                manager.addTestProvider(name,false,false,false,false,true,true,true,android.location.Criteria.POWER_LOW,android.location.Criteria.ACCURACY_FINE);providers.add(name);manager.setTestProviderEnabled(name,true);
            }
            if(wake==null){wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"qingxing:mock");wake.acquire();}
            active=true;handler.removeCallbacks(tick);tick.run();
            new Store(this).debug("虚拟位置","已开始模拟","系统坐标 WGS84；可在通知中停止",false);
        }catch(RuntimeException e){fail(e.getMessage()==null?"无法开始模拟":e.getMessage());}
        return START_NOT_STICKY;
    }
    void inject(){for(String name:providers){Location l=new Location(name);l.setLatitude(latitude);l.setLongitude(longitude);l.setAccuracy(3);l.setAltitude(0);l.setSpeed(0);l.setBearing(0);l.setTime(System.currentTimeMillis());l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());manager.setTestProviderLocation(name,l);}}
    void fail(String message){error=message;new Store(this).debug("虚拟位置","模拟已停止",message,true);stopSelf();}
    @Override public void onTaskRemoved(Intent rootIntent){
        stopSimulation();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    void stopSimulation(){
        handler.removeCallbacksAndMessages(null);
        for(String name:providers)try{manager.removeTestProvider(name);}catch(RuntimeException ignored){}
        providers.clear();
        if(wake!=null&&wake.isHeld())wake.release();
        wake=null;
        boolean was=active;
        active=false;
        stopForeground(true);
        if(was)new Store(this).debug("虚拟位置","已停止模拟","系统将重新获取真实位置",false);
    }
    @Override public void onDestroy(){stopSimulation();super.onDestroy();}
}
