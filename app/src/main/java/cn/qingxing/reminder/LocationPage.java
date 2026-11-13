package cn.qingxing.reminder;

import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.graphics.Color;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;
import com.baidu.location.*;import com.baidu.mapapi.*;import com.baidu.mapapi.map.*;import com.baidu.mapapi.model.LatLng;
import java.util.*;

/** An on-demand location tool, independent of the Wi-Fi attendance planner. */
final class LocationPage {
    final MainActivity a;final Handler handler=new Handler(Looper.getMainLooper());
    MapView view;BaiduMap map;LocationClient client;TextView status,pointText,mapText;Button start,stop;
    boolean virtual,loading,visible;int generation;long requestedAt;
    double lat=Double.NaN,lon=Double.NaN;String lastFixDetail="";String message="点击“当前位置”获取位置，或选择“虚拟位置”手动选点";
    String mapMessage="地图尚未加载";static boolean initialized;static volatile int authCode=-999;
    static volatile boolean mapLoaded;BroadcastReceiver authReceiver;
    LocationPage(MainActivity a){this.a=a;virtual=MockLocationService.active;}
    void render(){
        visible=true;LinearLayout card=a.card();LinearLayout row=a.row(card);
        a.action(row,"当前位置",()->consent(()->current()),!virtual).setTag("location_current");
        a.action(row,"虚拟位置",()->consent(()->{virtual=true;message="拖动地图，将十字中心移到测试位置，再点击开始模拟";a.render();}),virtual).setTag("location_virtual");
        status=a.text(message,13,MainActivity.INK,false);status.setTag("location_status");card.addView(status);
        pointText=a.text("",12,MainActivity.MUTED,false);pointText.setTag("location_coordinates");card.addView(pointText);
        FrameLayout frame=new FrameLayout(a){@Override public boolean dispatchTouchEvent(MotionEvent e){getParent().requestDisallowInterceptTouchEvent(e.getAction()!=MotionEvent.ACTION_UP&&e.getAction()!=MotionEvent.ACTION_CANCEL);return super.dispatchTouchEvent(e);}};frame.setTag("location_map_container");card.addView(frame,new LinearLayout.LayoutParams(-1,a.dp(290)));
        if(a.store.prefs.getBoolean("locationPrivacy",false)){
            try{init();view=new MapView(a);map=view.getMap();frame.addView(view,new FrameLayout.LayoutParams(-1,-1));view.showZoomControls(true);map.getUiSettings().setRotateGesturesEnabled(false);map.getUiSettings().setOverlookingGesturesEnabled(false);
                if(!Coordinates.valid(lat,lon)){lat=Double.parseDouble(a.store.prefs.getString("toolLat","39.915"));lon=Double.parseDouble(a.store.prefs.getString("toolLon","116.404"));}
                map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(new LatLng(lat,lon),16));
                map.setOnMapLoadedCallback(()->{mapLoaded=true;mapMessage=authCode==0?"百度地图已加载":"地图已加载，正在检查鉴权";update();});
                map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){public void onMapStatusChangeStart(MapStatus s){}public void onMapStatusChangeStart(MapStatus s,int reason){}public void onMapStatusChange(MapStatus s){}public void onMapStatusChangeFinish(MapStatus s){if(virtual&&visible){select(s.target.latitude,s.target.longitude);}}});
                frame.setOnTouchListener((v,event)->false);
                view.setOnTouchListener((v,event)->{v.getParent().requestDisallowInterceptTouchEvent(event.getAction()!=MotionEvent.ACTION_UP&&event.getAction()!=MotionEvent.ACTION_CANCEL);return false;});
                if(virtual){TextView cross=a.text("⊕",30,MainActivity.GREEN,true);cross.setGravity(Gravity.CENTER);cross.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(a.dp(44),a.dp(44),Gravity.CENTER);frame.addView(cross,p);}
                view.onResume();
            }catch(RuntimeException|LinkageError e){mapMessage="地图初始化失败，请重新打开定位页";a.store.debug("定位工具","地图初始化失败",e.getClass().getSimpleName(),true);frame.addView(a.text(mapMessage,14,MainActivity.MUTED,false));}
        }else{TextView placeholder=a.text("使用百度地图前，请点击上方按钮阅读并同意定位服务说明",14,MainActivity.MUTED,false);placeholder.setGravity(Gravity.CENTER);frame.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));}
        mapText=a.text(mapMessage,11,MainActivity.MUTED,false);card.addView(mapText);
        if(virtual){LinearLayout actions=a.row(card);start=a.action(actions,"开始模拟",this::startMock,true);start.setTag("mock_start");stop=a.action(actions,"停止模拟",()->{MockLocationService.stop(a);message="已停止模拟，等待系统刷新真实位置";handler.postDelayed(this::update,200);update();},false);stop.setTag("mock_stop");
            a.action(card,"模拟位置授权",()->a.open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),false).setTag("mock_authorize");
            a.heading(card,"使用说明","在系统开发者选项中，打开“选择模拟位置信息应用”，选择轻醒。按返回键退出或从最近任务划掉轻醒会停止模拟；Home、切换应用和锁屏时继续运行，也可在通知中停止。应用可能拒绝模拟位置或采用其他定位来源。");
        }
        update();handler.post(poll);
    }
    void consent(Runnable after){if(a.store.prefs.getBoolean("locationPrivacy",false)){after.run();return;}new AlertDialog.Builder(a).setTitle("百度地图与定位服务").setMessage("本功能会按需使用百度地图及定位 SDK，向百度发送获取位置和地图所需的位置信息、设备及网络信息。打卡监控仍只使用公司 Wi-Fi。是否同意使用？").setPositiveButton("同意",(d,w)->{a.store.prefs.edit().putBoolean("locationPrivacy",true).commit();a.handler.post(after);}).setNeutralButton("隐私说明",(d,w)->a.open(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://lbs.baidu.com/index.php?title=openprivacy")))).setNegativeButton("取消",null).show();}
    void init(){if(initialized)return;SDKInitializer.setAgreePrivacy(a.getApplicationContext(),true);LocationClient.setAgreePrivacy(true);SDKInitializer.addAuthResultListener((code,ignored)->authCode=code);authReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_OK.equals(i.getAction())){authCode=0;mapMessage="百度地图鉴权成功";}else{authCode=i.getIntExtra(SDKInitializer.SDK_BROADTCAST_INTENT_EXTRA_INFO_KEY_ERROR_CODE,-1);mapMessage="百度地图鉴权失败（"+authCode+"），请检查 Android AK 与服务权限";}update();}};
        IntentFilter filter=new IntentFilter();filter.addAction(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_OK);filter.addAction(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_ERROR);
        if(Build.VERSION.SDK_INT>=33)a.registerReceiver(authReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else a.registerReceiver(authReceiver,filter);
        SDKInitializer.initialize(a.getApplicationContext());initialized=true;
    }
    final Runnable poll=new Runnable(){public void run(){if(!visible)return;update();handler.postDelayed(this,1000);}};
    void update(){if(!visible||status==null)return;status.setText(MockLocationService.active&&virtual?"虚拟位置正在运行，切换应用后仍会继续":!MockLocationService.error.isEmpty()&&virtual?MockLocationService.error:message);
        if(pointText!=null)pointText.setText(Coordinates.valid(lat,lon)?String.format(Locale.CHINA,"%s · %.6f，%.6f（地图坐标）",virtual?"选中位置":"定位参考点",lat,lon):"");
        if(mapText!=null)mapText.setText(authCode>0?"百度地图鉴权失败（"+authCode+"）":mapMessage);
        if(start!=null)start.setText(MockLocationService.active?"应用新位置":"开始模拟");if(stop!=null)stop.setEnabled(MockLocationService.active);
    }
    void select(double latitude,double longitude){if(!Coordinates.valid(latitude,longitude))return;lat=latitude;lon=longitude;a.store.prefs.edit().putString("toolLat",Double.toString(lat)).putString("toolLon",Double.toString(lon)).apply();update();}
    void startMock(){
        if(map==null||!Coordinates.valid(lat,lon)){message="请先加载地图并选择位置";update();return;}
        if(!a.getSystemService(android.location.LocationManager.class).isLocationEnabled()){message="请先打开手机的位置服务，再开始模拟";update();a.open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return;}
        if(!MockLocationService.allowed(a)){message="请在开发者选项中，将轻醒选为模拟位置信息应用";update();a.open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));return;}
        try{double[] wgs=Coordinates.bdToWgs(lat,lon);MockLocationService.error="";a.startForegroundService(new Intent(a,MockLocationService.class).putExtra("lat",wgs[0]).putExtra("lon",wgs[1]));message="正在应用虚拟位置…";handler.postDelayed(this::update,500);}catch(RuntimeException e){message="无法开始模拟，请检查授权和通知设置";update();}
    }
    void current(){
        MockLocationService.stop(a);MockLocationService.error="";virtual=false;message="正在获取当前位置…";a.render();
        if(a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){message="请允许精确位置权限，然后再次点击当前位置";update();a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},61);return;}
        if(!a.getSystemService(android.location.LocationManager.class).isLocationEnabled()){message="请先打开手机的位置服务，再点击当前位置";update();a.open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return;}
        // Allow provider removal to finish before asking for a new, non-cached result.
        int token=++generation;loading=true;handler.postDelayed(()->requestCurrent(token),500);
    }
    void requestCurrent(int token){if(!visible||token!=generation)return;try{
        init();requestedAt=System.currentTimeMillis();lastFixDetail="";client=new LocationClient(a.getApplicationContext());LocationClientOption option=new LocationClientOption();option.setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy);option.setCoorType("bd09ll");option.setOpenGnss(true);option.setScanSpan(1000);option.setTimeOut(15000);option.setIsNeedAddress(true);option.setEnableSimulateGnss(false);option.disableLocCache=true;client.setLocOption(option);
        client.registerLocationListener(new BDAbstractLocationListener(){@Override public void onReceiveLocation(BDLocation fix){if(fix!=null)handler.post(()->receive(token,fix));}});client.start();handler.postDelayed(()->{if(token==generation&&loading){message="定位超时，请检查网络、位置权限，或移到室外重试"+(lastFixDetail.isEmpty()?"":"（"+lastFixDetail+"）");a.store.debug("定位工具","定位超时",lastFixDetail,true);finishRequest();update();}},20000);
    }catch(Exception|LinkageError e){message="定位启动失败，请检查授权后重试";finishRequest();update();}}
    void receive(int token,BDLocation fix){if(!visible||token!=generation||!loading)return;int type=fix.getLocType();lastFixDetail="返回码 "+type;if(type!=61&&type!=161){message="尚未获取有效位置（"+type+"），正在重试…";update();return;}
        long time=fix.getTimeStamp();if(time<=0)try{time=java.time.LocalDateTime.parse(fix.getTime(),java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();}catch(RuntimeException ignored){}
        if(!Coordinates.valid(fix.getLatitude(),fix.getLongitude())){lastFixDetail="返回坐标无效";return;}
        if(time<requestedAt-2000){lastFixDetail="等待新位置，返回时间 "+fix.getTime();return;}
        if(fix.getMockGnssProbability()==BDLocation.MOCK_GNSS_PROBABILITY_HIGH||fix.getMockGnssProbability()==BDLocation.MOCK_GNSS_PROBABILITY_MIDDLE){lastFixDetail="返回位置疑似模拟定位";return;}
        select(fix.getLatitude(),fix.getLongitude());if(map!=null){map.setMyLocationEnabled(true);map.setMyLocationData(new MyLocationData.Builder().latitude(lat).longitude(lon).accuracy(fix.getRadius()).build());map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(new LatLng(lat,lon),17));}
        message=String.format(Locale.CHINA,"%s\n精度约 %.0f 米 · %s",fix.getAddrStr()==null?"百度定位已返回位置":fix.getAddrStr(),fix.getRadius(),java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));a.store.debug("定位工具","获取当前位置成功","按需单次定位，未用于打卡判断",false);finishRequest();update();
    }
    void finishRequest(){loading=false;generation++;if(client!=null){client.stop();client=null;}}
    void pause(){if(loading)message="已暂停获取位置，点击当前位置可重试";finishRequest();handler.removeCallbacksAndMessages(null);if(view!=null)view.onPause();}
    void close(){visible=false;pause();if(view!=null){view.onDestroy();view=null;map=null;}if(authReceiver!=null){try{a.unregisterReceiver(authReceiver);}catch(RuntimeException ignored){}authReceiver=null;}status=null;pointText=null;mapText=null;start=null;stop=null;}
}
