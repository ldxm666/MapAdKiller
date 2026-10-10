#include <android/asset_manager.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <memory>
#include <string>
#include <unordered_map>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include "style_proto.h"
#include "vendor/zstd/lib/zstd.h"

using Hook=int (*)(void *,void *,void **);
using Loaded=void (*)(const char *,void *);
#include "native_hook_dispatch.h"
static bool installNativeHost(NativeHost,Hook,uint32_t);
static NativeHookDispatch nativeHooks(installNativeHost);
template<class Prepare> static bool requestBaiduNative(Prepare prepare){return nativeHooks.request(NativeHost::Baidu,prepare);}
#include "baidu_file_assets.h"
struct NativeAPIEntries {uint32_t version;Hook hook_func;int (*unhook_func)(void *);};
static std::atomic<int> map{0},hidden{0};
static std::mutex assetsMutex;
struct Asset {std::shared_ptr<style::Bytes> bytes;size_t offset=0;bool logged=false;};
static std::unordered_map<AAsset *,Asset> assets;
static std::atomic<size_t> trackedCount{0};
static AAsset *(*orig_open)(AAssetManager *,const char *,int);
static const void *(*orig_buffer)(AAsset *);
static int (*orig_read)(AAsset *,void *,size_t);
static off_t (*orig_seek)(AAsset *,off_t,int);
static off64_t (*orig_seek64)(AAsset *,off64_t,int);
static off_t (*orig_length)(AAsset *),(*orig_remaining)(AAsset *);
static off64_t (*orig_length64)(AAsset *),(*orig_remaining64)(AAsset *);
static void (*orig_close)(AAsset *);
static int (*orig_fd)(AAsset *,off_t *,off_t *);
static int (*orig_fd64)(AAsset *,off64_t *,off64_t *);
static constexpr size_t maxSize=24*1024*1024;

static bool decode(AAsset *asset,style::Bytes &out,bool &compressed){
    auto size=orig_length64(asset);auto *data=static_cast<const uint8_t *>(orig_buffer(asset));
    if(!data || size<8 || size>maxSize)return false;
    compressed=data[0]==0x28 && data[1]==0xb5 && data[2]==0x2f && data[3]==0xfd;
    if(!compressed){out.assign(data,data+size);return true;}
    auto length=ZSTD_getFrameContentSize(data,size);
    if(length==ZSTD_CONTENTSIZE_ERROR || length==ZSTD_CONTENTSIZE_UNKNOWN || length>maxSize)return false;
    out.resize(length);auto actual=ZSTD_decompress(out.data(),out.size(),data,size);
    return !ZSTD_isError(actual) && actual==length;
}
static AAsset *asset_open(AAssetManager *manager,const char *name,int mode){
    AAsset *asset=orig_open(manager,name,mode);
    if(!asset || !name || map!=1 || hidden==0 || std::strncmp(name,"map_assets/style_",17))return asset;
    std::string file(name);if(file.find("/style_X_Main")==std::string::npos)return asset;
    style::Bytes decoded;bool compressed=false;
    if(!decode(asset,decoded,compressed))return asset;
    style::Bytes filtered;int changed=0;
    if(!style::amap(decoded.data(),decoded.size(),filtered,changed) || !changed)return asset;
    auto result=std::make_shared<style::Bytes>();
    if(compressed){
        result->resize(ZSTD_compressBound(filtered.size()));
        auto length=ZSTD_compress(result->data(),result->size(),filtered.data(),filtered.size(),3);
        if(ZSTD_isError(length))return asset;result->resize(length);
    }else *result=std::move(filtered);
    {std::lock_guard<std::mutex> guard(assetsMutex);assets[asset]={result,0};trackedCount=assets.size();}
    __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","style_filtered mask=%x rules=%d bytes=%zu asset=%s",hidden.load(),changed,result->size(),name);
    return asset;
}
static const void *asset_buffer(AAsset *asset){
    if(trackedCount){std::lock_guard<std::mutex> guard(assetsMutex);auto a=assets.find(asset);if(a!=assets.end()){
        if(!a->second.logged){a->second.logged=true;__android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","style_consumed buffer bytes=%zu",a->second.bytes->size());}
        return a->second.bytes->data();
    }}
    return orig_buffer(asset);
}
static int asset_read(AAsset *asset,void *out,size_t count){
    if(trackedCount){std::lock_guard<std::mutex> guard(assetsMutex);auto a=assets.find(asset);if(a!=assets.end()){
        if(!a->second.logged){a->second.logged=true;__android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","style_consumed read request=%zu bytes=%zu",count,a->second.bytes->size());}
        auto &s=a->second;count=std::min(count,s.bytes->size()-s.offset);std::memcpy(out,s.bytes->data()+s.offset,count);s.offset+=count;return int(count);
    }}
    return orig_read(asset,out,count);
}
static off64_t seek(Asset &a,off64_t offset,int whence){
    off64_t base=whence==SEEK_SET?0:whence==SEEK_CUR?a.offset:whence==SEEK_END?a.bytes->size():-1;
    if(base<0 || offset< -base || offset>off64_t(a.bytes->size())-base)return -1;a.offset=base+offset;return a.offset;
}
#define TRACKED_RESULT(expression) if(trackedCount){std::lock_guard<std::mutex> guard(assetsMutex);auto a=assets.find(asset);if(a!=assets.end())return (expression);}
static off_t asset_seek(AAsset *asset,off_t offset,int whence){TRACKED_RESULT(seek(a->second,offset,whence));return orig_seek(asset,offset,whence);}
static off64_t asset_seek64(AAsset *asset,off64_t offset,int whence){TRACKED_RESULT(seek(a->second,offset,whence));return orig_seek64(asset,offset,whence);}
static off_t asset_length(AAsset *asset){TRACKED_RESULT(a->second.bytes->size());return orig_length(asset);}
static off64_t asset_length64(AAsset *asset){TRACKED_RESULT(a->second.bytes->size());return orig_length64(asset);}
static off_t asset_remaining(AAsset *asset){TRACKED_RESULT(a->second.bytes->size()-a->second.offset);return orig_remaining(asset);}
static off64_t asset_remaining64(AAsset *asset){TRACKED_RESULT(a->second.bytes->size()-a->second.offset);return orig_remaining64(asset);}
#undef TRACKED_RESULT
static int memory_fd(const style::Bytes &bytes){
    int fd=int(syscall(__NR_memfd_create,"mapclean-style",MFD_CLOEXEC));if(fd<0)return -1;
    size_t pos=0;while(pos<bytes.size()){auto written=write(fd,bytes.data()+pos,bytes.size()-pos);if(written<=0){close(fd);return -1;}pos+=written;}
    lseek(fd,0,SEEK_SET);return fd;
}
static std::shared_ptr<style::Bytes> fileBytes(AAsset *asset){
    if(trackedCount){std::lock_guard<std::mutex> guard(assetsMutex);auto a=assets.find(asset);if(a!=assets.end())return a->second.bytes;}return {};
}
static int asset_fd(AAsset *asset,off_t *offset,off_t *length){auto bytes=fileBytes(asset);if(!bytes)return orig_fd(asset,offset,length);*offset=0;*length=bytes->size();return memory_fd(*bytes);}
static int asset_fd64(AAsset *asset,off64_t *offset,off64_t *length){auto bytes=fileBytes(asset);if(!bytes)return orig_fd64(asset,offset,length);*offset=0;*length=bytes->size();return memory_fd(*bytes);}
static void asset_close(AAsset *asset){if(trackedCount){std::lock_guard<std::mutex> guard(assetsMutex);assets.erase(asset);trackedCount=assets.size();}orig_close(asset);}
static void on_loaded(const char *,void *){}
static bool installNativeHost(NativeHost host,Hook hook,uint32_t api){
    if(host==NativeHost::Baidu){
        baidu_file::install(hook);
        bool installed=baidu_file::ready.load(std::memory_order_acquire);
        if(!installed)baidu_file::active.store(nullptr,std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","native_install host=baidu file_only=true installed=%d api=%u",installed,api);
        return installed;
    }
    if(host!=NativeHost::Amap)return false;
    int failed=0;
#define INSTALL(target,replacement,backup) failed+=hook(reinterpret_cast<void *>(target),reinterpret_cast<void *>(replacement),reinterpret_cast<void **>(&backup))!=0
    INSTALL(AAsset_getBuffer,asset_buffer,orig_buffer);INSTALL(AAsset_read,asset_read,orig_read);
    INSTALL(AAsset_seek,asset_seek,orig_seek);INSTALL(AAsset_seek64,asset_seek64,orig_seek64);
    INSTALL(AAsset_getLength,asset_length,orig_length);INSTALL(AAsset_getLength64,asset_length64,orig_length64);
    INSTALL(AAsset_getRemainingLength,asset_remaining,orig_remaining);INSTALL(AAsset_getRemainingLength64,asset_remaining64,orig_remaining64);
    INSTALL(AAsset_openFileDescriptor,asset_fd,orig_fd);INSTALL(AAsset_openFileDescriptor64,asset_fd64,orig_fd64);
    INSTALL(AAsset_close,asset_close,orig_close);
    if(!failed)INSTALL(AAssetManager_open,asset_open,orig_open);
#undef INSTALL
    __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","native_install host=amap asset_only=true failures=%d api=%u",failed,api);
    return failed==0;
}
extern "C" __attribute__((visibility("default"),used))
Loaded native_init(const NativeAPIEntries *entries){
    if(entries && entries->hook_func){
        nativeHooks.bind(entries->hook_func,entries->version);
        __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","native_init framework_ready=true api=%u",entries->version);
    }
    return on_loaded;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_ldxm666_mapclean_NativeLabelAssets_configure(JNIEnv *,jclass,jint app,jint mask){
    if(app!=1)return;
    bool accepted=nativeHooks.request(NativeHost::Amap,[&]{map=1;hidden=mask;});
    __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","configure map=%d hide_poi=%d accepted=%d",app,mask!=0,accepted);
}
