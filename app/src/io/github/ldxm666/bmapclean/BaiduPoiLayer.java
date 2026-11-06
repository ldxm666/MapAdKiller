package io.github.ldxm666.bmapclean;

import android.app.Application;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Prepare individual STY tables and replace only their exact, read-only native
 * file opens. SDK configuration/resource roots and all original files stay intact.
 */
final class BaiduPoiLayer {
    private static final String SCHEMA="poi-file-assets-v9";
    private static final String[] STYLES={"map.sty","traffic.sty","weakmap.sty","reduct.sty","weaktraffic.sty","weakreduct.sty"};
    private static String cachedKey;
    private static File cachedDirectory;
    private static int cachedRules, cachedDrawings;
    private static boolean failedLogged, nativePrepared;
    static void install(ClassLoader cl) {
        BaiduHooks.hook(H.cls(cl,"com.baidu.platform.comjni.map.basemap.AppBaseMap"),"initWithOptions",chain -> {
            Bundle options=(Bundle)chain.getArg(0);
            // ★ 2.3.2：**先把宿主 Application 交给 HostPrefs，再算分类**。
            //
            // 真机实证（22.1.20 / pid 8347）：initWithOptions 在 01:51:56.498 就跑完了
            // 分类决策，而 Cfg 拿到宿主本地 prefs 是 01:51:59.598（cfg_snap n=43 backend=1）
            // —— 中间差 3.1 秒。这 3 秒里 Cfg.prefs() 只有 LSPatch 那个空表的只读 remote
            // 可读，于是每个键都 present=false、落回缺省 false = 隐藏。
            // 用户拨的逐类开关全部被忽略，界面表现就是「地图 POI 独立开关无效」。
            // 把 Application 提前交出去，local() 当场就能成功，读到的就是用户真正设的值。
            Application app=currentApplication();
            if (app!=null) {
                io.github.ldxm666.mapclean.HostPrefs.remember(app);
                // 丢掉「刚才本地拿不到」的探测结论 —— 否则 500ms 节流会把那次失败
                // 继续当成 fallback 用，等于没修。
                Cfg.resetBackend();
            }
            java.util.Set<String> hidden=hiddenCategories();
            boolean hide=!hidden.isEmpty();
            boolean prepared=false;
            if (hide && options!=null && app!=null) try {
                prepared=prepare(options,app,hidden);
            } catch (Throwable error) {
                disable();
                if (!failedLogged) {
                    failedLogged=true;
                    H.log("poi_style original_preserved reason="+error.getClass().getSimpleName());
                }
            }
            if (!hide || !prepared) disable();
            H.log("poi_style policy="+(hide?"hidden":"visible")+" categories="+hidden.size()
                    +"/"+(BaiduLabelSettings.CATS.length+1)+" original_bundle=true file_mapping="+prepared
                    // backend / n 是这次修复的可观测出口：backend=1 且 n>0 才说明
                    // 「按用户配置做的决策」；backend=2 或 n=0 就是又读早了。
                    +" backend="+Cfg.backend()+" host="+(app!=null));
            // The original Bundle, including every SDK root, is always passed once.
            Object result=chain.proceed();
            H.log("poi_style initialized="+Boolean.TRUE.equals(result)+" redirected=false file_mapping="+prepared);
            return result;
        },Bundle.class,boolean.class);
    }
    /**
     * 宿主 Application（可能为 null：initWithOptions 也可能在网络子进程里跑）。
     *
     * <p>走 {@code ActivityThread.currentApplication()} 而不是缓存 —— 这个方法在
     * 主进程第一次调用的时刻就一定有值，把它交给 {@link io.github.ldxm666.mapclean.HostPrefs}
     * 之后，{@code Cfg} 才能当场解析出宿主本地 prefs。
     */
    private static Application currentApplication() {
        try {
            Object app=Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application)app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
    /**
     * 当前要隐藏哪几类。
     *
     * <p>开关语义与其它页一致：prefs 里存的是「显示」，所以 {@code visible==false} = 隐藏。
     * 总开关 {@link Spec#K_MAP_POI} 关着 ⇒ 返回全集（等价 2.2.6 的一刀切）。
     * 总开关开着 ⇒ 逐类读；一类都没关 ⇒ 返回空集，调用方据此完全不装文件映射（零开销）。
     */
    private static java.util.Set<String> hiddenCategories() {
        java.util.Set<String> all=new java.util.HashSet<String>();
        for (BaiduLabelSettings.Cat c:BaiduLabelSettings.CATS) all.add(c.key);
        all.add(BaiduLabelSettings.KEY_OTHER);
        if (!Cfg.visible(Spec.K_MAP_POI,Spec.defaultVisible(Spec.K_MAP_POI))) return all;
        java.util.Set<String> out=new java.util.HashSet<String>();
        for (BaiduLabelSettings.Cat c:BaiduLabelSettings.CATS) {
            if (!Cfg.visible(c.key,false)) out.add(c.key);
        }
        if (!Cfg.visible(BaiduLabelSettings.KEY_OTHER,false)) out.add(BaiduLabelSettings.KEY_OTHER);
        return out;
    }
    private static void disable() {
        if (nativePrepared) io.github.ldxm666.mapclean.NativeLabelAssets.prepareBaidu(new String[0],new String[0]);
        nativePrepared=false;
    }
    private static synchronized boolean prepare(Bundle options,Application app,java.util.Set<String> hidden) throws Exception {
        String cfg=options.getString("cfgdataroot");
        if (cfg==null) { disable(); return false; }
        File source=new File(cfg).getCanonicalFile();
        if (!source.isDirectory()) { disable(); return false; }
        List<File> styles=new ArrayList<File>();
        boolean main=false;
        for (File child:list(source)) {
            if (!mode(child)) continue;
            for (String name:STYLES) {
                File style=new File(child,name);
                if (!style.isFile()) continue;
                if (style.length()<1 || style.length()>16*1024*1024) throw new java.io.IOException("Invalid STY size");
                styles.add(style);
                if ("map.sty".equals(name)) main=true;
            }
        }
        if (!main || styles.size()>128) throw new java.io.IOException("Unsupported cfg style set");
        String key=sourceKey(styles,hidden);
        File base=new File(app.getCodeCacheDir(),"mcpoi").getCanonicalFile();
        mkdir(base);
        File output=new File(base,key.substring(0,32));
        if (!key.equals(cachedKey) || cachedDirectory==null || !complete(output,key,styles)) {
            if (!complete(output,key,styles)) {
                // Source reads also use libc. Remove our old mapping before
                // building a new generation so its input is always original STY.
                disable();
                List<byte[]> data=new ArrayList<byte[]>(); int rules=0, drawings=0;
                for (File style:styles) {
                    BaiduLabelStyle.Result result=BaiduLabelStyle.filterByCategories(read(style),hidden);
                    data.add(result.bytes); rules+=result.features; drawings+=result.drawings;
                }
                if (drawings==0) throw new java.io.IOException("No native point drawings");
                File temporary=new File(base,key.substring(0,32)+"-"+android.os.Process.myPid()+"-"+System.nanoTime());
                mkdir(temporary);
                for (int i=0;i<styles.size();i++) {
                    File destination=new File(temporary,relative(styles.get(i)));
                    mkdir(destination.getParentFile()); write(destination,data.get(i));
                }
                if (!key.equals(sourceKey(styles,hidden))) throw new java.io.IOException("Source STY changed during preparation");
                write(new File(temporary,".complete"),(SCHEMA+"\n"+key+"\n"+styles.size()+"\n"+rules+"\n"+drawings).getBytes(StandardCharsets.UTF_8));
                if (!temporary.renameTo(output) && !complete(output,key,styles)) throw new java.io.IOException("Cannot publish STY cache");
            }
            if (!complete(output,key,styles)) throw new java.io.IOException("Incomplete STY cache");
            String[] manifest=manifest(output);
            cachedRules=Integer.parseInt(manifest[3]); cachedDrawings=Integer.parseInt(manifest[4]);
            cachedKey=key; cachedDirectory=output;
            H.log("poi_style prepared themes="+styles.size()+" features="+cachedRules+" drawings="+cachedDrawings);
        }
        if (!key.equals(sourceKey(styles,hidden))) throw new java.io.IOException("Source STY changed before initialization");
        Map<String,String> paths=new TreeMap<String,String>();
        String resources=options.getString("stylerespath");
        for (File style:styles) {
            String destination=new File(cachedDirectory,relative(style)).getAbsolutePath();
            add(paths,style,destination,style);
            add(paths,new File(cfg,relative(style)),destination,style);
            if (resources!=null) add(paths,new File(resources,relative(style)),destination,style);
            String canonical=style.getCanonicalPath();
            String data="/data/data/"+MainHook.PKG_BMAP+"/", user="/data/user/0/"+MainHook.PKG_BMAP+"/";
            if (canonical.startsWith(data)) add(paths,new File(user+canonical.substring(data.length())),destination,style);
            else if (canonical.startsWith(user)) add(paths,new File(data+canonical.substring(user.length())),destination,style);
        }
        boolean accepted=io.github.ldxm666.mapclean.NativeLabelAssets.prepareBaidu(
                paths.keySet().toArray(new String[0]),paths.values().toArray(new String[0]));
        nativePrepared=accepted;
        if (!accepted) throw new java.io.IOException("Native exact-file interface unavailable");
        return true;
    }
    private static void add(Map<String,String> paths,File alias,String destination,File original) throws Exception {
        if (alias.isFile() && alias.getCanonicalFile().equals(original.getCanonicalFile()))
            paths.put(alias.getAbsolutePath(),destination);
    }
    private static String relative(File style) { return style.getParentFile().getName()+"/"+style.getName(); }
    private static boolean complete(File directory,String key,List<File> styles) {
        try {
            String[] manifest=manifest(directory);
            if (manifest.length!=5 || !SCHEMA.equals(manifest[0]) || !key.equals(manifest[1])
                    || Integer.parseInt(manifest[2])!=styles.size() || Integer.parseInt(manifest[3])<1 || Integer.parseInt(manifest[4])<1) return false;
            for (File style:styles) {
                File file=new File(directory,relative(style));
                if (!file.isFile() || file.length()<1 || file.length()>16*1024*1024
                        || !file.getCanonicalFile().equals(file.getAbsoluteFile())) return false;
            }
            return true;
        } catch (Exception error) { return false; }
    }
    private static String[] manifest(File directory) throws Exception {
        File marker=new File(directory,".complete");
        if (!marker.isFile() || marker.length()>256) throw new java.io.IOException("Invalid STY manifest");
        return new String(read(marker),StandardCharsets.UTF_8).split("\n");
    }
    /**
     * 缓存键 = SCHEMA + 每个源 STY 的 路径/大小/mtime + **当前隐藏的分类集合**。
     *
     * <p>最后一项是 2.2.7 补的。以前键里只有源文件，于是「改了开关」和「没改」算出来的键
     * 一模一样，直接复用旧缓存 —— 表现就是「开关拨了没反应」。分类按名字排序后参与摘要，
     * 集合不变则键不变，变了必然重建。
     */
    private static String sourceKey(List<File> styles,java.util.Set<String> hidden) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); fingerprint(digest,SCHEMA);
        java.util.List<String> cats=new java.util.ArrayList<String>(hidden);
        java.util.Collections.sort(cats);
        for (String cat:cats) fingerprint(digest,"cat="+cat);
        for (File style:styles) fingerprint(digest,style.getCanonicalPath()+":"+style.length()+":"+style.lastModified());
        StringBuilder out=new StringBuilder(); char[] hex="0123456789abcdef".toCharArray();
        for(byte b:digest.digest()){out.append(hex[(b&255)>>>4]);out.append(hex[b&15]);}return out.toString();
    }
    private static void fingerprint(MessageDigest digest,String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
    }
    private static File[] list(File directory) throws java.io.IOException {
        File[] out=directory.listFiles();
        if (out==null || out.length>1024) throw new java.io.IOException("Unsupported cfg directory");
        Arrays.sort(out,Comparator.comparing(File::getName));return out;
    }
    private static boolean mode(File file) {
        String name=file.getName();
        if (!file.isDirectory() || !name.startsWith("mode_") || name.length()==5) return false;
        for(int i=5;i<name.length();i++)if(name.charAt(i)<'0'||name.charAt(i)>'9')return false;return true;
    }
    private static byte[] read(File file) throws java.io.IOException {
        try(FileInputStream input=new FileInputStream(file)){
            ByteArrayOutputStream out=new ByteArrayOutputStream((int)file.length());
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){
                if(out.size()+count>16*1024*1024)throw new java.io.IOException("STY size changed");out.write(buffer,0,count);
            }return out.toByteArray();
        }
    }
    private static void write(File file,byte[] bytes) throws java.io.IOException {
        try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);output.getFD().sync();}
    }
    private static void mkdir(File directory) throws java.io.IOException {
        if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Cannot create STY cache");
    }
    private BaiduPoiLayer() {}
}
