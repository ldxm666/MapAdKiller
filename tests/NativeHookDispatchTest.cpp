#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>
using Hook=int (*)(void *,void *,void **);
#include "../app/native/native_hook_dispatch.h"

static std::atomic<int> assertions{0},amap{0},baidu{0},prepareCount{0};
static std::atomic<bool> installerSuccess{true},prepared{false},requirePrepared{false};
static Hook observedHook=nullptr;
static uint32_t observedApi=0;
static void check(bool value,const char *name){
    assertions++;if(!value){std::fprintf(stderr,"FAIL %s\n",name);std::exit(1);}
}
static int frameworkHook(void *,void *,void **){return 0;}
static int otherHook(void *,void *,void **){return 0;}
static bool install(NativeHost host,Hook hook,uint32_t api){
    if(requirePrepared)check(prepared,"policy prepared before hook installation");
    if(host==NativeHost::Amap)amap++;else if(host==NativeHost::Baidu)baidu++;
    else check(false,"unknown host never reaches installer");
    observedHook=hook;observedApi=api;return installerSuccess;
}
static void reset(){amap=0;baidu=0;prepareCount=0;installerSuccess=true;prepared=false;requirePrepared=false;observedHook=nullptr;observedApi=0;}
int main(){
    reset();{
        NativeHookDispatch state(install);
        state.bind(frameworkHook,1);
        check(!amap && !baidu,"framework callback alone installs no hooks");
        check(!state.request(NativeHost::Other),"unknown host rejected");
        requirePrepared=true;
        check(state.request(NativeHost::Amap,[]{prepared=true;prepareCount++;}),"callback-first AMap accepted");
        check(amap==1 && !baidu && prepareCount==1,"callback-first installs only AMap once");
        check(observedHook==frameworkHook && observedApi==1,"trusted Hook and API forwarded");
        check(state.request(NativeHost::Amap,[]{prepareCount++;}),"same-host configuration update accepted");
        state.bind(frameworkHook,1);state.bind(otherHook,9);
        check(amap==1 && prepareCount==2 && observedHook==frameworkHook,"repeated callback/configuration never double hooks");
        int before=prepareCount;
        check(!state.request(NativeHost::Baidu,[]{prepareCount++;}),"AMap host rejects Baidu hook family");
        check(!baidu && prepareCount==before,"rejected host cannot change policy");
    }
    reset();{
        NativeHookDispatch state(install);requirePrepared=true;
        check(state.request(NativeHost::Amap,[]{prepared=true;}),"JNI-first AMap request cached");
        check(!amap && !baidu,"no framework Hook means no install");
        state.bind(nullptr,5);check(!amap,"null framework callback ignored");
        state.bind(frameworkHook,7);
        check(amap==1 && !baidu && observedApi==7,"late callback installs cached AMap request");
        state.bind(frameworkHook,7);state.request(NativeHost::Amap);
        check(amap==1,"late callback and repeated JNI are install-once");
    }
    reset();{
        NativeHookDispatch state(install);
        check(state.request(NativeHost::Baidu),"JNI-first Baidu request cached");
        check(!amap && !baidu,"Baidu waits for framework capability");
        state.bind(frameworkHook,2);
        check(baidu==1 && !amap,"late callback installs only Baidu files");
        check(!state.request(NativeHost::Amap),"Baidu host rejects AAsset family");
        check(state.request(NativeHost::Baidu),"same Baidu host remains usable");
        check(baidu==1 && !amap,"no cross-host or duplicate Baidu install");
    }
    reset();{
        NativeHookDispatch state(install);state.bind(frameworkHook,3);
        check(state.request(NativeHost::Baidu),"callback-first Baidu request accepted");
        check(baidu==1 && !amap,"callback-first Baidu never installs AAssets");
    }
    reset();{
        NativeHookDispatch state(install);installerSuccess=false;state.bind(frameworkHook,4);
        check(!state.request(NativeHost::Amap),"hook failure reported to JNI requester");
        check(!state.request(NativeHost::Amap),"failed install is not retried over partially installed hooks");
        state.bind(otherHook,5);
        check(amap==1 && !baidu,"failure remains install-once");
        check(!state.request(NativeHost::Baidu),"failure cannot cross to other host");
    }
    reset();{
        NativeHookDispatch state(install);std::vector<std::thread> tasks;
        for(int i=0;i<24;i++)tasks.emplace_back([&]{state.bind(frameworkHook,6);});
        for(int i=0;i<24;i++)tasks.emplace_back([&]{check(state.request(NativeHost::Amap,[]{prepareCount++;}),"concurrent same-host setup accepted");});
        for(auto &task:tasks)task.join();
        check(amap==1 && !baidu && prepareCount==24,"concurrent callback/JNI installs one family exactly once");
    }
    reset();{
        NativeHookDispatch state(install);std::vector<std::thread> tasks;
        std::atomic<int> acceptedA{0},acceptedB{0};
        for(int i=0;i<20;i++)tasks.emplace_back([&]{if(state.request(NativeHost::Amap))acceptedA++;});
        for(int i=0;i<20;i++)tasks.emplace_back([&]{if(state.request(NativeHost::Baidu))acceptedB++;});
        tasks.emplace_back([&]{state.bind(frameworkHook,8);});
        for(auto &task:tasks)task.join();
        check((acceptedA==20 && acceptedB==0) || (acceptedB==20 && acceptedA==0),"concurrent conflicting requests select one immutable host");
        check((amap==1 && baidu==0) || (baidu==1 && amap==0),"conflicting hosts never install both hook families");
    }
    std::printf("NativeHookDispatchTest: %d assertions passed\n",assertions.load());return 0;
}
