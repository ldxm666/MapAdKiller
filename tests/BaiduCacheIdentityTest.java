package io.github.ldxm666.bmapclean;

import android.content.SharedPreferences;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** TEST-CACHE-BAIDU-234: preserved timestamps and lengths cannot conceal updated bundles/STY. */
public final class BaiduCacheIdentityTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static Object call(Object receiver,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=receiver.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(receiver,args);
    }
    public static void main(String[] ignored)throws Exception{
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        for(String key:new String[]{"localCache","resolved"}){Field f=Cfg.class.getDeclaredField(key);f.setAccessible(true);f.set(null,prefs);}
        Field emb=Cfg.class.getDeclaredField("embState");emb.setAccessible(true);emb.set(null,1);
        Class<?>[] bundleTypes={String.class,String.class,String.class};
        Object adapter=BaiduTalos.Companion;
        String a=(String)call(adapter,"sourceKey",bundleTypes,"userCore","/same/index.android.bundle","var value=1;");
        String b=(String)call(adapter,"sourceKey",bundleTypes,"userCore","/same/index.android.bundle","var value=2;");
        check(!a.equals(b),"same-size changed JS uses new identity");
        check(a.equals(call(adapter,"sourceKey",bundleTypes,"userCore","/same/index.android.bundle","var value=1;")),"unchanged JS identity stable");
        values.put(Spec.K_MINE_APPLY,true);values.put(Spec.K_MINE_GRID,false);Cfg.invalidate();
        check(!a.equals(call(adapter,"sourceKey",bundleTypes,"userCore","/same/index.android.bundle","var value=1;")),"configuration participates in JS identity");
        File root=Files.createTempDirectory(Paths.get("/data/local/tmp"),"mapclean-sty-test-").toFile(),style=new File(root,"map.sty");
        try{
            Files.write(style.toPath(),new byte[]{1,2,3,4});check(style.setLastModified(1600000000000L),"fixed source timestamp");
            Class<?>[] types={List.class,Set.class};List<File> styles=Collections.singletonList(style);Set<String> hidden=new HashSet<>(Arrays.asList("poi"));
            Object layer=BaiduPoiLayer.Companion;
            String one=(String)call(layer,"sourceKey",types,styles,hidden);long time=style.lastModified();
            Files.write(style.toPath(),new byte[]{1,2,3,5});check(style.setLastModified(time),"updater preserves timestamp");
            String two=(String)call(layer,"sourceKey",types,styles,hidden);
            check(!one.equals(two),"same-length same-timestamp STY rebuilds cache");
            check(two.equals(call(layer,"sourceKey",types,styles,hidden)),"unchanged STY cache stable");
            hidden.add("traffic");check(!two.equals(call(layer,"sourceKey",types,styles,hidden)),"category switches change STY cache");
            check(Arrays.equals(Files.readAllBytes(style.toPath()),new byte[]{1,2,3,5}),"fingerprinting never rewrites host STY");
        }finally{style.delete();root.delete();}
        System.out.println("BaiduCacheIdentityTest: "+checks+" assertions passed");
    }
}
