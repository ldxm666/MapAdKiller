package io.github.ldxm666.mapadkiller;

import java.util.Arrays;

/** TEST-AMAP-POI-CATEGORY-235: production settings and native bit contract. */
public final class AmapPoiCategoryTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] ignored){
        check(AmapPoiSettings.KEYS.length==9 && AmapPoiSettings.TITLES.length==9,"nine business categories");
        for(String key:AmapPoiSettings.KEYS)check(Config.defaultVisible(key),"category defaults visible");
        check(!Config.defaultVisible(Config.K_MAP_POI),"existing master default preserved");
        check(AmapPoiSettings.mask(true,new boolean[0])==0,"missing category entries fail open");
        for(int combination=0;combination<512;combination++){
            boolean[] visible=new boolean[9];for(int i=0;i<9;i++)visible[i]=(combination&(1<<i))==0;
            boolean[] before=visible.clone();
            check(AmapPoiSettings.mask(true,visible)==(combination<<1),"independent native category bits "+combination);
            check(AmapPoiSettings.mask(false,visible)==1,"master off retains original all-POI behavior");
            check(Arrays.equals(before,visible),"computing mask does not change saved preferences");
        }
        System.out.println("AmapPoiCategoryTest: "+checks+" assertions passed");
    }
}
