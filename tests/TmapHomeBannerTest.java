package io.github.ldxm666.tmapclean;
import android.content.SharedPreferences;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;import java.util.*;
/** Verify native marketing cleanup, live restoration and unrelated billboard preservation. */
public final class TmapHomeBannerTest {
 static int checks;static final Map<String,Object> values=new HashMap<>();
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static void flag(boolean on){values.put(Spec.K_HOME_BANNER,on);Cfg.invalidate();}
 public static class Promo{}
 public static class Native {
  boolean image=true,animation=true,clickMask=true,billboard=true;int calls;
  private void update(Promo data){calls++;image=animation=clickMask=data!=null;}
 }
 static class Chain implements XposedInterface.Chain{
  final Native self;final Object[] args;int calls;Object passed;boolean fail;
  Chain(Native v,Promo p){self=v;args=new Object[]{p};}
  public Executable getExecutable(){return null;}public Object getThisObject(){return self;}
  public List<Object> getArgs(){return Arrays.asList(args);}public Object getArg(int i){return args[i];}
  public Object proceed(){return proceed(args);}
  public Object proceed(Object[] a){calls++;if(fail)throw new IllegalStateException("native");passed=a[0];self.update((Promo)a[0]);return "native";}
  public Object proceedWith(Object t){throw new AssertionError("receiver");}public Object proceedWith(Object t,Object[] a){throw new AssertionError("receiver");}
 }
 static Object run(XposedInterface.Hooker h,Chain c){try{return h.intercept(c);}catch(Throwable t){throw new AssertionError(t);}}
 public static void main(String[] args)throws Exception{
  SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
   if(m.getName().equals("getAll"))return new HashMap<>(values);
   if(m.getName().equals("contains"))return values.containsKey(a[0]);
   if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
  });
  for(String n:new String[]{"localCache","resolved"}){Field f=Cfg.class.getDeclaredField(n);f.setAccessible(true);f.set(null,prefs);}
  Field e=Cfg.class.getDeclaredField("embState");e.setAccessible(true);e.set(null,1);Cfg.invalidate();
  check(!Spec.defaultVisible(Spec.K_HOME_BANNER),"marketing hidden by default");
  Method render=Native.class.getDeclaredMethod("update",Promo.class);render.setAccessible(true);
  Field u=TmapHomeBanner.class.getDeclaredField("update");u.setAccessible(true);u.set(null,render);
  XposedInterface.Hooker hook=TmapHomeBanner.bannerHook();Promo promo=new Promo();Native v=new Native();
  for(int bits=0;bits<16;bits++){
   values.put(Spec.K_HOME_FEED,(bits&2)!=0);values.put(Spec.K_MAP_ICONS,(bits&4)!=0);values.put(Spec.K_SEEK_CHIPS,(bits&8)!=0);flag((bits&1)!=0);
   Chain c=new Chain(v,promo);check(run(hook,c).equals("native"),"native result preserved");
   check(c.calls==1,"native cleanup/display called once");
   check(c.passed==(((bits&1)!=0)?promo:null),"only banner switch controls marketing");
   check(v.billboard,"business billboard retained");
  }
  flag(false);run(hook,new Chain(v,promo));
  check(!v.image&&!v.animation&&!v.clickMask,"image, animation and touch mask removed together");
  int before=v.calls;TmapHomeBanner.refresh();check(v.calls==before,"unchanged state does not reload");
  flag(true);TmapHomeBanner.refresh();check(v.image&&v.animation&&v.clickMask&&v.calls==before+1,"live enable restores last native banner");
  flag(false);TmapHomeBanner.refresh();check(!v.image&&v.billboard,"live disable cleans marketing only");
  Chain resume=new Chain(v,promo);check(run(TmapHomeBanner.resumeHook(),resume)==null&&resume.calls==0,"resume cannot revive hidden promotion");
  flag(true);resume=new Chain(v,promo);check(run(TmapHomeBanner.resumeHook(),resume).equals("native")&&resume.calls==1,"enabled resume keeps native semantics");
  run(hook,new Chain(v,null));flag(false);TmapHomeBanner.refresh();flag(true);TmapHomeBanner.refresh();check(!v.image,"native cleared data never resurrected");
  Chain broken=new Chain(v,promo);broken.fail=true;boolean threw=false;try{hook.intercept(broken);}catch(Throwable t){threw=true;}check(threw&&broken.calls==1,"native failures propagate without retry");
  System.out.println("PASS TmapHomeBannerTest checks="+checks);
 }
}
