package io.github.ldxm666.mapadkiller;

import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** Complements base style filtering for typed POI updates; leaves map geography intact. */
final class AmapPoiLayer {
    private AmapPoiLayer() {}
    private static void filterPoints(Object data, boolean third) throws Exception {
        if (data == null || Config.visible(Config.K_MAP_POI)) return;
        Object value = data.getClass().getField("items").get(data);
        if (!(value instanceof List)) return;
        int removed=0;
        for (Object entry:(List<?>)value) {
            if(entry==null)continue;
            Object props=entry.getClass().getField("properties").get(entry);
            if(props==null)continue;
            Object style=third?props:props.getClass().getField("styleInfo").get(props);
            if(style==null)continue;
            int main=style.getClass().getField("mainKey").getInt(style);
            if(main==12024 || main==12029) {
                props.getClass().getField("visible").setBoolean(props,false);
                removed++;
            }
        }
        if(removed!=0)H.log("amap_poi_update hidden="+removed+" third="+third);
    }
    static void install(final ClassLoader cl) {
        Class<?> scene=H.cls(cl,"com.autonavi.jni.vmap.dsl.VMapSceneWrapper");
        Class<?> points=H.cls(cl,"com.autonavi.jni.vmap.dsl.MapSceneObjDef$StylePointInfos");
        Class<?> labels=H.cls(cl,"com.autonavi.jni.vmap.dsl.MapSceneObjDef$ThirdLabelInfos");
        if(points!=null)H.hookSig(scene,"nativeSetStylePointInfo","amap_poi_points",new XposedInterface.Hooker() {
            public Object intercept(XposedInterface.Chain chain)throws Throwable {
                filterPoints(chain.getArg(3),false);return chain.proceed();
            }
        },int.class,int.class,int.class,points);
        if(labels!=null)for(String method:new String[]{"nativeSetThirdLabelInfo","nativeSetUpdate3rdLabelsWithPoiData"})
            H.hookSig(scene,method,"amap_poi_"+method,new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain)throws Throwable {
                    filterPoints(chain.getArg(3),true);return chain.proceed();
                }
            },int.class,int.class,int.class,labels);
    }
}
