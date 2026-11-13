package io.github.ldxm666.mapclean.tests;
import android.app.*;import android.os.*;import android.content.*;import android.accessibilityservice.AccessibilityServiceInfo;import android.view.accessibility.*;import android.graphics.Rect;
public class HostTreeProbe extends Instrumentation{
 String component;
 public void onCreate(Bundle b){super.onCreate(b);component=b==null?null:b.getString("component");start();}
 public void onStart(){Bundle b=new Bundle();try{
  UiAutomation ui=getUiAutomation();AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
  String pkg=component==null?null:component.split("/")[0];
  if(component!=null){if(!component.matches("[A-Za-z0-9_.$/]+"))throw new IllegalArgumentException("component");ComponentName target=ComponentName.unflattenFromString(component);if(target==null)throw new IllegalArgumentException("component");getTargetContext().startActivity(new Intent().setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
  AccessibilityNodeInfo root=null;for(int i=0;i<30;i++){SystemClock.sleep(200);root=ui.getRootInActiveWindow();if(root!=null&&(pkg==null||pkg.equals(String.valueOf(root.getPackageName()))))break;root=null;}StringBuilder out=new StringBuilder();if(root!=null)walk(root,out,0);else out.append("NO_TARGET_ROOT\n");b.putString("stream",out.toString());
 }catch(Throwable e){b.putString("stream","FAIL "+e);}finish(Activity.RESULT_OK,b);}
 void walk(AccessibilityNodeInfo n,StringBuilder out,int d){if(d>35)return;Rect r=new Rect();n.getBoundsInScreen(r);out.append(n.getClassName()).append(" id=").append(n.getViewIdResourceName()).append(" text=").append(n.getText()).append(" desc=").append(n.getContentDescription()).append(" bounds=").append(r).append('\n');for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)walk(child,out,d+1);}}
}
