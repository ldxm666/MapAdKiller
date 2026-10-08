package io.github.ldxm666.bmapclean;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;

/** Page-specific cardList assignments, never Array.prototype or generic JSON mutation. */
public final class BaiduPageScripts {
    private BaiduPageScripts() {}
    static String signature(){
        StringBuilder out=new StringBuilder();for(Spec.Cat c:Spec.cats())for(Spec.Row r:c.rows)
            if(r.key!=null)out.append(Cfg.visible(r.key,Spec.defaultVisible(r.key))?'1':'0');
        return out.toString();
    }
    public static String adapt(String module,String source){
        if("HomeFeed".equals(module))return filterFeed(source,
                Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ),
                Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ),
                Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ),Cfg.visible(Spec.K_HC));
        if(("userCore".equals(module)||"userCenter".equals(module))&&Spec.mineEnabled()){
            List<String> hidden=new ArrayList<>();
            add(hidden,Spec.K_MINE_GRID,"common");
            add(hidden,Spec.K_MINE_CAR,"car");
            add(hidden,Spec.K_MINE_VOICE,"voice","oldVoice");
            add(hidden,Spec.K_MINE_CARNAV,"carLogo");
            add(hidden,Spec.K_MINE_SPORT,"sport");
            add(hidden,Spec.K_MINE_BUILD,"contribution","ugc");
            add(hidden,Spec.K_MINE_OPS,"goldCoin","dxmFinance","userOperation","shop");
            add(hidden,Spec.K_MINE_AD,"gold","banner");
            return filterMine(source,hidden);
        }
        return source;
    }
    public static String filterFeed(String source,boolean chips,boolean content,boolean weather,boolean addresses){
        String marker="xL.kind=Xk.kind,";
        // Verified HomeFeed 1.0.432.1 feed-list store, including both network and cached datasets.
        if(!source.contains(marker)||!source.contains("key:\"handleFeedListNew\",value:function(t){"))return source;
        String filter="function(v){var k=v&&v.content_type;"
                +"if(k==='usual_address')return "+addresses+";"
                +"if(k==='travel_card'||k==='horizontal_top_card'||k==='feed_baikan_weather')return "+weather+";"
                +"return "+content+";}";
        String hook="!function(p){var set=p.set;function f(v){return Array.isArray(v)?v.filter("+filter+"):v;}"
                +"p.set=function(k,v){var a=Array.prototype.slice.call(arguments);"
                +"if(typeof k==='string'&&k.indexOf('feedDatas.')===0&&k.slice(-5)==='.list')a[1]=f(v);"
                +"else if(k==='feedDatas'&&v&&typeof v==='object'){var copy={};Object.keys(v).forEach(function(id){"
                +"var item=v[id];copy[id]=item&&typeof item==='object'?Object.assign({},item,{list:f(item.list)}):item;});a[1]=copy;}"
                +"return set.apply(this,a);};}(xL.prototype),";
        if(!content||!weather||!addresses)source=source.replace(marker,hook+marker);
        if(!chips)source=source.replace("s-if=\"isTabShow\"","s-if=\"false\"");
        return source;
    }
    static void add(List<String> out,String key,String...names){
        if(!Cfg.visible(key,Spec.defaultVisible(key)))java.util.Collections.addAll(out,names);
    }
    public static String filterMine(String source,List<String> hidden){
        if(hidden.isEmpty())return source;
        // The checked function names and bounded assignments are present in 1.0.92.1 and 1.1.61.1.
        if(!source.contains("processCardList:function(")||!source.contains("processServerData:function("))return source;
        Pattern p=Pattern.compile("(\\b(?:this|[a-zA-Z_$][\\w$]*)\\.data\\.set\\(\"cardList\",)(\\[\\]\\.concat\\([a-zA-Z_$][\\w$]*\\)|[a-zA-Z_$][\\w$]*)(\\))");
        Matcher m=p.matcher(source);StringBuffer out=new StringBuffer();
        String list=new JSONArray(hidden).toString();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(m.group(1)+m.group(2)
                +".filter(function(v){return "+list+".indexOf(v)<0;})"+m.group(3)));
        m.appendTail(out);
        String result=out.toString();
        // The initial list also renders before the server response (and when the request fails).
        Pattern initial=Pattern.compile("(cardList:)(\\[\\]\\.concat\\([a-zA-Z_$][\\w$]*\\))");
        m=initial.matcher(result);out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(m.group(1)+m.group(2)
                +".filter(function(v){return "+list+".indexOf(v)<0;})"));
        m.appendTail(out);return out.toString();
    }
}
