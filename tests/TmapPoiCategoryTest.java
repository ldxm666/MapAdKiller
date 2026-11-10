package io.github.ldxm666.tmapclean;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** TEST-TMAP-POI-CATEGORY-235: production parser, style masks and private copies. */
public final class TmapPoiCategoryTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void write(File f,String value)throws Exception{Files.write(f.toPath(),value.getBytes(StandardCharsets.UTF_8));}
    private static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
    // A small synthetic FlatBuffer with the exact recovered field layout. IDs and
    // resource names below are from Tencent 11.7.0's original point-style table.
    private static final class Flat {
        final ByteBuffer b=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);int pos=4;
        int alloc(int size){int p=pos;pos+=size;return p;}
        int table(int count){int v=alloc(4+count*2),t=alloc(4+count*4);b.putShort(v,(short)(4+count*2));b.putShort(v+2,(short)(4+count*4));b.putInt(t,t-v);for(int i=0;i<count;i++)b.putShort(v+4+i*2,(short)(4+i*4));return t;}
        int field(int t,int i){return t+4+i*4;}
        void num(int t,int i,int value){b.putInt(field(t,i),value);}
        void ref(int p,int target){b.putInt(p,target-p);}
        int vector(int size){int p=alloc(4+size*4);b.putInt(p,size);return p;}
        int string(String text){byte[] s=text.getBytes(StandardCharsets.UTF_8);int p=alloc(5+s.length);b.putInt(p,s.length);for(int i=0;i<s.length;i++)b.put(p+4+i,s[i]);return p;}
        byte[] build(boolean mismatch){
            int root=table(3);b.putInt(0,root);num(root,0,12000000);int groups=vector(2);ref(field(root,2),groups);
            for(int g=0;g<2;g++){
                int group=table(6);ref(groups+4+g*4,group);int images=vector(4);ref(field(group,2),images);
                String[] names={"45.png",mismatch&&g==1?"32.png":"25.png","126.png","unknown.png"};
                for(int i=0;i<names.length;i++){int image=table(3);ref(images+4+i*4,image);num(image,0,40+i);int name=string(names[i]);ref(field(image,1),name);ref(field(image,2),name);}
                int points=vector(4);ref(field(group,5),points);
                int[] ids={65595,65581,65584,65999};
                for(int i=0;i<4;i++){int p=table(3);ref(points+4+i*4,p);num(p,0,ids[i]);int rows=vector(1);ref(field(p,2),rows);int row=table(14);ref(rows+4,row);num(row,0,0);num(row,1,20);num(row,3,40+i);num(row,4,263201);}
            }
            return Arrays.copyOf(b.array(),pos);
        }
    }
    private static void remove(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)remove(c);if(!f.delete()&&f.exists())throw new AssertionError("test cleanup "+f.getName());}
    public static void main(String[] args)throws Exception{
        if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();
        check(TmapPoiStyles.KEYS.length==9&&TmapPoiStyles.TITLES.length==9,"nine independent business categories");
        for(String key:TmapPoiStyles.KEYS){check(Spec.defaultVisible(key),"category default visible "+key);check(key.startsWith("ui_"),"category enters live config refresh "+key);}
        check(TmapPoiStyles.category("45.png")==0,"restaurant icon");
        check(TmapPoiStyles.category("32.png")==1,"shopping icon");
        check(TmapPoiStyles.category("25.png")==2,"medical icon");
        check(TmapPoiStyles.category("126.png")==3,"education icon");
        check(TmapPoiStyles.category("300.png")==3,"teaching-building education icon");
        check(TmapPoiStyles.category("20.png")==4,"transport icon");
        check(TmapPoiStyles.category("26.png")==5,"hotel icon");
        check(TmapPoiStyles.category("24.png")==6,"scenic icon");
        check(TmapPoiStyles.category("65.png")==7,"bank icon");
        check(TmapPoiStyles.category("43.png")==8,"service icon");
        for(String icon:new String[]{null,"new.png","unknown.png","mapcfg_Country.png","0.png","2.png","45","45.png.png"})
            check(TmapPoiStyles.category(icon)==-1,"unknown/geographic icon stays visible");
        byte[] data=new Flat().build(false);Map<Integer,Integer> cat=TmapPoiStyles.catalogue(data);
        check(cat.size()==3,"only three recognized point styles");
        check(cat.get(65595)==0&&cat.get(65581)==2&&cat.get(65584)==3,"actual point->icon->category chain");
        check(!TmapPoiStyles.catalogue(new Flat().build(true)).containsKey(65581),"day/night category disagreement stays visible");
        for(int n=0;n<data.length;n+=13)check(TmapPoiStyles.catalogue(Arrays.copyOf(data,n)).isEmpty(),"truncated FlatBuffer fails open");
        byte[] bad=data.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0,Integer.MAX_VALUE);
        check(TmapPoiStyles.catalogue(bad).isEmpty(),"overflowing root offset fails open");
        Random random=new Random(235);for(int i=0;i<100;i++){byte[] junk=new byte[32+random.nextInt(4096)];random.nextBytes(junk);check(TmapPoiStyles.catalogue(junk).isEmpty(),"unknown format fails open");}
        String original="{\"version\":\"1.0\",\"mode\":\"night\",\"model\":{\"regex\":\"light_night\"},\"base\":{\"color\":{\"131773\":[{\"value\":123}]},\"disable\":{\"196621,196627\":[{\"range\":[0,25],\"disable\":1}]}},\"rich\":{\"native\":true},\"future\":{\"unchanged\":9}}";
        JSONObject baseline=new JSONObject(original);
        Map<Integer,Integer> all=new LinkedHashMap<>();for(int i=0;i<9;i++)all.put(65536+100+i,i);
        all.put(131773,0);all.put(263201,0);all.put(65599,99); // hostile/non-point entries must be ignored.
        for(int mask=0;mask<512;mask++){
            String result=TmapPoiStyles.filter(original,all,mask);
            if(mask==0){check(result==null,"all-visible uses exact original path");continue;}
            JSONObject filtered=new JSONObject(result),base=filtered.getJSONObject("base"),disable=base.getJSONObject("disable");
            for(int i=0;i<9;i++)check(disable.has(Integer.toString(65536+100+i))==((mask&(1<<i))!=0),"independent category mask "+mask+"/"+i);
            check(!disable.has("131773")&&!disable.has("263201")&&!disable.has("65599"),"never disable roads/shared font/unknown category");
            check(base.getJSONObject("color").toString().equals(baseline.getJSONObject("base").getJSONObject("color").toString()),"road colors preserved");
            check(disable.getJSONArray("196621,196627").toString().equals(baseline.getJSONObject("base").getJSONObject("disable").getJSONArray("196621,196627").toString()),"native disabled styles preserved");
            for(String key:new String[]{"mode","model","rich","future"})check(filtered.get(key).toString().equals(baseline.get(key).toString()),"original style property preserved: "+key);
        }
        check(TmapPoiStyles.filter("{}",all,1)==null,"unrecognized JSON schema retained");
        check(TmapPoiStyles.filter("{broken",all,1)==null,"malformed style retained");
        check(TmapPoiStyles.filter("{\"version\":\"1.0\",\"base\":{\"disable\":{ // native style comment\n},},}",all,1)!=null,"host JSON comments/trailing commas normalized");
        JSONObject relaxed=TmapPoiStyles.parseStyle("{\"literal\":\"https://res/a,}/*x*/\\\"\",/* native */\"array\":[1,2,],}");
        check(relaxed.getString("literal").equals("https://res/a,}/*x*/\""),"quoted punctuation/comments/escape preserved");
        check(relaxed.getJSONArray("array").length()==2,"trailing array comma does not invent null element");
        check(TmapPoiStyles.filter("{\"version\":\"1.0\",\"base\":{},/* unterminated",all,1)==null,"unfinished comment stays native");
        check(TmapPoiStyles.filter("{\"version\":\"1.0\",\"base\":{\"disable\":[]}}",all,1)==null,"unknown disable schema retained");
        check(new JSONObject(original).toString().equals(baseline.toString()),"source JSON untouched");
        File sandbox=new File("/data/local/tmp/mapclean-poi-categories-test-"+android.os.Process.myPid());
        if(!sandbox.mkdirs())throw new IOException("test sandbox unavailable");
        try{
            final File files=new File(sandbox,"files"),cache=new File(sandbox,"cache");new File(files,"config").mkdirs();cache.mkdirs();
            Files.write(new File(files,"config/mapconfig_crc.bin").toPath(),data);
            File source=new File(sandbox,"native-style");source.mkdirs();
            File nativeConfig=new File(source,"stylization_config.json"),nativeStyle=new File(source,"stylized.json");
            String config="{\"layers\":{\"stylizd_mpcfg\":{\"enable\":true,\"path\":\"stylized.json\"},\"lighting_config\":{\"lighting_enable\":true}}}";
            write(nativeConfig,config);write(nativeStyle,original);write(new File(source,"texture.bin"),"native-resource");
            Context context=new ContextWrapper(null){public File getFilesDir(){return files;}public File getCacheDir(){return cache;}};
            Method prepare=TmapPoiCategories.class.getDeclaredMethod("prepare",Context.class,String.class,int.class);prepare.setAccessible(true);
            String path=(String)prepare.invoke(TmapPoiCategories.INSTANCE,context,source.getPath(),1);
            check(path!=null&&!new File(path).getCanonicalFile().equals(source.getCanonicalFile()),"native files copied to private cache");
            check(read(nativeStyle).equals(original)&&read(nativeConfig).equals(config),"host files byte-for-byte unchanged");
            check(read(new File(path,"stylization_config.json")).equals(config),"lighting/config copied exactly");
            check(read(new File(path,"texture.bin")).equals("native-resource"),"resource copied exactly");
            JSONObject disable=new JSONObject(read(new File(path,"stylized.json"))).getJSONObject("base").getJSONObject("disable");
            check(disable.has("65595")&&!disable.has("65581")&&!disable.has("65584"),"only dining style disabled in native derivative");
            check(path.equals(prepare.invoke(TmapPoiCategories.INSTANCE,context,source.getPath(),1)),"identical requests reuse derivative");
            String second=(String)prepare.invoke(TmapPoiCategories.INSTANCE,context,source.getPath(),1<<2);
            check(!path.equals(second),"independent mask gets new native load path");
            check(prepare.invoke(TmapPoiCategories.INSTANCE,context,source.getPath(),1<<8)==null,"unsupported category in a skin stays native");
            write(nativeConfig,"{\"layers\":{\"stylizd_mpcfg\":{\"enable\":true,\"path\":\"../outside.json\"}}}");write(new File(sandbox,"outside.json"),original);
            check(prepare.invoke(TmapPoiCategories.INSTANCE,context,source.getPath(),1)==null,"style path traversal rejected");
        }finally{remove(sandbox);}
        System.out.println("TmapPoiCategoryTest: "+checks+" assertions passed");
    }
}
