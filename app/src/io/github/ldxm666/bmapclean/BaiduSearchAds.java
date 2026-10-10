package io.github.ldxm666.bmapclean;

import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;

/** Search recommendations have two independent producers: the Mystique header
 * and PoiNoteListPresenter's RankingInfo row appended to historyDataList.
 * Remove only the verified ranking model/type and preserve unknown entries,
 * including cache reads when the page returns from search results.
 */
final class BaiduSearchAds {
    static void install(ClassLoader loader) {
        installRankingList(loader);
        android.content.pm.PackageInfo version=io.github.ldxm666.mapclean.Compatibility.packageInfo(loader,MainHook.PKG_BMAP);
        // These private UI methods and the advertctrl response path are
        // verified on 22.0.0 only. Unknown builds keep their original behavior.
        if(version!=null&&version.versionCode==1650)installSearchPromotions(loader);
        Class<?> presenter=H.cls(loader,"com.baidu.baidumaps.poi.newpoi.home.presenter.PoiRecommendHotWordPresenter");
        Class<?> template=H.cls(loader,"com.baidu.entity.pb.HwResult$ReciTemplate");
        BaiduHooks.hook(presenter,"onCreateView",chain -> {
            collapse(chain.getThisObject()); return null;
        });
        if (template!=null) BaiduHooks.hook(presenter,"handleRecommend",chain -> {
            collapse(chain.getThisObject()); return null;
        },template,boolean.class);
    }
    private static void installSearchPromotions(ClassLoader loader) {
        Class<?> home=H.cls(loader,"com.baidu.baidumaps.aihome.search.SearchPresenter");
        AtomicInteger hintLogs=new AtomicInteger();
        // Only advertctrl's box_txt JSONArray enters handleDatas(). Empty
        // data follows the host's own default hint/model reset and UI dispatch.
        BaiduHooks.hook(home,"handleDatas",chain -> {
            if(hintLogs.getAndIncrement()<4)H.log("event=baidu_search_promotion_drop source=box_txt");
            return chain.proceed(new Object[]{new JSONArray()});
        },JSONArray.class);

        Class<?> header=H.cls(loader,"com.baidu.baidumaps.poi.newpoi.home.presenter.PoiSearchNewNaHeaderPresenter");
        Class<?> manager=H.cls(loader,"com.baidu.baidumaps.poi.newpoi.home.presenter.PoiSearchNewNaHeaderPresenter$ViewManager");
        try {
            if(header==null||manager==null)return;
            Method addresses=header.getDeclaredMethod("refreshAddressCapsuleView");
            Field managerField=header.getDeclaredField("viewManager");
            Method guessCard=manager.getDeclaredMethod("l");
            if(addresses.getReturnType()!=void.class||managerField.getType()!=manager
                    ||guessCard.getReturnType()!=View.class)return;
            addresses.setAccessible(true);managerField.setAccessible(true);guessCard.setAccessible(true);
            AtomicInteger chipLogs=new AtomicInteger();
            // Reuse the native address builder for home/company/favorites and
            // saved addresses. Jingang category buttons are updated separately.
            BaiduHooks.hook(header,"refreshCapsuleView",chain -> {
                addresses.invoke(chain.getThisObject());
                if(chipLogs.getAndIncrement()<4)H.log("event=baidu_search_promotion_drop source=query_chips");
                return null;
            });
            // Address rendering calls this independent RcmdWordInfo card.
            // Collapse its exact getter; never touch the address/category rows.
            BaiduHooks.hook(header,"refreshGuessCard",chain -> {
                Object view=guessCard.invoke(managerField.get(chain.getThisObject()));
                if(view instanceof View)((View)view).setVisibility(View.GONE);
                return null;
            });
        }catch(ReflectiveOperationException ignored) {
            H.log("event=baidu_search_promotion_unsupported reason=header_schema");
        }
    }
    private static void installRankingList(ClassLoader loader) {
        Class<?> presenter=H.cls(loader,"com.baidu.baidumaps.poi.newpoi.home.presenter.PoiNoteListPresenter");
        Class<?> note=H.cls(loader,"com.baidu.baidumaps.poi.model.PoiNoteModel");
        Class<?> sug=H.cls(loader,"com.baidu.baidumaps.poi.model.PoiSugModel");
        Field type=null;
        try {
            if(note!=null&&note.getSuperclass()==sug) {
                Field candidate=sug.getDeclaredField("type");
                if(candidate.getType()==int.class) { candidate.setAccessible(true);type=candidate; }
            }
        }catch(ReflectiveOperationException ignored){}
        if(type==null) {
            H.log("event=baidu_hot_ranking_unsupported reason=model_schema");
            return;
        }
        Field rankingType=type;
        AtomicInteger logs=new AtomicInteger();
        // In Baidu 22.0.0, handleNoteData() supplies only RankingInfo as a
        // PoiNoteModel(type=18). The merger receives no search/history rows.
        BaiduHooks.hook(presenter,"addRecommendDataToHisList",chain -> {
            Object input=chain.getArg(0);
            Object output=withoutRanking(input,note,rankingType);
            logRankingDrop(logs,"merge",input,output);
            return input==output?chain.proceed():chain.proceed(new Object[]{output});
        },ArrayList.class);
        // PoiHistoryPresenter.initHistoryDataList() appends this cached feed
        // after constructing the actual history and its clear/more controls.
        BaiduHooks.hook(presenter,"getNoteDataList",chain -> {
            Object input=chain.proceed();
            Object output=withoutRanking(input,note,rankingType);
            logRankingDrop(logs,"cache",input,output);
            return output;
        });
    }
    private static Object withoutRanking(Object input,Class<?> note,Field type) throws IllegalAccessException {
        if(!(input instanceof List))return input;
        List<?> rows=(List<?>)input;
        ArrayList<Object> kept=null;
        for(int i=0;i<rows.size();i++) {
            Object row=rows.get(i);
            if(row!=null&&row.getClass()==note&&type.getInt(row)==18) {
                if(kept==null)kept=new ArrayList<>(rows.subList(0,i));
            }else if(kept!=null)kept.add(row);
        }
        return kept==null?input:kept;
    }
    private static void logRankingDrop(AtomicInteger logs,String source,Object input,Object output) {
        if(logs.getAndIncrement()<8)H.log("event=baidu_hot_ranking_drop source="+source
                +" dropped="+(input instanceof List&&output instanceof List
                ?((List<?>)input).size()-((List<?>)output).size():0));
    }
    private static void collapse(Object presenter) throws Exception {
        Object component=BaiduHooks.field(presenter,"component");
        Object list=BaiduHooks.field(component,"listPresenter");
        Object binding=BaiduHooks.field(list,"headerBinding");
        Object container=BaiduHooks.field(binding,"recHotWordContainer");
        if (!(container instanceof View)) return;
        View view=(View)container;
        view.setMinimumHeight(0);
        view.setVisibility(View.GONE);
        if (view instanceof ViewGroup && ((ViewGroup)view).getChildCount()!=0) ((ViewGroup)view).removeAllViews();
    }
    private BaiduSearchAds() {}
}
