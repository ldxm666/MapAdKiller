package io.github.ldxm666.bmapclean;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Desktop-compatible protocol tests plus an optional original-host STY fixture. */
public final class BaiduLabelStyleTest {
    private static int assertions;
    private static void check(boolean pass,String message) {
        assertions++; if(!pass)throw new AssertionError(message);
    }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] part:parts)out.write(part,0,part.length);return out.toByteArray();
    }
    private static byte[] var(long n) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        do{int b=(int)(n&127);n>>>=7;out.write(n==0?b:b|128);}while(n!=0);
        return out.toByteArray();
    }
    private static byte[] number(int k,long n){return concat(var(k<<3),var(n));}
    private static byte[] message(int k,byte[] bytes){return concat(var((k<<3)|2),var(bytes.length),bytes);}
    private static final class Field {
        int k,w; long v;byte[] payload,raw;
    }
    private static long readVar(byte[] source,int[] pos){
        long n=0;for(int i=0;i<10;i++){int b=source[pos[0]++]&255;n|=(long)(b&127)<<(7*i);if(b<128)return n;}
        throw new AssertionError("bad test protobuf");
    }
    private static List<Field> fields(byte[] source) {
        List<Field> list=new ArrayList<Field>();int[] pos={0};
        while(pos[0]<source.length){int start=pos[0];long tag=readVar(source,pos);Field f=new Field();f.k=(int)(tag>>>3);f.w=(int)(tag&7);
            if(f.w==0)f.v=readVar(source,pos);
            else if(f.w==2){int size=(int)readVar(source,pos);f.payload=Arrays.copyOfRange(source,pos[0],pos[0]+size);pos[0]+=size;}
            else pos[0]+=f.w==1?8:4;
            f.raw=Arrays.copyOfRange(source,start,pos[0]);list.add(f);
        }return list;
    }
    private static byte[] first(byte[] source,int k){for(Field f:fields(source))if(f.k==k&&f.w==2)return f.payload;throw new AssertionError("missing test field");}
    private static long id(byte[] bytes){for(Field f:fields(bytes))if(f.k==1&&f.w==0)return f.v;throw new AssertionError("missing ID");}
    private static Set<Long> drawings(byte[] rule){
        Set<Long> out=new HashSet<Long>();for(Field f:fields(rule))if(f.k==2){
            if(f.w==0)out.add(f.v);else if(f.w==2){int[] p={0};while(p[0]<f.payload.length)out.add(readVar(f.payload,p));}
        }return out;
    }
    private static int drawingCount(byte[] rule){
        int count=0;for(Field f:fields(rule))if(f.k==2){
            if(f.w==0)count++;
            else if(f.w==2){int[] p={0};while(p[0]<f.payload.length){readVar(f.payload,p);count++;}}
        }return count;
    }
    private static Map<Long,byte[]> rules(byte[] level){Map<Long,byte[]> map=new HashMap<Long,byte[]>();for(Field f:fields(level))if(f.k==1&&f.w==2)map.put(id(f.payload),f.payload);return map;}
    private static Set<Long> pointDrawings(byte[] source){
        Set<Long> set=new HashSet<Long>();for(Field f:fields(first(source,3)))if((f.k==1||f.k==2)&&f.w==2)set.add(id(f.payload));return set;
    }
    private static byte[] scene(byte[] level,byte[] definitions){return concat(message(1,number(1,123)),message(2,level),message(3,definitions),number(19,471));}
    private static void rejected(byte[] bytes,String reason){
        boolean thrown=false;try{BaiduLabelStyle.hideAll(bytes);}catch(IllegalArgumentException expected){thrown=true;}
        check(thrown,reason);
    }
    public static void main(String[] args)throws Exception{
        byte[] definitions=concat(message(1,number(1,101)),message(2,number(1,102)),message(4,number(1,103)),message(5,number(1,104)),message(8,number(5,57)));
        byte[] hotel=concat(number(1,116),number(2,101),number(2,102),number(7,987));
        byte[] city=concat(number(1,30),number(2,101),number(2,102));
        byte[] street=concat(number(1,58),number(2,102),number(2,103));
        byte[] unknown=concat(number(1,9999999),number(2,101),number(2,102));
        byte[] mixed=concat(number(1,9999998),number(2,101),number(2,103),number(2,104));
        byte[] packed=concat(number(1,9999997),message(2,concat(var(101),var(103),var(102))),number(8,678));
        byte[] level=concat(message(1,hotel),message(1,city),message(1,street),message(1,unknown),message(1,mixed),message(1,packed),number(6,75));
        byte[] source=scene(level,definitions);
        BaiduLabelStyle.Result result=BaiduLabelStyle.hideAll(source);
        Map<Long,byte[]> filtered=rules(first(result.bytes,2));
        check(filtered.size()==6,"feature entries remain present, including hidden POIs");
        check(drawings(filtered.get(116L)).equals(new HashSet<Long>(Arrays.asList(0xffffffffL))),"hotel uses native no-draw sentinel");
        check(Arrays.equals(filtered.get(30L),city),"city rule byte-for-byte preserved with shared drawing IDs");
        check(Arrays.equals(filtered.get(58L),street),"street drawing and label preserved");
        check(drawings(filtered.get(9999999L)).equals(new HashSet<Long>(Arrays.asList(0xffffffffL))),"new unlisted POI IDs use host point schema");
        check(drawings(filtered.get(9999998L)).equals(new HashSet<Long>(Arrays.asList(0xffffffffL,103L,104L))),"geometry in mixed features preserved");
        check(drawings(filtered.get(9999997L)).equals(new HashSet<Long>(Arrays.asList(0xffffffffL,103L))),"packed IDs preserve non-point references");
        for(byte[] original:Arrays.asList(hotel,city,street,unknown,mixed,packed))
            check(drawingCount(original)==drawingCount(filtered.get(id(original))),"native draw array cardinality retained");
        check(Arrays.equals(first(result.bytes,3),definitions),"shared definitions and unknown drawing types untouched");
        check(Arrays.equals(first(result.bytes,1),first(source,1)),"global palette untouched");
        check(result.levels==1&&result.features==4&&result.drawings==7,"transformation counts match actual removed references");
        check(BaiduLabelStyle.filter(source,new HashSet<Integer>())==source,"empty selection preserves original byte array");
        Set<Integer> hotels=new HashSet<Integer>(Arrays.asList(116));
        Map<Long,byte[]> selected=rules(first(BaiduLabelStyle.filter(source,hotels),2));
        check(Arrays.equals(selected.get(9999999L),unknown),"explicit filter preserves unselected features");
        check(drawings(selected.get(116L)).equals(new HashSet<Long>(Arrays.asList(0xffffffffL))),"explicit filter disables selected point drawings");
        check(Arrays.equals(result.bytes,BaiduLabelStyle.hideAll(result.bytes).bytes),"filter is idempotent");
        rejected(Arrays.copyOf(source,source.length-1),"truncated input rejected");
        rejected(concat(source,new byte[]{(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,2}),"64-bit overflow rejected");
        rejected(scene(message(1,concat(number(1,116),number(1,117))),definitions),"ambiguous feature identity rejected");
        rejected(concat(message(2,level),message(3,definitions),message(3,definitions)),"conflicting drawing tables rejected");
        rejected(concat(message(1,number(1,123)),message(2,level)),"missing native drawing table rejected");
        if(args.length>0 && args[0].endsWith(".apk")){
            try(java.util.zip.ZipFile apk=new java.util.zip.ZipFile(args[0])){
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries=apk.entries();int themes=0;
                while(entries.hasMoreElements()){
                    java.util.zip.ZipEntry entry=entries.nextElement();String name=entry.getName();
                    if(!name.startsWith("assets/cfg/a/mode_"))continue;
                    String leaf=name.substring(name.lastIndexOf('/')+1);
                    if(!Arrays.asList("map.sty","traffic.sty","weakmap.sty","reduct.sty","weaktraffic.sty","weakreduct.sty").contains(leaf))continue;
                    ByteArrayOutputStream fixture=new ByteArrayOutputStream();
                    try(java.io.InputStream in=apk.getInputStream(entry)){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)fixture.write(block,0,n);}
                    byte[] original=fixture.toByteArray();
                    BaiduLabelStyle.Result r=BaiduLabelStyle.hideAll(original);
                    check(Arrays.equals(first(original,1),first(r.bytes,1)),name+" global config preserved");
                    check(Arrays.equals(first(original,3),first(r.bytes,3)),name+" drawing definitions preserved");
                    check(Arrays.equals(r.bytes,BaiduLabelStyle.hideAll(r.bytes).bytes),name+" idempotent");
                    themes++;
                    System.out.println(name+" levels="+r.levels+" features="+r.features+" drawings="+r.drawings);
                }
                check(themes>=3,"multiple original map themes checked");
            }
            System.out.println("PASS "+assertions+" Baidu native theme assertions");return;
        }
        if(args.length>0){
            byte[] fixture=Files.readAllBytes(Paths.get(args[0]));long at=System.nanoTime();
            BaiduLabelStyle.Result actual=BaiduLabelStyle.hideAll(fixture);
            check(actual.levels==25,"original host retains all 25 zoom levels");
            check(actual.features>1000&&actual.drawings>2000,"original host point references were filtered");
            check(Arrays.equals(first(fixture,3),first(actual.bytes,3)),"original drawing styles fully preserved");
            check(Arrays.equals(first(fixture,1),first(actual.bytes,1)),"original native global config preserved");
            Set<Long> points=pointDrawings(fixture),preserved=new HashSet<Long>();
            for(int i:BaiduLabelSettings.PRESERVED_FEATURES)preserved.add((long)i);
            List<byte[]> oldLevels=new ArrayList<byte[]>(),newLevels=new ArrayList<byte[]>();
            for(Field f:fields(fixture))if(f.k==2)oldLevels.add(f.payload);
            for(Field f:fields(actual.bytes))if(f.k==2)newLevels.add(f.payload);
            for(int zoom=0;zoom<oldLevels.size();zoom++){
                Map<Long,byte[]> old=rules(oldLevels.get(zoom)),now=rules(newLevels.get(zoom));
                check(old.keySet().equals(now.keySet()),"zoom "+zoom+" feature identities retained");
                for(Long feature:old.keySet()){
                    check(drawingCount(old.get(feature))==drawingCount(now.get(feature)),"native draw array cardinality preserved "+feature);
                    if(preserved.contains(feature))check(Arrays.equals(old.get(feature),now.get(feature)),"geographic rule preserved "+feature);
                    else {Set<Long> remaining=drawings(now.get(feature));remaining.retainAll(points);check(remaining.isEmpty(),"POI point references removed "+feature);}
                }
            }
            if(args.length>1)Files.write(Paths.get(args[1]),actual.bytes);
            System.out.println("Fixture: levels="+actual.levels+" features="+actual.features+" drawings="+actual.drawings+" milliseconds="+((System.nanoTime()-at)/1000000));
        }
        System.out.println("PASS "+assertions+" Baidu native style assertions");
    }
}
