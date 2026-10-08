package io.github.ldxm666.bmapclean;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Only verified page modules are adapted; built-ins and other Talos pages are untouched. */
public final class BaiduTalos {
    private static final Map<String,String> paths = new HashMap<>();
    private BaiduTalos() {}
    static void install(ClassLoader cl) {
        BaiduHooks.hook(H.cls(cl,"com.baidu.talos.core.runtime.TalosBaseRuntime"),"getModulePath",chain -> {
            String path=(String)chain.proceed();return adapt((String)chain.getArg(0),path);
        },String.class);
        Class<?> pm=H.cls(cl,"com.baidu.talos.core.data.ParamMap");
        Class<?> callback=H.cls(cl,"com.baidu.talos.core.bridge.a");
        Class<?> bridge=H.cls(cl,"com.baidu.talos.core.bridge.TalosBridge");
        if(pm==null||callback==null)return;
        for(String name:new String[]{"loadJSFile","preLoadJSFile"}) {
            BaiduHooks.hook(bridge,name,chain -> {
                Object map=chain.getArg(0);
                Method get=pm.getMethod("getString",String.class);
                String biz=(String)get.invoke(map,"biz_pkg_name"), path=(String)get.invoke(map,"biz_pkg_path");
                String replacement=adapt(biz,path);
                if(replacement!=null&&!replacement.equals(path)) pm.getMethod("putString",String.class,String.class).invoke(map,"biz_pkg_path",replacement);
                return chain.proceed();
            },pm,callback);
        }
    }
    private static synchronized String adapt(String module,String path) {
        if(path==null||module==null)return path;
        if(!"userCore".equals(module)&&!"userCenter".equals(module)&&!"HomeFeed".equals(module))return path;
        try {
            File requested=new File(path);
            File source=new File(requested.getParentFile(),"index.android.bundle");
            String canonical=source.getCanonicalPath();
            if(!canonical.startsWith("/data/user/0/com.baidu.BaiduMap/files/talos/dpmbundles/")
                    &&!canonical.startsWith("/data/data/com.baidu.BaiduMap/files/talos/dpmbundles/"))return path;
            if(!source.isFile()||source.length()>4*1024*1024)return path;
            String key=module+":"+canonical+":"+source.lastModified()+":"+BaiduPageScripts.signature();
            String cached=paths.get(key);if(cached!=null)return cached;
            String original=read(source);
            String result=BaiduPageScripts.adapt(module,original);
            if(result.equals(original)){paths.put(key,canonical);return canonical;}
            File out=new File(source.getParentFile(),"index.android.mapclean-210.bundle");
            String current=out.isFile()?read(out):null;
            if(!result.equals(current))try(FileOutputStream f=new FileOutputStream(out)){f.write(result.getBytes("UTF-8"));}
            paths.put(key,out.getAbsolutePath());
            H.log("talos adapted module="+module+" bytes="+result.getBytes("UTF-8").length);
            return out.getAbsolutePath();
        } catch(Throwable t){H.log("talos adapter skipped "+module+": "+t.getClass().getSimpleName());return path;}
    }
    private static String read(File file)throws Exception{
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
        }
    }
}
