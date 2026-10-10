package io.github.ldxm666.bmapclean;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Native STY level tables: identify point icons/text from the host's own drawing
 * definitions, retain feature identities and non-point drawing references.
 * Hidden references use the host's existing uint32 -1 sentinel. Native readers
 * expect a draw array for every feature; an empty array is not an equivalent rule.
 * Definition, geometry, palette and unknown fields remain unchanged.
 */
final class BaiduLabelStyle {
    private static final long NO_DRAW=0xffffffffL;
    static final class Result {
        final byte[] bytes;
        final int levels, features, drawings;
        Result(byte[] bytes, int levels, int features, int drawings) {
            this.bytes=bytes; this.levels=levels; this.features=features; this.drawings=drawings;
        }
    }
    private static final Set<Integer> PRESERVED = preserved();
    private static Set<Integer> preserved() {
        Set<Integer> out=new HashSet<Integer>();
        for (int id:BaiduLabelSettings.PRESERVED_FEATURES) out.add(id);
        return Collections.unmodifiableSet(out);
    }
    static Set<Integer> hiddenFeatures(int mask) {
        Set<Integer> out=new HashSet<Integer>();
        if ((mask&1)!=0) for (int id:BaiduLabelSettings.FEATURES[0]) out.add(id);
        return out;
    }
    static byte[] filter(byte[] source, Set<Integer> hidden) {
        if (hidden.isEmpty()) return source;
        return transform(source,hidden).bytes;
    }
    static Result hideAll(byte[] source) { return transform(source,null); }
    private static Result transform(byte[] source, Set<Integer> selected) {
        if (source==null || source.length>16*1024*1024) throw bad("Invalid STY size");
        Cursor root=new Cursor(source);
        Set<Long> points=new HashSet<Long>();
        Map<Long,Integer> kinds=new HashMap<Long,Integer>();
        int definitions=0, levels=0;
        while (root.next()) {
            if (root.number==2 && root.wire==2) levels++;
            if (root.number==3 && root.wire==2) {
                definitions++;
                Cursor draws=new Cursor(root.payload());
                while (draws.next()) {
                    if (draws.wire!=2 || draws.number<1 || draws.number>7) continue;
                    Cursor style=new Cursor(draws.payload());
                    Long identity=null;
                    while (style.next()) if (style.number==1 && style.wire==0) {
                        if (identity!=null) throw bad("Duplicate STY drawing identity");
                        identity=style.value;
                    }
                    if (identity==null) throw bad("Missing STY drawing identity");
                    Integer previous=kinds.put(identity,draws.number);
                    if (previous!=null && previous.intValue()!=draws.number) throw bad("Conflicting STY drawing type");
                    if (draws.number==1 || draws.number==2) points.add(identity);
                }
            }
        }
        if (definitions!=1 || levels<1 || levels>64) throw bad("Unsupported STY scene schema");
        int[] counts={0,0};
        root=new Cursor(source);
        ByteArrayOutputStream out=new ByteArrayOutputStream(source.length);
        while (root.next()) {
            if (root.number==2 && root.wire==2) message(out,2,filterLevel(root.payload(),points,selected,counts));
            else root.copy(out);
        }
        return new Result(counts[1]==0?source:out.toByteArray(),levels,counts[0],counts[1]);
    }
    private static byte[] filterLevel(byte[] source, Set<Long> points, Set<Integer> selected, int[] counts) {
        Cursor level=new Cursor(source);
        ByteArrayOutputStream out=new ByteArrayOutputStream(source.length);
        while (level.next()) {
            if (level.number==1 && level.wire==2) {
                byte[] original=level.payload(), filtered=filterRule(original,points,selected,counts);
                if (filtered==original) level.copy(out); else message(out,1,filtered);
            } else level.copy(out);
        }
        return out.toByteArray();
    }
    private static byte[] filterRule(byte[] source, Set<Long> points, Set<Integer> selected, int[] counts) {
        Cursor rule=new Cursor(source);
        Integer feature=null;
        while (rule.next()) if (rule.number==1 && rule.wire==0) {
            if (feature!=null || rule.value<0 || rule.value>0xffffffffL) throw bad("Invalid STY feature identity");
            feature=(int)rule.value;
        }
        if (feature==null) throw bad("Missing STY feature identity");
        if (PRESERVED.contains(feature) || (selected!=null && !selected.contains(feature))) return source;
        rule=new Cursor(source);
        ByteArrayOutputStream out=new ByteArrayOutputStream(source.length);
        int removed=0;
        while (rule.next()) {
            if (rule.number==2 && rule.wire==0 && points.contains(rule.value)) {
                removed++;
                writeVarint(out,2<<3); writeVarint(out,NO_DRAW);
            }
            else if (rule.number==2 && rule.wire==2) {
                byte[] packed=rule.payload();
                Cursor values=new Cursor(packed);
                ByteArrayOutputStream keep=new ByteArrayOutputStream(packed.length);
                int before=removed;
                while (values.pos<packed.length) {
                    int start=values.pos;
                    long value=values.varint();
                    if (points.contains(value)) { removed++; writeVarint(keep,NO_DRAW); }
                    else keep.write(packed,start,values.pos-start);
                }
                if (before==removed) rule.copy(out);
                else if (keep.size()!=0) message(out,2,keep.toByteArray());
            } else rule.copy(out);
        }
        if (removed==0) return source;
        counts[0]++; counts[1]+=removed;
        return out.toByteArray();
    }
    private static void message(ByteArrayOutputStream out, int number, byte[] data) {
        writeVarint(out,(number<<3)|2); writeVarint(out,data.length); out.write(data,0,data.length);
    }
    private static void writeVarint(ByteArrayOutputStream out, long value) {
        do { int b=(int)(value&127); value>>>=7; out.write(value==0?b:b|128); } while(value!=0);
    }
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException(why); }
    private static final class Cursor {
        final byte[] bytes;
        int pos, start, payloadStart, payloadSize, number, wire;
        long value;
        Cursor(byte[] bytes) { this.bytes=bytes; }
        long varint() {
            long value=0;
            for (int i=0;i<10;i++) {
                if (pos>=bytes.length) throw bad("Truncated STY varint");
                int b=bytes[pos++]&255;
                if (i==9 && b>1) throw bad("Overflowed STY varint");
                value|=(long)(b&127)<<(i*7);
                if (b<128) return value;
            }
            throw bad("Invalid STY varint");
        }
        boolean next() {
            if (pos==bytes.length) return false;
            start=pos;
            long tag=varint();
            if (tag<=0 || tag>0xffffffffL || (tag>>>3)>0x1fffffffL) throw bad("Invalid STY field");
            number=(int)(tag>>>3); wire=(int)(tag&7);
            if (number==0) throw bad("Invalid STY field number");
            if (wire==0) value=varint();
            else if (wire==2) {
                long size=varint();
                if (size<0 || size>bytes.length-pos) throw bad("Truncated STY payload");
                payloadStart=pos; payloadSize=(int)size; pos+=payloadSize;
            } else if (wire==1 || wire==5) {
                int size=wire==1?8:4;
                if (size>bytes.length-pos) throw bad("Truncated STY fixed field");
                pos+=size;
            } else throw bad("Unsupported STY wire type");
            return true;
        }
        byte[] payload() { return java.util.Arrays.copyOfRange(bytes,payloadStart,payloadStart+payloadSize); }
        void copy(ByteArrayOutputStream out) { out.write(bytes,start,pos-start); }
    }
    private BaiduLabelStyle() {}
}
