package cn.qingxing.reminder;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.*;

/** Small mandatory install/start/navigation/reminder check, only in the disposable AVD. */
public final class MinimalSmokeTests extends Instrumentation {
    int checks;Bundle args;MainActivity activity;
    void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();try{
        check(c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode()==Integer.parseInt(args.getString("expectedCode")),"actual installed candidate version");
        boolean verifyBackup="true".equals(args.getString("verifyBackup"));
        main(()->{c.stopService(new Intent(c,MonitorService.class));Store s=new Store(c);s.enabled(false);s.pending(new JSONObject());
            if(verifyBackup){
                check(s.config().optString("ssid").isEmpty()&&!s.day().optBoolean("amDone")&&!s.day().optBoolean("pmDone"),"clean install starts without old data");
                try{
                    JSONObject config=new JSONObject().put("ssid","RestoredOffice").put("amStart","08:30")
                        .put("amEnd","10:00").put("pmStart","17:30").put("pmEnd","19:00")
                        .put("calendar",new JSONObject().put("2026-11-14",true));
                    JSONObject backup=new JSONObject().put("schema",4).put("config",config);
                    MainActivity.restoreBackup(c,backup);
                    JSONObject restored=s.config();
                    check("RestoredOffice".equals(restored.optString("ssid")),"company Wi-Fi restored from settings backup");
                    check("08:30".equals(restored.optString("amStart"))&&"19:00".equals(restored.optString("pmEnd")),"time windows restored");
                    check(restored.optJSONObject("calendar").optBoolean("2026-11-14"),"calendar override restored");
                    check(!s.enabled()&&!s.day().optBoolean("amDone")&&!s.day().optBoolean("pmDone"),"import leaves monitoring off and adds no punch records");
                }catch(Exception e){throw new AssertionError(e);}
            }
            FlowTests.fixture(s);s.prefs.edit().putBoolean("firstGuidePending",false).commit();Alerts.channels(c);});
        activity=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        main(()->{check(!activity.locationUnlocked&&((LinearLayout)activity.root.findViewWithTag("appNavigation")).getChildCount()==3,"location hidden by default");activity.page=3;activity.render();View secret=activity.root.findViewWithTag("version_unlock");for(int i=0;i<10;i++)secret.performClick();check(activity.locationUnlocked,"ten clicks reveal location for smoke coverage");for(int page:new int[]{0,2,4,3}){activity.page=page;activity.render();check(activity.body.getChildCount()>0,"page renders "+page);check(activity.root.isAttachedToWindow(),"page attached "+page);}
            Store s=new Store(c);FlowTests.window(s,"pm");FlowTests.ready(s);FlowTests.sample(c,false);check(s.pending().has("pm"),"minute reminder created");Planner.complete(c,"pm",s.pending().optJSONObject("pm").optLong("due"));check(!s.pending().has("pm")&&s.day().optBoolean("pmDone"),"confirmation stops reminder");check(c.getSystemService(NotificationManager.class).getNotificationChannel(Alerts.MONITOR)!=null,"monitor channel exists");activity.finish();});
        result.putString("stream","PASS "+checks+" final smoke checks: minimal version/start/four pages/reminder confirmation"+(verifyBackup?"/reinstall/backup import":"")+"\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable t){result.putString("stream","FAIL minimal smoke: "+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}}
}
