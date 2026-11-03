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
                // ParamMap 为 null 时 Method.invoke(null, ...) 会抛 NullPointerException；
                // 这一条会被 mapclean.HookGuard 判死，于是"百度页面脚本适配"整个进程失效
                // （用户侧看不到任何提示，只是推荐流/我的页突然恢复原样）。先原样放行。
                if(map==null)return chain.proceed();
                Method get=paramGet(pm), put=paramPut(pm);
                if(get==null||put==null)return chain.proceed();
                String biz=(String)get.invoke(map,"biz_pkg_name"), path=(String)get.invoke(map,"biz_pkg_path");
                String replacement=adapt(biz,path);
                if(replacement!=null&&!replacement.equals(path)) put.invoke(map,"biz_pkg_path",replacement);
                return chain.proceed();
            },pm,callback);
        }
    }
    /**
     * ParamMap.getString / putString 的 Method 缓存。
     *
     * <p>原来每次 {@code loadJSFile}/{@code preLoadJSFile} 都各查一次 {@code getMethod}
     * —— 那要遍历整张方法表并对每个参数类型做比较，而页面加载正是启动期热路径。
     *
     * <p>benign race：多个线程同时查就是查两次、结果一样，不加锁也不阻塞。缓存校验
     * {@code getDeclaringClass()==pm}，万一哪天解析到另一个同类名的 ParamMap 也不会串。
     */
    private static volatile Method GET_STRING, PUT_STRING;
    private static Method paramGet(Class<?> pm) {
        Method m=GET_STRING;
        if(m!=null&&m.getDeclaringClass()==pm)return m;
        // 缓存不命中（或命中别的类）就重查一次。查不到必须返回 **null** 而不是把旧值
        // 带回去 —— 拿一个属于别的类的 Method 去 invoke 会抛 IllegalArgumentException，
        // 那又会把整条 hook 拖下水。
        try { Method fresh=pm.getMethod("getString",String.class); GET_STRING=fresh; return fresh; }
        catch(Throwable ignored){ return null; }
    }
    private static Method paramPut(Class<?> pm) {
        Method m=PUT_STRING;
        if(m!=null&&m.getDeclaringClass()==pm)return m;
        try { Method fresh=pm.getMethod("putString",String.class,String.class); PUT_STRING=fresh; return fresh; }
        catch(Throwable ignored){ return null; }
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
