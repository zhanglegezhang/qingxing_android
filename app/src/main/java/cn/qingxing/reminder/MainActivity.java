package cn.qingxing.reminder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.io.*;

public final class MainActivity extends Activity {
    static final int GREEN=0xff217568, INK=0xff20332e, MUTED=0xff65766d, BG=0xfff5f7f4;
    Store store;LinearLayout root,body;int page=0;LocationPage locationPage;
    private static final String UI_SESSION=UUID.randomUUID().toString();
    boolean locationUnlocked;int versionTapCount;long lastVersionTap=-1;
    final Map<String,EditText> fields=new LinkedHashMap<>();
    final Map<String,String> draft=new LinkedHashMap<>();
    final Handler handler=new Handler(Looper.getMainLooper());
    private boolean homeDataChanged;
    private final Runnable refreshHome=()->{
        if(page!=0||root==null)return;
        boolean changed=homeDataChanged;homeDataChanged=false;
        new Screens(this).refreshHome(changed);
    };
    final SharedPreferences.OnSharedPreferenceChangeListener statusListener=(prefs,key)->{
        if(page!=0||root==null)return;
        if("status".equals(key)){TextView view=root.findViewWithTag("monitorStatus");if(view!=null){String value=prefs.getString("status","正在监测");view.setText(value);view.setVisibility(value.equals("本时段已确认打卡，等待下个时段")?View.GONE:View.VISIBLE);}}
        if("day".equals(key)||"pending".equals(key)||"events".equals(key)){
            if(!"events".equals(key))homeDataChanged=true;
            handler.removeCallbacks(refreshHome);handler.post(refreshHome);
        }
    };
    final Map<String,TextView> configBadges=new LinkedHashMap<>();
    TextView autoSaveStatus;boolean rendering;String lastSaveError="";
    ScrollView contentScroll;int renderedPage=-1;final Map<Integer,Integer> scrollOffsets=new HashMap<>();
    private boolean autostartConfirmationPending;
    private AlertDialog autostartDialog;
    private android.window.OnBackInvokedCallback exitBackCallback;
    final Map<String,Long> doneTaps=new HashMap<>();
    YearMonth month=YearMonth.now();
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    @Override public void onCreate(Bundle b){super.onCreate(b);store=new Store(this);Alerts.channels(this);MockLocationService.cleanup(this);locationPage=new LocationPage(this);restoreLocationAccess(b);if(b!=null){autostartConfirmationPending=b.getBoolean("autostartConfirmationPending",false);page=Math.max(0,Math.min(4,b.getInt("page",0)));month=YearMonth.parse(b.getString("month",YearMonth.now().toString()));JSONObject saved=Store.json(b.getString("draft","{}"));Iterator<String> it=saved.keys();while(it.hasNext()){String k=it.next();draft.put(k,saved.optString(k));}}locationIntent(getIntent());render();if(store.prefs.getBoolean("firstGuidePending",false)){store.prefs.edit().putBoolean("firstGuidePending",false).commit();handler.post(this::showIntroduction);}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("locationPage",false)){locationIntent(intent);render();}}
    @Override protected void onStart(){
        super.onStart();
        if(Build.VERSION.SDK_INT>=33&&exitBackCallback==null){
            exitBackCallback=this::onBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,exitBackCallback);
        }
    }
    @Override public void onBackPressed(){
        MockLocationService.stop(this);
        finish();
    }
    void restoreLocationAccess(Bundle state){locationUnlocked=state!=null&&UI_SESSION.equals(state.getString("uiSession"))&&state.getBoolean("locationUnlocked",false);}
    void locationIntent(Intent intent){if(intent.getBooleanExtra("locationPage",false)){page=locationUnlocked?4:3;if(locationUnlocked)locationPage.virtual=true;}}
    void resetVersionTaps(){versionTapCount=0;lastVersionTap=-1;}
    void versionBlankTap(){
        if(locationUnlocked)return;long now=SystemClock.elapsedRealtime();
        if(lastVersionTap<0||now-lastVersionTap>2000)versionTapCount=0;
        lastVersionTap=now;
        if(++versionTapCount==10){locationUnlocked=true;resetVersionTaps();commitWifiOnExit();render();toast("已显示定位栏目");}
    }
    void showIntroduction(){if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("必要授权").setMessage("请完成 1、2、3、4、5 项必要授权。").setPositiveButton("确认",(d,w)->openSettingsTop()).setNegativeButton("否",(d,w)->openSettingsTop()).setOnCancelListener(d->openSettingsTop()).show();}
    void openSettingsTop(){commitWifiOnExit();stashDraft();page=3;render();scrollOffsets.put(3,0);contentScroll.post(()->contentScroll.scrollTo(0,0));}

    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);stashDraft();JSONObject j=new JSONObject();for(Map.Entry<String,String>e:draft.entrySet())Store.put(j,e.getKey(),e.getValue());b.putString("month",month.toString());b.putInt("page",page);b.putString("draft",j.toString());b.putBoolean("autostartConfirmationPending",autostartConfirmationPending);b.putString("uiSession",UI_SESSION);b.putBoolean("locationUnlocked",locationUnlocked);}
    @Override protected void onResume(){super.onResume();store.prefs.registerOnSharedPreferenceChangeListener(statusListener);Planner.rebuild(this);MonitorService.reconcile(this,true);HolidaySync.schedule(this);render();handler.post(this::showAutostartConfirmation);HolidaySync.start(this,false,()->{if(!isFinishing()&&!isDestroyed()){stashDraft();if(page!=3&&page!=4)render();}});}
    @Override protected void onPause(){store.prefs.unregisterOnSharedPreferenceChangeListener(statusListener);handler.removeCallbacks(refreshHome);resetVersionTaps();commitWifiOnExit();stashDraft();if(locationPage!=null)locationPage.pause();super.onPause();}
    @Override protected void onDestroy(){
        if(isFinishing())MockLocationService.stop(this);
        if(Build.VERSION.SDK_INT>=33&&exitBackCallback!=null){
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(exitBackCallback);
            exitBackCallback=null;
        }
        handler.removeCallbacksAndMessages(null);if(locationPage!=null)locationPage.close();if(autostartDialog!=null){autostartDialog.setOnCancelListener(null);autostartDialog.dismiss();autostartDialog=null;}super.onDestroy();
    }
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(CheckMark.styled(this,s));t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(2),0,dp(2));if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    LinearLayout card(){return card(body);}
    LinearLayout card(LinearLayout parent){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(12),dp(8),dp(12),dp(8));l.setBackground(bg(Color.WHITE,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));parent.addView(l,p);return l;}
    LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    Button action(LinearLayout p,String title,Runnable run,boolean primary){Button b=new Button(this);b.setText(CheckMark.styled(this,title));b.setTextSize(13);b.setAllCaps(false);b.setElevation(0);b.setStateListAnimator(null);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(6),0,dp(6),0);b.setTextColor(primary?Color.WHITE:GREEN);b.setBackground(bg(primary?GREEN:0xffe9f1ec,9));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(p.getOrientation()==LinearLayout.HORIZONTAL?0:-1,dp(44),p.getOrientation()==LinearLayout.HORIZONTAL?1:0);lp.setMargins(0,dp(4),p.getOrientation()==LinearLayout.HORIZONTAL?dp(4):0,0);p.addView(b,lp);b.setOnClickListener(v->run.run());return b;}
    void button(LinearLayout p,String title,Runnable run,boolean primary){action(p,title,run,primary);}
    void info(String title,String message){resetVersionTaps();commitWifiOnExit();new AlertDialog.Builder(this).setTitle(CheckMark.styled(this,title)).setMessage(CheckMark.styled(this,message)).setPositiveButton("知道了",null).show();}
    void heading(LinearLayout c,String title,String sub){LinearLayout r=row(c);TextView t=text(title,16,INK,true);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));if(!sub.isEmpty())help(r,title,sub);}
    void help(LinearLayout r,String title,String message){help(r,title,message,false);}
    void help(LinearLayout r,String title,String message,boolean small){View icon=new View(this){final android.graphics.Paint p=new android.graphics.Paint(3);@Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f;p.setColor(GREEN);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(1));c.drawCircle(x,y,dp(small?7:9),p);p.setStyle(android.graphics.Paint.Style.FILL);p.setTextAlign(android.graphics.Paint.Align.CENTER);p.setTextSize(dp(small?11:13));p.setTypeface(Typeface.DEFAULT_BOLD);android.graphics.Paint.FontMetrics f=p.getFontMetrics();c.drawText("!",x,y-(f.ascent+f.descent)/2,p);}};icon.setContentDescription(title+"说明");r.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));icon.setOnClickListener(v->info(title,message));}
    EditText input(LinearLayout p,String label,String value){LinearLayout r=row(p);TextView l=text(label,13,MUTED,false);r.addView(l,new LinearLayout.LayoutParams(dp(110),-2));EditText e=new EditText(this);e.setText(value);e.setTextSize(15);e.setTextColor(INK);e.setSingleLine(true);r.addView(e,new LinearLayout.LayoutParams(0,dp(44),1));return e;}
    void field(LinearLayout p,String k,String label,String value){fields.put(k,input(p,label,draft.containsKey(k)?draft.get(k):value));}
    String val(String k){return fields.get(k).getText().toString().trim();}
    String current(String k){return fields.containsKey(k)?val(k):draft.getOrDefault(k,store.config().optString(k,""));}
    boolean sameConfig(String... keys){JSONObject cfg=store.config();for(String key:keys)if(!current(key).equals(cfg.optString(key,"")))return false;return true;}
    void badges(){for(TextView b:configBadges.values()){boolean saved=!store.config().optString("ssid").isEmpty()&&sameConfig("ssid");b.setText(String.valueOf(b.getTag()));android.graphics.drawable.Drawable mark=saved?new CheckMark(b.getCurrentTextColor()):null;if(mark!=null)mark.setBounds(0,0,dp(20),dp(20));b.setCompoundDrawables(mark,null,null,null);b.setCompoundDrawablePadding(dp(4));}updateSaveStatus();}
    void updateSaveStatus(){if(autoSaveStatus==null)return;String msg;boolean error=false;
        if(!sameConfig("amStart","amEnd","pmStart","pmEnd")){msg="未生效：时段无效或冲突，继续使用上次有效时段";error=true;}
        else if(!sameConfig("ssid")&&current("ssid").isEmpty()&&!fields.get("ssid").hasFocus()){msg="未生效：公司 Wi-Fi 名称不能为空，继续使用原配置";error=true;}
        else if(!lastSaveError.isEmpty()){msg="未生效："+lastSaveError;error=true;}
        else if(!sameConfig("ssid"))msg="编辑中，结束输入后自动保存";
        else if(store.config().optString("ssid").isEmpty())msg="请填写或绑定公司 Wi-Fi";
        else msg="已自动保存";
        autoSaveStatus.setText(msg);autoSaveStatus.setTextColor(error?0xffb42318:MUTED);
    }
    void watchFields(){for(EditText e:fields.values())e.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){lastSaveError="";badges();}public void afterTextChanged(android.text.Editable e){}});
        EditText wifi=fields.get("ssid");wifi.setOnFocusChangeListener((v,focus)->{if(!focus&&!rendering)commitWifiOnExit();});wifi.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);wifi.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commitWifiOnExit();root.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);return true;}return false;});badges();}
    void render(){
        if(locationPage!=null)locationPage.close();
        if(page==4&&!locationUnlocked)page=3;
        stashDraft();if(contentScroll!=null&&renderedPage>=0)scrollOffsets.put(renderedPage,contentScroll.getScrollY());rendering=true;if(page==1)page=3;fields.clear();configBadges.clear();autoSaveStatus=null;root=new LinearLayout(this);root.setFocusableInTouchMode(true);root.setOrientation(1);root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets a=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(a.left,a.top,a.right,a.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(6),dp(16),dp(4));root.addView(header);
        TextView brand=text("轻醒",22,INK,true);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(text(new String[]{"今天","设置","工作日历","设置","定位"}[page],13,MUTED,false));
        ScrollView scroll=new ScrollView(this);contentScroll=scroll;scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(12),dp(4),dp(12),dp(6));scroll.addView(body);
        if(page==0)home();else if(page==2)new Screens(this).calendar();else if(page==4)locationPage.render();else settings();
        LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.WHITE);nav.setPadding(dp(8),dp(4),dp(8),dp(4));root.addView(nav);
        nav.setTag("appNavigation");String[] tabs=locationUnlocked?new String[]{"今天","日历","定位","设置"}:new String[]{"今天","日历","设置"};int[] pages=locationUnlocked?new int[]{0,2,4,3}:new int[]{0,2,3};for(int i=0;i<tabs.length;i++){final int index=pages[i];LinearLayout item=new LinearLayout(this);item.setOrientation(1);item.setGravity(Gravity.CENTER);if(page==index)item.setBackground(bg(0xffe5efe8,12));nav.addView(item,new LinearLayout.LayoutParams(0,dp(52),1));item.addView(new Screens.NavIcon(this,index,page==index?GREEN:MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));TextView label=text(tabs[i],11,page==index?GREEN:MUTED,page==index);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(2),0,0);item.addView(label);item.setContentDescription(tabs[i]);item.setOnClickListener(v->{resetVersionTaps();commitWifiOnExit();stashDraft();page=index;render();});label.setOnClickListener(v->item.performClick());}
        renderedPage=page;rendering=false;int offset=scrollOffsets.getOrDefault(page,0);scroll.post(()->{if(scroll==contentScroll)scroll.scrollTo(0,offset);});
    }

    private void home(){new Screens(this).home();}
    private void attendance(){new Screens(this).attendance();}
    private void override(EditText e,Boolean work){try{String date=LocalDate.parse(e.getText().toString().trim()).toString();JSONObject cfg=store.config(),days=cfg.optJSONObject("calendar");if(work==null)days.remove(date);else Store.put(days,date,work);store.saveConfig(cfg);Planner.rebuild(this);stopService(new Intent(this,MonitorService.class));MonitorService.reconcile(this,true);render();}catch(Exception ex){toast("日期格式请用 2026-10-24");}}
    boolean autoSave(boolean wifi,boolean times){
        if(rendering||!fields.containsKey("ssid"))return false;
        try{JSONObject cfg=store.config();String[] keys=wifi&&times?new String[]{"ssid","amStart","amEnd","pmStart","pmEnd"}:wifi?new String[]{"ssid"}:new String[]{"amStart","amEnd","pmStart","pmEnd"};boolean changed=false;
            for(String key:keys){String value=val(key);if(!value.equals(cfg.optString(key)))changed=true;Store.put(cfg,key,value);}
            if(wifi&&cfg.optString("ssid").isEmpty())throw new IllegalArgumentException("公司 Wi-Fi 名称不能为空，继续使用原配置");
            JSONObject backup=new JSONObject();Store.put(backup,"schema",4);Store.put(backup,"config",cfg);validateBackup(backup);
            if(changed){store.saveConfig(cfg);Planner.reset(this);if(!store.hasTimes())store.enabled(false);Planner.rebuild(this);stopService(new Intent(this,MonitorService.class));MonitorService.reconcile(this,true);store.log("已自动保存"+(wifi?"公司 Wi-Fi":"提醒时段"));}
            for(String key:keys)draft.remove(key);lastSaveError="";badges();Switch toggle=root.findViewWithTag("monitor_switch");if(toggle!=null&&toggle.isChecked()!=store.enabled())toggle.setChecked(store.enabled());return true;
        }catch(Exception e){lastSaveError=e.getMessage()==null?"请检查配置":e.getMessage();badges();store.debug("自动保存","配置未生效",lastSaveError,true);return false;}
    }
    void commitWifiOnExit(){if(!rendering&&renderedPage==3&&fields.containsKey("ssid")&&!sameConfig("ssid"))autoSave(true,false);}
    void captureWifi(){if(!WifiConnection.permitted(this,false)){locationPermission();return;}WifiConnection.State wifi=WifiConnection.read(this,"unused");if(wifi.name.isEmpty()){toast("未能读取当前 Wi-Fi 名称，可手动填写；请检查系统 Wi-Fi 名称权限及位置开关");return;}wifiCaptured(wifi.name);}
    void wifiCaptured(String name){fields.get("ssid").setText(name);if(autoSave(true,false))toast("公司 Wi-Fi 已自动保存");}
    void selectedTime(String key,String value){fields.get(key).setText(value);draft.put(key,value);autoSave(false,true);}
    interface TimeResult{void selected(String value);}
    void pickTime(String value,TimeResult result){java.time.LocalTime t;try{t=java.time.LocalTime.parse(value);}catch(RuntimeException e){t=java.time.LocalTime.now();}new TimePickerDialog(this,(view,h,m)->result.selected(String.format(Locale.ROOT,"%02d:%02d",h,m)),t.getHour(),t.getMinute(),true).show();}
    void resetTimes(boolean clear){commitWifiOnExit();String[] keys={"amStart","amEnd","pmStart","pmEnd"},values={"08:30","10:00","17:30","19:00"};for(int i=0;i<keys.length;i++){fields.get(keys[i]).setText(clear?"":values[i]);draft.put(keys[i],clear?"":values[i]);}if(autoSave(false,true))toast(clear?"时间已清空，监控已暂停":"默认时段已自动保存");}
    void attendanceClick(String w){
        if(!store.day().optBoolean(w+"Done")){doneTaps.remove(w);try{attendanceLaunch(w,getPackageManager().getLaunchIntentForPackage("com.tencent.wework"));}catch(SecurityException e){store.debug("企微","打开失败","系统不允许打开企业微信",true);toast("无法打开企业微信，请检查系统限制");}return;}
        long now=SystemClock.elapsedRealtime(),last=doneTaps.containsKey(w)?doneTaps.get(w):-1000;
        if(now-last<=500){doneTaps.remove(w);Planner.attendanceUndo(this,w);toast("已取消打卡确认");render();}else doneTaps.put(w,now);
    }
    void stashDraft(){if(renderedPage==3)for(Map.Entry<String,EditText> e:fields.entrySet())draft.put(e.getKey(),e.getValue().getText().toString());}
    void syncCalendar(){stashDraft();HolidaySync.start(this,true,()->{if(!isFinishing()&&!isDestroyed())render();});render();}
    void changeDay(java.time.LocalDate date,Boolean work){JSONObject cfg=store.config(),days=cfg.optJSONObject("calendar");if(work==null)days.remove(date.toString());else Store.put(days,date.toString(),work);store.saveConfig(cfg);Planner.rebuild(this);stopService(new Intent(this,MonitorService.class));MonitorService.reconcile(this,true);render();}
    void settings(){
        LinearLayout c=card();heading(c,"必要授权","");
        button(c,(Authorizations.ready(this)?"✅":"❌")+" "+Authorizations.ENTRY_LABEL,this::permissionGuide,!Authorizations.ready(this));
        button(c,(getSystemService(NotificationManager.class).areNotificationsEnabled()?"✅":"❌")+" "+Authorizations.NOTIFICATION_PATH,this::notificationPermission,false);
        action(c,(WifiConnection.permitted(this,false)?"✅":"❌")+" "+Authorizations.PRECISE_PATH,this::appSettings,false).setTag("auth_precise");
        action(c,(WifiConnection.permitted(this,true)?"✅":"❌")+" "+Authorizations.BACKGROUND_PATH,this::appSettings,false).setTag("auth_background");
        button(c,(Planner.exact(this)?"✅":"❌")+" "+Authorizations.ALARM_PATH,this::exactSettings,false);
        action(c,(Authorizations.autostart(this)?"✅":"❌")+" "+Authorizations.AUTOSTART_PATH,this::autostartSettings,false).setTag("auth_autostart");
        attendance();
        LinearLayout data=card();heading(data,"本机数据","Wi-Fi 名称、提醒时段和打卡记录保存在手机。备份包含公司 Wi-Fi、时段和日历设置，不包含打卡历史。");
        LinearLayout backups=row(data);button(backups,"导出设置备份",()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"轻醒设置.json");startActivityForResult(i,40);},false);
        button(backups,"导入设置备份",()->new AlertDialog.Builder(this).setTitle("导入会替换公司设置").setMessage("当前重复提醒将停止，导入后需手动重新开启打卡监测。").setPositiveButton("选择备份",(d,w)->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),41)).setNegativeButton("取消",null).show(),false);
        LinearLayout about=card(),versionRow=row(about);TextView version=text("轻醒 "+getString(R.string.app_version_name),16,INK,true);version.setTag("version_label");versionRow.addView(version,new LinearLayout.LayoutParams(-2,-2));version.setOnClickListener(v->resetVersionTaps());
        View blank=new View(this);blank.setTag("version_unlock");blank.setContentDescription("版本区域");versionRow.addView(blank,new LinearLayout.LayoutParams(0,dp(36),1));blank.setOnClickListener(v->versionBlankTap());
        help(versionRow,"轻醒 "+getString(R.string.app_version_name),"每 10 秒查看公司 Wi-Fi，监控时保持安静；满一分钟后按条件每分钟提醒，响铃和振动持续 5 秒。点击首页打卡按钮会打开企业微信，并直接记为已打卡、停止对应提醒，请在企业微信完成打卡。手机静音、勿扰和通知设置仍会影响提醒。调休安排自动同步。");
    }
    void permissionGuide(){
        commitWifiOnExit();
        String[] labels={(getSystemService(NotificationManager.class).areNotificationsEnabled()?"✅":"❌")+" "+Authorizations.NOTIFICATION_PATH,(WifiConnection.permitted(this,false)?"✅":"❌")+" "+Authorizations.PRECISE_PATH,(WifiConnection.permitted(this,true)?"✅":"❌")+" "+Authorizations.BACKGROUND_PATH,(Planner.exact(this)?"✅":"❌")+" "+Authorizations.ALARM_PATH,(Authorizations.autostart(this)?"✅":"❌")+" "+Authorizations.AUTOSTART_PATH,(notificationAllowed(Alerts.monitorChannel(this))&&notificationAllowed(Alerts.remindChannel(this))?"✅":"❌")+" 监控和打卡通知"};
        CharSequence[] marked=new CharSequence[labels.length];for(int i=0;i<labels.length;i++)marked[i]=CheckMark.styled(this,labels[i]);
        new AlertDialog.Builder(this).setTitle(CheckMark.styled(this,(Authorizations.ready(this)?"✅ ":"❌ ")+Authorizations.ENTRY_LABEL)).setItems(marked,(d,w)->{switch(w){case 0:notificationPermission();break;case 1:case 2:appSettings();break;case 3:exactSettings();break;case 4:autostartSettings();break;default:channel(!notificationAllowed(Alerts.monitorChannel(this))?Alerts.monitorChannel(this):Alerts.remindChannel(this));}}).setNegativeButton("关闭",null).show();
    }
    Intent appSettingsIntent(){return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));}
    void appSettings(){open(appSettingsIntent());}
    void autostartSettings(){
        try{startActivityForResult(appSettingsIntent(),42);}
        catch(ActivityNotFoundException|SecurityException e){store.debug("授权","无法打开自启动设置",e.getClass().getSimpleName(),true);toast("无法打开应用信息，请在系统设置中找到轻醒");}
    }
    void showAutostartConfirmation(){
        if(!autostartConfirmationPending||autostartDialog!=null||isFinishing()||isDestroyed())return;
        autostartDialog=new AlertDialog.Builder(this).setTitle("是否已开启自启动？").setMessage(CheckMark.styled(this,"请确认已在系统应用信息中勾选轻醒的自启动。应用无法自动核验该设置，只有你确认后才显示 ✅。未开启或取消会显示 ❌，必要授权未完成时暂停监控。"))
            .setPositiveButton("已开启",(d,w)->finishAutostartConfirmation(true)).setNegativeButton("取消",(d,w)->finishAutostartConfirmation(false)).setOnCancelListener(d->finishAutostartConfirmation(false)).create();
        autostartDialog.show();
    }
    void finishAutostartConfirmation(boolean confirmed){autostartConfirmationPending=false;autostartDialog=null;setAutostart(confirmed);}
    void setAutostart(boolean confirmed){store.prefs.edit().putBoolean("autostartConfirmed",confirmed).commit();store.debug("授权","自启动手动确认",confirmed?"用户已确认允许后台自启动":"用户已撤销确认，暂停监控",!confirmed);Planner.rebuild(this);MonitorService.reconcile(this,true);render();}
    boolean notificationAllowed(String id){return Alerts.allowed(this,id);}
    void channel(String id){open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,id));}
    void notificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);else open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
    void locationPermission(){if(!MonitorService.permitted(this,false))requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},11);else open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    void exactSettings(){if(Build.VERSION.SDK_INT>=31)open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));else toast("当前系统无需单独授权");}
    /** Opening WeCom is the user's attendance confirmation; a failed launch leaves it pending. */
    void attendanceLaunch(String key,Intent launch){if(!AttendanceData.attendanceKey(key))return;if(launchWeCom(launch)){Planner.attendanceDone(this,key);render();}}
    boolean launchWeCom(Intent launch){
        if(launch==null){store.debug("企微","打开失败","未安装企业微信或没有可用启动入口",true);toast("未安装企业微信或无法打开，请检查安装状态");return false;}
        try{startActivity(launch);store.debug("企微","已请求打开","已打开企业微信，并按你的选择记为已打卡，请完成实际打卡",false);return true;}
        catch(ActivityNotFoundException|SecurityException e){store.debug("企微","打开失败",e.getClass().getSimpleName(),true);toast("无法打开企业微信，请检查安装状态和系统限制");return false;}
    }
    void open(Intent i){try{startActivity(i);}catch(ActivityNotFoundException e){toast("系统未提供此入口，请在设置中找到轻醒");}}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    String clock(long t){return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);store.debug("授权","授权返回",Authorizations.ready(this)?"必要授权完成":"仍缺少："+Authorizations.missing(this),!Authorizations.ready(this));Planner.rebuild(this);MonitorService.reconcile(this,true);render();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42){autostartConfirmationPending=true;handler.post(this::showAutostartConfirmation);return;}if(result!=RESULT_OK||data==null)return;
        try{
            if(request==40){JSONObject backup=makeBackup(store);try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(backup.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}toast("设置已导出");}
            else if(request==41){String raw;try(InputStream in=getContentResolver().openInputStream(data.getData())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IllegalArgumentException("备份过大");out.write(b,0,n);}raw=out.toString("UTF-8");}JSONObject backup=new JSONObject(raw);validateBackup(backup);
                restoreBackup(this,backup);draft.clear();fields.clear();lastSaveError="";toast("公司设置已导入，打卡监测保持暂停");render();
            }
        }catch(Exception e){toast("操作失败，未应用无效备份："+e.getMessage());}
    }
    static JSONObject makeBackup(Store s){JSONObject b=new JSONObject();Store.put(b,"schema",4);Store.put(b,"config",s.config());return b;}
    static void restoreBackup(Context c,JSONObject b)throws Exception{validateBackup(b);Store s=new Store(c);s.enabled(false);Planner.reset(c);c.stopService(new Intent(c,MonitorService.class));s.saveConfig(Store.cleanConfig(b.getJSONObject("config")));Planner.rebuild(c);}
    static void validateBackup(JSONObject b)throws Exception{
        int schema=b.getInt("schema");if(schema<1||schema>4)throw new IllegalArgumentException("不支持的备份版本");JSONObject c=b.getJSONObject("config");
        if(c.has("ssid")&&!(c.get("ssid") instanceof String))throw new IllegalArgumentException("Wi-Fi 名称无效");
        boolean empty=true;for(String k:new String[]{"amStart","amEnd","pmStart","pmEnd"})if(!c.getString(k).isEmpty())empty=false;
        if(!empty&&!Rules.validWindows(c.getString("amStart"),c.getString("amEnd"),c.getString("pmStart"),c.getString("pmEnd")))throw new IllegalArgumentException("时间段无效");
        JSONObject days=c.getJSONObject("calendar");Iterator<String> it=days.keys();while(it.hasNext()){String d=it.next();LocalDate.parse(d);if(!(days.get(d) instanceof Boolean))throw new IllegalArgumentException("工作日值无效");}
    }
}
