package io.github.ldxm666.mapadkiller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** More Tools catalog from AMap 17.00.0.2005. Matching uses IDs, never displayed text.
 * Host catalog data takes priority; these native routes also work before the first catalog fetch.
 */
public final class AmapToolCatalog {
    // id, display name, category, native schema, icon URL. Contains no account or location data.
    public static final String[][] ENTRIES = {
        {"113", "景点游玩", "高德精选", "amapuri://scenicChannel/ScenicPortal?pathid=maintool&needTabFooterBar=1&atLeastVersion=22.84.50.1", "https://gw.alicdn.com/imgextra/i4/O1CN01fOpb1dKA0TL0Gzew_!!6000000003952-2-tps-135-135.png"},
        {"302", "充电站", "高德精选", "amapuri://search/general?keyword=%E5%85%85%E7%94%B5%E7%AB%99&chinfo=ch_link_mastermap_toolkit_forward&superid=q_72", "https://gw.alicdn.com/imgextra/i3/O1CN01YATbd8usNsD0Gzew_!!6000000001603-2-tps-135-135.png"},
        {"168", "租车", "高德精选", "amapuri://webview/amaponline?url=https%3A%2F%2Fm.hellobike.com%2FAppRentCarNH5%2Flatest%2Fpr_index_pages_index_index.html%23%2Fpages%2Findex%2Findex%3Fc1%3DO3%26c3%3D410%26adSource%3Dduanwai_gaode_shouye_jingangwei%26source%3Damap&forbid_show_loading=1&hide_title=1&disable_amap_ua=1", "https://gw.alicdn.com/imgextra/i2/O1CN01Q5OBUfnKlyH0Gzew_!!6000000003111-2-tps-135-135.png"},
        {"114", "语音包设置", "高德精选", "amapuri://dialect/home?from=jgq", "https://gw.alicdn.com/imgextra/i4/O1CN01hZxSe0ieR9D0Gzew_!!6000000004632-2-tps-135-135.png"},
        {"544", "超划算", "高德精选", "amapuri://venue/center?scenario=assembly.venueCenter&tracePageId=venueCenter&effectiveVersion=16.16.0&atLeastVersion=23.8.80.5&useList2=true&from=zhutujingangwei&isShowBack=1", "https://gw.alicdn.com/imgextra/i3/O1CN01g6HufWf9RcD0Gzew_!!6000000006503-2-tps-135-135.png"},
        {"484", "特价酒店", "高德精选", "amapuri://hotsale/venue?inventoryIds=fast_4012&hotelCpNames=hotel_taobaolvxing_api&scene=quickPurchase", "https://gw.alicdn.com/imgextra/i1/O1CN019kcuMcPbpuB0Gzew_!!6000000005497-2-tps-135-135.png"},
        {"348", "我的店铺", "服务", "amapuri://webview/amaponline?hide_title=1&url=https%3A%2F%2Fmp.amap.com%2Fgd-h5%2Fmp-mono-shop-mng%2FshopList%3Fpha_manifest%3Dtrue%26redirect%3Damapuri%253A%252F%252Famap_bundle_small_biz%252Fsmall_biz_index%26channelSource%3Djingangwei", "https://gw.alicdn.com/imgextra/i3/O1CN016oC8qrfo39G0Gzew_!!6000000002712-2-tps-135-135.png"},
        {"362", "代驾", "服务", "amapuri://sharedtrip/taxi/driving/openDrivingIndex?data={\"sourceApplication\":\"amaptoolbox\"}", "https://gw.alicdn.com/imgextra/i3/O1CN013RyxmJHIb1J0Gzew_!!6000000003756-2-tps-135-135.png"},
        {"380", "旅游度假", "服务", "amapuri://tourAround/homePage?data={\"city_code\":\"\",\"city_name\":\"\",\"businessArgs\":{},\"trip_source\":1} &atLeastVersion=5.50.87.1", "https://gw.alicdn.com/imgextra/i2/O1CN01FvKWe4S3aQD0Gzew_!!6000000006673-2-tps-135-135.png"},
        {"323", "美食", "服务", "amapuri://search/general?keyword=%E7%BE%8E%E9%A3%9F&superid=a_23_96", "https://gw.alicdn.com/imgextra/i2/O1CN011BgZIv0TYBJ0Gzew_!!6000000004138-2-tps-135-135.png"},
        {"116", "车主服务", "服务", "amapuri://tabBarPage?__tab_config=%7B%22name%22%3A%20%22tabs_page_carcenter%22%2C%22ajx_bundles%22%3A%20%5B%22car%22%2C%20%22toolpro%22%5D%2C%22ajxBundles%22%3A%20%5B%22car%22%2C%20%22toolpro%22%5D%2C%22selectedIndex%22%3A%200%7D&from=mapicontoolbox", "https://gw.alicdn.com/imgextra/i4/O1CN01mlKbvlVtl3B0Gzew_!!6000000003192-2-tps-135-135.png"},
        {"410", "号卡充值", "服务", "amapuri://webview/amaponline?url=https%3A%2F%2Fkashichang.cn%2Fnc%2Fsaas%2Faggregatepage%2Fpublic%2Findex.html%3FaggregatePageId%3D384%26gd_from%3Doutside&sourceApplication=outside&urlType=0&contentType=autonavi&hide_title", "https://gw.alicdn.com/imgextra/i3/O1CN01xea1tpGT4SF0Gzew_!!6000000006040-2-tps-135-135.png"},
        {"470", "沿街取", "服务", "amapuri://ka_biz/StreetPick?data=%7B%22brandCode%22%3A%22al0016392%22%2C%22from%22%3A%22jingangwei%22%7D", "https://gw.alicdn.com/imgextra/i3/O1CN01PphaUqhkGdJ0Gzew_!!6000000007615-2-tps-135-135.png"},
        {"359", "新车底价", "服务", "amapuri://c3/carservice/protalPage?bizKey=auto&isStruct=true&from=shouyejingang", "https://gw.alicdn.com/imgextra/i3/O1CN01ZtrBWH6nrbF0Gzew_!!6000000004759-2-tps-135-135.png"},
        {"418", "秒送", "服务", "amapuri://sharedtrip/taxi/errands/index?sourceApplication=jgw_amap", "https://gw.alicdn.com/imgextra/i3/O1CN01e9CNfPtoCkG0Gzew_!!6000000005095-2-tps-135-135.png"},
        {"374", "高德车市", "服务", "amapuri://c3/common/standardproducts/selector?categoryId=carBrand&industry=new_car-car_series_detail&page_tactics=11&superid=zhutu", "https://gw.alicdn.com/imgextra/i4/O1CN010LhTinS2fpD0Gzew_!!6000000004737-2-tps-135-135.png"},
        {"356", "买房租房", "服务", "amapuri://info/search/house_portal?src=13", "https://gw.alicdn.com/imgextra/i1/O1CN01heLB1WlkmQK0Gzew_!!6000000004879-2-tps-135-135.png"},
        {"335", "洗车养车", "服务", "amapuri://webview/amaponline?url=https%3A%2F%2Fcache.gaode.com%2Factivity%2Flowcode%2Fh5%2FhvdO2m13%2Findex.html%3Fgd_from%3Dzhutuicon&hide_title=1&forbid_show_loading=1&use_net_proxy=1", "https://gw.alicdn.com/imgextra/i4/O1CN01O4TXPuskwYL0Gzew_!!6000000007437-2-tps-135-135.png"},
        {"449", "洗牙", "服务", "amapuri://c3/commonCustomerRetainPage?sceneType=askPrice&sceneTypeParam=%7B%22recallCategory%22%3A%22%E5%8F%A3%E8%85%94%E5%8C%BB%E9%99%A2%7C%E5%8F%A3%E8%85%94%E8%AF%8A%E6%89%80%22%2C%22campaignId%22%3A%2210001795%22%7D&version=v2", "https://gw.alicdn.com/imgextra/i3/O1CN01oGT2BOeKFRD0Gzew_!!6000000004409-2-tps-135-135.png"},
        {"383", "休闲玩乐", "服务", "amapuri://search/general?keyword=%E4%BC%91%E9%97%B2%E5%A8%B1%E4%B9%90", "https://gw.alicdn.com/imgextra/i1/O1CN01KBi33QwOd9I0Gzew_!!6000000003889-2-tps-135-135.png"},
        {"317", "订酒店", "服务", "amapuri://hotel/portal/new?superid=b_87&readHistoryCity=1&needTabFooterBar=1&atLeastVersion=22.84.50.1", "https://gw.alicdn.com/imgextra/i1/O1CN01uVmKQhTXc2I0Gzew_!!6000000007471-2-tps-135-135.png"},
        {"313", "优惠加油", "服务", "amapuri://c3/carservice/protalPage?searchType=keyword&keywords=加油站&superid=z_87_96", "https://gw.alicdn.com/imgextra/i2/O1CN01gtDGlDXMwcH0Gzew_!!6000000004641-2-tps-135-135.png"},
        {"537", "高德快报", "服务", "amapuri://ajx?path=path://amap_bundle_messagebox/src/NewMessageBoxPage.page.js&from=jingangwei", "https://gw.alicdn.com/imgextra/i2/O1CN01s7vpMGz68lJ0Gzew_!!6000000007535-2-tps-135-135.png"},
        {"363", "电影", "服务", "amapuri://search/general?keyword=电影&superid=z_91_60", "https://gw.alicdn.com/imgextra/i3/O1CN01BgRaVU8UOyG0Gzew_!!6000000004872-2-tps-135-135.png"},
        {"382", "丽人", "服务", "amapuri://search/general?keyword=%E4%B8%BD%E4%BA%BA", "https://gw.alicdn.com/imgextra/i1/O1CN01Sx9zwEhZ3QB0Gzew_!!6000000005979-2-tps-135-135.png"},
        {"458", "家电维修", "服务", "amapuri://c3/commonCustomerRetainPage?sceneType=fullPageLead&sceneTypeParam=%7B%22campaignId%22%3A%2210003257%22%7D", "https://gw.alicdn.com/imgextra/i1/O1CN013AKErkZ39fG0Gzew_!!6000000003408-2-tps-135-135.png"},
        {"471", "医美", "服务", "amapuri://search/general?keyword=%E5%8C%BB%E7%BE%8E", "https://gw.alicdn.com/imgextra/i2/O1CN01SsRCE1iBYzA0Gzew_!!6000000004386-2-tps-135-135.png"},
        {"485", "二手车", "服务", "amapuri://search/general?keyword=%E4%BA%8C%E6%89%8B%E8%BD%A6&superid=z_87_96&user_geoobj_range=30000", "https://gw.alicdn.com/imgextra/i2/O1CN01es67NP9KhaJ0Gzew_!!6000000003844-2-tps-135-135.png"},
        {"490", "加油充电", "服务", "amapuri://search/general?keyword=%E5%8A%A0%E6%B2%B9%E7%AB%99&superid=a_05&searchInputType=btn", "https://gw.alicdn.com/imgextra/i3/O1CN01Y3njf7uiAIK0Gzew_!!6000000005534-2-tps-135-135.png"},
        {"501", "超市", "服务", "amapuri://search/general?keyword=超市&superid=q_87_167", "https://gw.alicdn.com/imgextra/i3/O1CN011FgfOsrB2sE0Gzew_!!6000000002224-2-tps-135-135.png"},
        {"522", "附近工作", "服务", "amapuri://c3/jobPortal?from=home_ball&templateId=jobPortal&effectiveVersion=16.08.0&atLeastVersion=22.74.13.11", "https://gw.alicdn.com/imgextra/i3/O1CN01McAL7G6fBeL0Gzew_!!6000000004157-2-tps-135-135.png"},
        {"529", "ETC办理", "服务", "amapuri://webview/amaponline?url=https%3A%2F%2Fetc.cyzl.com%2Fwetc%2Fetc_for_gaode%2Findex.html%23%2Findex%3FshopId%3D1502689708888301568%26actId%3D1778310790621&forbid_show_loading=1&hide_title=0", "https://gw.alicdn.com/imgextra/i3/O1CN01NAQs16ZEF1E0Gzew_!!6000000005793-2-tps-135-135.png"},
        {"543", "高德问店", "服务", "amapuri://applets/platformapi/startapp?appId=2021006157639138", "https://gw.alicdn.com/imgextra/i1/O1CN01evbDNFIvUCE0Gzew_!!6000000003802-2-tps-135-135.png"},
        {"547", "借钱", "服务", "amapuri://webview/amaponline?url=https%3A%2F%2Fcache.gaode.com%2Factivity%2F2021GaodeAwardlist%2FloanGuidancepage.html%3Fgd_from%3Djgwgjx1&sourceApplication=1&hide_title=0", "https://gw.alicdn.com/imgextra/i4/O1CN019sxf7StWQrC0Gzew_!!6000000003948-2-tps-135-135.png"},
        {"568", "回收", "服务", "amapuri://search/general?keyword=%E5%9B%9E%E6%94%B6", "https://gw.alicdn.com/imgextra/i3/O1CN019dF367RvegD0Gzew_!!6000000002825-2-tps-135-135.png"},
        {"399", "地图小程序", "小程序", "amapuri://workInAmap/entryStatic?from=tools&sourceFrom=amapTools", "https://gw.alicdn.com/imgextra/i4/O1CN01r41Zw24bFuH0Gzew_!!6000000006875-2-tps-135-135.png"},
        {"430", "做旅游攻略", "小程序", "amapuri://workInAmap/entryStatic?&isIgnoreIntro=true&forceIgnoreIntroPage=true&forceAutoCreate=true&templateId=10000001010003120001&sourceScene=10&from=tools_template&sourceFrom=amapTools&utFrom=amapTools_TourismStrategy", "https://gw.alicdn.com/imgextra/i4/O1CN01kkw7vUnwNmH0Gzew_!!6000000005909-2-tps-135-135.png"},
        {"456", "共享位置", "小程序", "amapuri://workInAmap/entryStatic?isIgnoreIntro=true&forceIgnoreIntroPage=true&forceAutoCreate=true&templateId=10000001010000072001&sourceScene=10&from=tools_template&sourceFrom=amapTools&utFrom=amapTools_Position", "https://gw.alicdn.com/imgextra/i2/O1CN01lGawqQ4Cx1I0Gzew_!!6000000005318-2-tps-135-135.png"},
        {"432", "地图管门店", "小程序", "amapuri://workInAmap/entryStatic?&isIgnoreIntro=true&forceIgnoreIntroPage=true&forceAutoCreate=true&templateId=10000001010003124001&sourceScene=10&from=tools_template&sourceFrom=amapTools&utFrom=amapTools_StoreManagement", "https://gw.alicdn.com/imgextra/i3/O1CN01dVahmDJryFB0Gzew_!!6000000005188-2-tps-135-135.png"},
        {"476", "共享轨迹", "小程序", "amapuri://workInAmap/entryStatic?&isIgnoreIntro=true&forceIgnoreIntroPage=true&forceAutoCreate=true&templateId=10000001010000166001&sourceScene=10&from=tools_template&sourceFrom=amapTools&utFrom=amapTools_guiji", "https://gw.alicdn.com/imgextra/i4/O1CN01Z5SJagbD0dG0Gzew_!!6000000003818-2-tps-135-135.png"},
        {"477", "地图找客户", "小程序", "amapuri://workInAmap/entryStatic?&isIgnoreIntro=true&forceIgnoreIntroPage=true&forceAutoCreate=true&templateId=10000001010003124003&sourceScene=10&from=tools_template&sourceFrom=amapTools&utFrom=amapTools_Outsidework", "https://gw.alicdn.com/imgextra/i2/O1CN01CdWqsPnFC7J0Gzew_!!6000000006111-2-tps-135-135.png"},
        {"102", "驾车", "出行", "amapuri://routePlan/home?t=0", "https://gw.alicdn.com/imgextra/i2/O1CN01BX4egZlz4yG0Gzew_!!6000000003035-2-tps-135-135.png"},
        {"360", "新能源导航", "出行", "amapuri://routePlan/home?t=12", "https://gw.alicdn.com/imgextra/i2/O1CN01h1dYrW3cRHC0Gzew_!!6000000005069-2-tps-135-135.png"},
        {"417", "驾车巡航", "出行", "amapuri://edog/home?from=recommendtool", "https://gw.alicdn.com/imgextra/i4/O1CN01vAXLLMOhQeB0Gzew_!!6000000004186-2-tps-135-135.png"},
        {"271", "货车", "出行", "amapuri://routePlan/home?t=7", "https://gw.alicdn.com/imgextra/i2/O1CN013Vq2ddvIEzD0Gzew_!!6000000005628-2-tps-135-135.png"},
        {"254", "摩托车", "出行", "amapuri://routePlan/home?t=11", "https://gw.alicdn.com/imgextra/i3/O1CN018cQlkOrwZkF0Gzew_!!6000000005080-2-tps-135-135.png"},
        {"103", "公交地铁", "出行", "amapuri://routePlan/home?t=1&netAcc={\"path\":\"amapservice://amap_bundle_realbus/RequestScheduleService\",\"requestKeys\":\"basemapRouteNearBus\"}", "https://gw.alicdn.com/imgextra/i4/O1CN01jl6K2Coz8SB0Gzew_!!6000000003761-2-tps-135-135.png"},
        {"151", "实时公交", "出行", "amapuri://realtimeBus/home?netAcc={\"path\":\"amapservice://amap_bundle_realbus/RequestScheduleService\",\"requestKeys\":\"busStation\"}&from=toolbox", "https://gw.alicdn.com/imgextra/i4/O1CN01P5lTFDpRpsG0Gzew_!!6000000008016-2-tps-135-135.png"},
        {"104", "步行", "出行", "amapuri://routePlan/plan?t=2", "https://gw.alicdn.com/imgextra/i3/O1CN01yrvlUYpM65E0Gzew_!!6000000008014-2-tps-135-135.png"},
        {"105", "骑行", "出行", "amapuri://routePlan/plan?t=3", "https://gw.alicdn.com/imgextra/i3/O1CN01IB0Q2xXqsvH0Gzew_!!6000000005856-2-tps-135-135.png"},
        {"395", "高德运动", "出行", "amapuri://ajx_sports_health/SportIndex?route=Sport&from=homeBox", "https://gw.alicdn.com/imgextra/i3/O1CN012yZFlGcrwhI0Gzew_!!6000000005397-2-tps-135-135.png"},
        {"472", "无网导航", "出行", "amapuri://videoknowledge/VideoDetailPage?&labelId=2035308100005956644&labelType=20&contentId=1043014100005956651&source=jingangwei", "https://gw.alicdn.com/imgextra/i1/O1CN01I5YJ1amorSK0Gzew_!!6000000005897-2-tps-135-135.png"},
        {"453", "卫星求救", "出行", "amapuri://rescue/RescuePage", "https://gw.alicdn.com/imgextra/i4/O1CN01wzxovoBziVG0Gzew_!!6000000005008-2-tps-135-135.png"},
        {"106", "打车", "出行", "amapuri://drive/takeTaxi?sourceApplication=icon_dache", "https://gw.alicdn.com/imgextra/i4/O1CN01srxx1Nv4XlH0Gzew_!!6000000000993-2-tps-135-135.png"},
        {"370", "助老打车", "出行", "amapuri://sharedtrip/taxi/helpage/openHelpageIndex?sourceApplication=helpageCarry&data=%7B%22sourceApplication%22%3A%22helpageCarry%22%7D", "https://gw.alicdn.com/imgextra/i2/O1CN01dyCAhUoGLcD0Gzew_!!6000000007803-2-tps-135-135.png"},
        {"354", "企业用车", "出行", "amapuri://sharetrip/enterprise/onService?from=AmapEntTaxiEntry", "https://gw.alicdn.com/imgextra/i1/O1CN01Q9dxtTNAE1D0Gzew_!!6000000005653-2-tps-135-135.png"},
        {"327", "火车票机票", "出行", "amapuri://hkf/HkfPortal?routeType=flight&from=jingangweil&needTabFooterBar=1&atLeastVersion=22.84.50.1", "https://gw.alicdn.com/imgextra/i2/O1CN01CcmT2t6tSZC0Gzew_!!6000000006437-2-tps-135-135.png"},
        {"125", "跑步运动", "出行", "amapuri://ajx_sports_health/SportIndex?route=SportBeforeNavi&travel_type=6&from=homeBox", "https://gw.alicdn.com/imgextra/i4/O1CN012IwKk2xBB6C0Gzew_!!6000000006255-2-tps-135-135.png"},
        {"126", "骑行运动", "出行", "amapuri://ajx_sports_health/SportIndex?route=SportBeforeNavi&travel_type=7&from=homeBox", "https://gw.alicdn.com/imgextra/i2/O1CN019gNpr3wKWxB0Gzew_!!6000000008181-2-tps-135-135.png"},
        {"110", "查公交", "出行", "amapuri://ajx_realbus/RealBusIndexPage?openSearch=1", "https://gw.alicdn.com/imgextra/i2/O1CN01wrLomdjkzIH0Gzew_!!6000000001924-2-tps-135-135.png"},
        {"111", "地铁图", "出行", "amapuri://subway/home", "https://gw.alicdn.com/imgextra/i3/O1CN01ZSGE0viydfB0Gzew_!!6000000000901-2-tps-135-135.png"},
        {"541", "包车", "出行", "amapuri://sharedtrip/taxi/global/charterCarIndex?sourceApplication=qsPrivateCarcharter", "https://gw.alicdn.com/imgextra/i4/O1CN01VY4NFAj65zE0Gzew_!!6000000007278-2-tps-135-135.png"},
        {"145", "乘车码", "出行", "amapuri://buscard/detailPage?from=gongju", "https://gw.alicdn.com/imgextra/i3/O1CN01JDX629aJJRH0Gzew_!!6000000002804-2-tps-135-135.png"},
        {"386", "拼车", "出行", "amapuri://sharedtrip/taxi/intercity/intercityIndex?pageType=carpool_city&sourceApplication=amap", "https://gw.alicdn.com/imgextra/i1/O1CN011GPcmnBwEGC0Gzew_!!6000000007354-2-tps-135-135.png"},
        {"469", "顺风车", "出行", "amapuri://sharedtrip/hitch/index?sourceApplication=amapjgw", "https://gw.alicdn.com/imgextra/i2/O1CN01RwNLKfZeAAB0Gzew_!!6000000000678-2-tps-135-135.png"},
        {"502", "乘车码", "出行", "amapuri://webview/amaponline?url=https%3A%2F%2Frender.alipay.com%2Fp%2Fs%2Fi%3Fscheme%3Dalipays%253A%252F%252Fplatformapi%252Fstartapp%253FappId%253D20002047%2526scene%253Dbus%2526chInfo%253Dgdhlnew%2526alipayAppdonwlaodPlanId%253Dba401e8a04344cd08518a92dc10c3a94%2526backurl%253Damapuri%25253A%25252F%25252Famap%25253FclearStack%25253D0%252526keepStack%25253D1%2526&forbid_show_loading=1&hide_title=0", "https://gw.alicdn.com/imgextra/i3/O1CN01JDX629aJJRH0Gzew_!!6000000002804-2-tps-135-135.png"},
        {"127", "收藏夹", "小工具", "amapuri://ajx_favorites/index", "https://gw.alicdn.com/imgextra/i2/O1CN01yX3gy4zxcsL0Gzew_!!6000000007770-2-tps-135-135.png"},
        {"333", "足迹", "小工具", "amapuri://ajx?path=path://amap_bundle_mine/src/pages/FootprintPage.page.js&data={\"from\":\"tool\",\"scene\":3,\"cardName\":\"city\"}&wvc=true&animation=false", "https://gw.alicdn.com/imgextra/i2/O1CN01xluLndc7IgF0Gzew_!!6000000001530-2-tps-135-135.png"},
        {"319", "手车互联", "小工具", "amapuri://amapcar/main", "https://gw.alicdn.com/imgextra/i1/O1CN01xN7wRPZvIzJ0Gzew_!!6000000001064-2-tps-135-135.png"},
        {"118", "限行查询", "小工具", "amapuri://carRestrict/openRestrictCities?from=toolbox&sourceApplication=Trip", "https://gw.alicdn.com/imgextra/i2/O1CN01xWWKPxEETyC0Gzew_!!6000000006776-2-tps-135-135.png"},
        {"384", "地图共建", "小工具", "amapuri://feedback_ajx/feedbackHome?data=%7B%22sourcepage%22%3A%22185%22%2C%22pageOrigin%22%3A%22PublicAskPage%22%7D", "https://gw.alicdn.com/imgextra/i3/O1CN013TjL5b4mtzF0Gzew_!!6000000000508-2-tps-135-135.png"},
        {"130", "扫一扫", "小工具", "amapuri://qrscan/mainView?firepage=kit", "https://gw.alicdn.com/imgextra/i2/O1CN01BhM4DTzoUGD0Gzew_!!6000000000277-2-tps-135-135.png"},
        {"367", "写地点评价", "小工具", "amapuri://mine/comments?tabIndex=1", "https://gw.alicdn.com/imgextra/i3/O1CN01HUYJZ6qrJ0F0Gzew_!!6000000005591-2-tps-135-135.png"},
        {"128", "测距", "小工具", "amapuri://measure/home", "https://gw.alicdn.com/imgextra/i1/O1CN01TZRKNTsFI3D0Gzew_!!6000000004001-2-tps-135-135.png"},
        {"391", "油耗记录", "其他", "amapuri://footprint/OilWearPage?from=toolbox", "https://gw.alicdn.com/imgextra/i4/O1CN01RstXUnAGQ7F0Gzew_!!6000000002429-2-tps-135-135.png"},
        {"283", "通行费助手", "其他", "amapuri://ajx?path=path://amap_bundle_etc/src/etc/ETCBillDetail.page.js&data=%7b%22from%22%3a%22mainpage%22%7d", "https://gw.alicdn.com/imgextra/i3/O1CN01s7fnQ3hmSxH0Gzew_!!6000000004063-2-tps-135-135.png"},
        {"112", "群组", "其他", "amapuri://AGroup/joinGroup?from=tool", "https://gw.alicdn.com/imgextra/i4/O1CN01q4lJasit4ND0Gzew_!!6000000004909-2-tps-135-135.png"},
        {"310", "家人地图", "其他", "amapuri://WatchFamily/myFamily?from=toolbox", "https://gw.alicdn.com/imgextra/i2/O1CN01Dh9d32TMpRI0Gzew_!!6000000005676-2-tps-135-135.png"},
        {"115", "离线地图", "其他", "amapuri://offlinemap/home", "https://gw.alicdn.com/imgextra/i1/O1CN01bwhS0nbvPII0Gzew_!!6000000005844-2-tps-135-135.png"},
        {"121", "驾驶成就", "其他", "amapuri://ajx?path=path://amap_bundle_mine/src/pages/FootprintPage.page.js&data={\\\"from\\\":\\\"toolbox\\\",\\\"scene\\\":3,\\\"cardName\\\":\\\"driver\\\"}&wvc=true&animation=false", "https://gw.alicdn.com/imgextra/i1/O1CN0134BjsAnn4wD0Gzew_!!6000000007368-2-tps-135-135.png"},
        {"337", "语音助手", "其他", "amapuri://vui/HelpCenter?selectTab=beginner_guide", "https://gw.alicdn.com/imgextra/i4/O1CN01ZyNGmtQOH4C0Gzew_!!6000000007672-2-tps-135-135.png"},
        {"361", "记录足迹", "其他", "amapuri://footprint/FreeRecord?from=toolbox&showListEntry=1", "https://gw.alicdn.com/imgextra/i3/O1CN016t2xhQOXDNE0Gzew_!!6000000007110-2-tps-135-135.png"},
        {"483", "高德扫街榜", "其他", "amapuri://ajx?path=path://amap_bundle_landing_page/src/new_rank/RankNewPage.page.js&data=%7B%22business%22%3A%22dining%22%2C%22list_tag%22%3A%22%E9%AB%98%E5%BE%B7%E6%89%AB%E8%A1%97%E6%A6%9C%7C%E9%AB%98%E5%BE%B7%E6%89%AB%E8%A1%97%E6%A6%9C%22%2C%22poi_tpl_style%22%3A%225%22%2C%22gd_from%22%3A%22pageIndex%22%7D", "https://gw.alicdn.com/imgextra/i4/O1CN01zG719UdujcE0Gzew_!!6000000005477-2-tps-135-135.png"},
        {"377", "停车推荐", "其他", "amapuri://drive/ParkRadar?sourcePage=mainPage&sourceApplication=MainTool", "https://gw.alicdn.com/imgextra/i1/O1CN01s0d3cOVTQTH0Gzew_!!6000000002308-2-tps-135-135.png"},
        {"375", "停车记录", "其他", "amapuri://drive/ParkingPage?from=toolbox", "https://gw.alicdn.com/imgextra/i3/O1CN012IIj5HDkOcE0Gzew_!!6000000002385-2-tps-135-135.png"},
        {"349", "领水果", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fcache.gaode.com%2Factivity%2F2021AmapGarden%2Findex.html%3Fgd_from%3Damap_tool&hide_title=1", "https://gw.alicdn.com/imgextra/i1/O1CN01Kn2wa5gOAPH0Gzew_!!6000000000534-2-tps-135-135.png"},
        {"108", "电子狗", "其他", "amapuri://edog/home?from=edog", "https://gw.alicdn.com/imgextra/i1/O1CN01RZPGNbNS4oC0Gzew_!!6000000003233-2-tps-135-135.png"},
        {"129", "商户标注", "其他", "amapuri://ajx?path=path://amap_bundle_small_biz/src/pages/small_biz_index.page.js&data=%7B%22from%22%3A%22xinzhutu%22%7D", "https://gw.alicdn.com/imgextra/i4/O1CN01fmgydyXpU1I0Gzew_!!6000000000358-2-tps-135-135.png"},
        {"131", "反馈上报", "其他", "amapuri://reportTrafficEvent/main", "https://gw.alicdn.com/imgextra/i1/O1CN01pbEs1e1wyYC0Gzew_!!6000000000090-2-tps-135-135.png"},
        {"133", "高德淘金", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fgxd.amap.com%2F", "https://gw.alicdn.com/imgextra/i2/O1CN01Bj9saFcDvGB0Gzew_!!6000000005765-2-tps-135-135.png"},
        {"136", "交通号", "其他", "amapuri://webview/amaponline?url=https://jiaotonghao2.amap.com/?channel=amap&hide_title=1&hideTitleBar=1", "https://gw.alicdn.com/imgextra/i3/O1CN01WlW2dALGC8I0Gzew_!!6000000000128-2-tps-135-135.png"},
        {"303", "一路护航", "其他", "amapuri://ajx?path=path://amap_bundle_convoy/src/index.page.js", "https://gw.alicdn.com/imgextra/i4/O1CN01hdIMrVPAzwF0Gzew_!!6000000006116-2-tps-135-135.png"},
        {"352", "送药上门", "其他", "amapuri://applets/platformapi/startapp?appId=2021002120634069&chInfo=ch_scene__chsub_homepage_toolkit", "https://gw.alicdn.com/imgextra/i3/O1CN01VUF29mINHeI0Gzew_!!6000000007889-2-tps-135-135.png"},
        {"365", "钱包卡券", "其他", "amapuri://webview/amaponline?url=https://cache.gaode.com/activity/2021GaodeAwardlist/index.html&hide_title=1", "https://gw.alicdn.com/imgextra/i1/O1CN01hOnRB04s7qL0Gzew_!!6000000004033-2-tps-135-135.png"},
        {"366", "我的反馈", "其他", "amapuri://ajx?path=path://amap_bundle_basemap_feedback/src/user_center/pages/BizUserFeedBackList.page.js", "https://gw.alicdn.com/imgextra/i3/O1CN01L4On80bQSTB0Gzew_!!6000000006423-2-tps-135-135.png"},
        {"378", "我的问答", "其他", "amapuri://poiAsk/myQuestionAskPage", "https://gw.alicdn.com/imgextra/i2/O1CN01Z4BeIKF3k3C0Gzew_!!6000000001902-2-tps-135-135.png"},
        {"388", "我的相册", "其他", "amapuri://mine/album?from=tool", "https://gw.alicdn.com/imgextra/i1/O1CN01J5YTUlaJY1C0Gzew_!!6000000002876-2-tps-135-135.png"},
        {"390", "达人中心", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fcache.gaode.com%2Factivity%2F2022infoTalent%2FexchangeIndex.html%3Fgd_from%3Djx&hide_title=1", "https://gw.alicdn.com/imgextra/i3/O1CN01Qi2A8NfXGHK0Gzew_!!6000000007532-2-tps-135-135.png"},
        {"392", "车险报价", "其他", "amapuri://webview/thirdparty?url=https%3A%2F%2Fu.alipay.cn%2F_6waurB0pszn%20", "https://gw.alicdn.com/imgextra/i1/O1CN01RYgu0RMrpyK0Gzew_!!6000000000825-2-tps-135-135.png"},
        {"393", "拍图赚赏金", "其他", "amapuri://feedback/publicAskMapOperation?sourcePage=185", "https://gw.alicdn.com/imgextra/i3/O1CN016QgKyF4xPfB0Gzew_!!6000000001725-2-tps-135-135.png"},
        {"396", "道路救援", "其他", "amapuri://c3/carservice/PlaceOrder?from=toolkit&tabSelected=rescue_electrify", "https://gw.alicdn.com/imgextra/i2/O1CN01TS0HcDhUiTK0Gzew_!!6000000002131-2-tps-135-135.png"},
        {"398", "献血", "其他", "amapuri://search/general?keyword=%E7%8C%AE%E8%A1%80&superid=a_87", "https://gw.alicdn.com/imgextra/i2/O1CN01qdu6auBtWZC0Gzew_!!6000000001333-2-tps-135-135.png"},
        {"405", "发热门诊", "其他", "amapuri://ajx_plague_map/FeverClinicsPage?from=tool", "https://gw.alicdn.com/imgextra/i4/O1CN014qiXFVjLZ0H0Gzew_!!6000000007646-2-tps-135-135.png"},
        {"406", "合成烟花", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fline.amap.com%2F2023PointsMall%2Findex.html%3Fgd_from%3Djingangyanhua&sourceApplication=jingangyanhua&hide_title=1", "https://gw.alicdn.com/imgextra/i1/O1CN01xh6Um2b0WZI0Gzew_!!6000000004772-2-tps-135-135.png"},
        {"414", "养车必囤", "其他", "amapuri://c3/carservice/protalPage?bizKey=washCarProduct&superid=z_87_96_29", "https://gw.alicdn.com/imgextra/i4/O1CN01YksJEQmNxED0Gzew_!!6000000002149-2-tps-135-135.png"},
        {"416", "电动车投屏", "其他", "amapuri://bleconnect/main", "https://gw.alicdn.com/imgextra/i1/O1CN01QMqLnnRGbYJ0Gzew_!!6000000004227-2-tps-135-135.png"},
        {"447", "3D车标", "其他", "amapuri://dialect/home?tab=carlogo&from=jgq", "https://gw.alicdn.com/imgextra/i1/O1CN01qPqpwSOVf9I0Gzew_!!6000000008107-2-tps-135-135.png"},
        {"451", "游戏中心", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fline.amap.com%2F2023PointsMall%2Findex.html%3Fgd_from%3Djingang&sourceApplication=jingang&hide_title=1", "https://gw.alicdn.com/imgextra/i1/O1CN01euXg9W04VkG0Gzew_!!6000000007783-2-tps-135-135.png"},
        {"468", "高德公益", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fc3-activity.amap.com%2Fapp%2Famap_web_c3_info%2Fwelfare-2026%2Findex.html%3Fgd_from%3Djingang&sourceApplication=jingang&hide_title=1", "https://gw.alicdn.com/imgextra/i1/O1CN01292g9khBZIJ0Gzew_!!6000000005572-2-tps-135-135.png"},
        {"479", "天天领福利", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fc3-pha.amap.com%2F2025ActFissionSite%2Findex.html%3FareaId%3Damap_ugliebiansite_00001%26gd_from%3Djingang&sourceApplication=jingang&hide_title=0", "https://gw.alicdn.com/imgextra/i4/O1CN016lN1JEDeruI0Gzew_!!6000000001063-2-tps-135-135.png"},
        {"546", "长征星火", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fcz.amap.com%2Fprod%2Fpublish%2Fcz-front-h5%2Famap%2Findex.html%3Fgd_from%3Dtools_box&forbid_show_loading=1&hide_title=1&login_check=1", "https://gw.alicdn.com/imgextra/i4/O1CN01dBvIGGEbHNE0Gzew_!!6000000003411-2-tps-135-135.png"},
        {"558", "懂鸟", "其他", "amapuri://applets/platformapi/startapp?appId=2021003148626361", "https://gw.alicdn.com/imgextra/i3/O1CN01VzrAWpb3yoB0Gzew_!!6000000000889-2-tps-135-135.png"},
        {"567", "真探中心", "其他", "amapuri://webview/amaponline?url=https%3A%2F%2Fc3-activity.amap.com%2Fapp%2Famap-h5%2Fdetective-center%2Findex.html%3Fgd_from%3Dnew_jingangwei&sourceApplication=zt&urlType=0&contentType=autonavi&hide_title=1", "https://gw.alicdn.com/imgextra/i2/O1CN01AjIO5QbopJH0Gzew_!!6000000001519-2-tps-135-135.png"}
    };
    public static final String[] GROUPS = {"出行", "高德精选", "服务", "小程序", "小工具", "其他"};
    private static final Map<String, String> KEYS;
    static {
        Map<String, String> keys = new LinkedHashMap<String, String>();
        for (String[] entry : ENTRIES) keys.put(entry[0], "tool_id_" + entry[0]);
        // Preserve existing backups and user selections for the original toolbar switches.
        for (String[] entry : AmapData.TOOLS) keys.put(entry[0], Config.K_TOOL_PREFIX + entry[1]);
        KEYS = Collections.unmodifiableMap(keys);
    }
    private AmapToolCatalog() {}
    public static String key(String id) { return KEYS.get(id); }
    public static JSONObject item(String[] entry) throws Exception {
        return new JSONObject().put("id", entry[0]).put("name", entry[1])
            .put("schema", entry[3]).put("iconV2", entry[4]);
    }
}
