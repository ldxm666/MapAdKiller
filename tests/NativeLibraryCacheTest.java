package io.github.ldxm666.mapclean;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** TEST-CACHE-NATIVE-234: equal APK/library sizes must never select stale native code. */
public final class NativeLibraryCacheTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static byte[] read(File f)throws Exception{return Files.readAllBytes(f.toPath());}
    private static File zip(File root,String name,byte[] bytes)throws Exception{
        File f=new File(root,name);
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))){
            CRC32 crc=new CRC32();crc.update(bytes);
            ZipEntry e=new ZipEntry("lib/x86_64/libmapclean_labels.so");e.setMethod(ZipEntry.STORED);e.setSize(bytes.length);e.setCrc(crc.getValue());e.setTime(0);
            out.putNextEntry(e);out.write(bytes);out.closeEntry();
        }
        return f;
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory(Paths.get("/data/local/tmp"),"mapclean-native-test-").toFile(),cache=new File(root,"cache");check(cache.mkdir(),"cache created");
        try{
            byte[] a=new byte[4096],b=new byte[4096];Arrays.fill(a,(byte)41);Arrays.fill(b,(byte)42);
            File za=zip(root,"a.apk",a),zb=zip(root,"b.apk",b);byte[] originalA=read(za),originalB=read(zb);
            check(za.length()==zb.length(),"regression fixtures have identical APK lengths");
            try(ZipFile az=new ZipFile(za);ZipFile bz=new ZipFile(zb)){
                File one=NativeLibraryCache.extract(az,"lib/x86_64/libmapclean_labels.so",cache,"x86_64");
                File two=NativeLibraryCache.extract(bz,"lib/x86_64/libmapclean_labels.so",cache,"x86_64");
                check(!one.equals(two),"same-size changed native library uses different path");
                check(Arrays.equals(read(one),a)&&Arrays.equals(read(two),b),"each path contains its own native bytes");
                long timestamp=one.lastModified();
                check(one.equals(NativeLibraryCache.extract(az,"lib/x86_64/libmapclean_labels.so",cache,"x86_64")),"unchanged bytes reuse path");
                check(timestamp==one.lastModified(),"valid cache is not rewritten");
                check(one.setWritable(true,true),"test can corrupt owned cache");Files.write(one.toPath(),b);
                check(one.equals(NativeLibraryCache.extract(az,"lib/x86_64/libmapclean_labels.so",cache,"x86_64")),"corrupt equal-size cache repairs same content address");
                check(Arrays.equals(read(one),a),"cache content verified rather than length only");
                try{NativeLibraryCache.extract(az,"missing",cache,"x86_64");throw new AssertionError("missing entry accepted");}catch(Exception expected){check(expected instanceof IOException,"missing entry rejected as IO failure");}
                try{NativeLibraryCache.extract(az,"lib/x86_64/libmapclean_labels.so",cache,"../escape");throw new AssertionError("invalid ABI accepted");}catch(IllegalArgumentException expected){checks++;}
            }
            check(Arrays.equals(read(za),originalA)&&Arrays.equals(read(zb),originalB),"original APKs untouched");
            String[] leftovers=cache.list((dir,name)->name.endsWith(".tmp"));check(leftovers!=null&&leftovers.length==0,"no partial native files remain");
        }finally{
            File[] cached=cache.listFiles();if(cached!=null)for(File f:cached){f.setWritable(true,true);f.delete();}
            File[] files=root.listFiles();if(files!=null)for(File f:files)f.delete();root.delete();
        }
        System.out.println("NativeLibraryCacheTest: "+checks+" assertions passed");
    }
}
