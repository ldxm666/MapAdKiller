#pragma once
#include <fcntl.h>
#include <sys/stat.h>
#include <cstdarg>
#include <cstdio>
#include <tuple>
#include <vector>

// Immutable exact-file mappings. Every other file operation uses its original
// arguments. Old tables live until process exit, so lookups need no mutex.
namespace baidu_file {
struct Entry {
    std::string target;
    mutable std::atomic<bool> reported{false};
    explicit Entry(std::string path):target(std::move(path)){}
};
struct Table {std::unordered_map<std::string,Entry> paths;};
static std::atomic<const Table *> active{nullptr};
static std::atomic<bool> ready{false};
static std::atomic<bool> installStarted{false};
static std::mutex versionsMutex;
static std::vector<std::unique_ptr<Table>> versions;
static int (*original_open)(const char *,int,...);
static int (*original_openat)(int,const char *,int,...);
static FILE *(*original_fopen)(const char *,const char *);

static bool needsMode(int flags){
    if(flags&O_CREAT)return true;
#ifdef O_TMPFILE
    if((flags&O_TMPFILE)==O_TMPFILE)return true;
#endif
    return false;
}
static bool readOnly(int flags){
    return (flags&O_ACCMODE)==O_RDONLY && !(flags&(O_CREAT|O_TRUNC|O_APPEND)) && !needsMode(flags);
}
static const Entry *lookup(const char *path){
    auto *table=active.load(std::memory_order_acquire);
    if(!table || !path || path[0]!='/')return nullptr;
    auto found=table->paths.find(path);return found==table->paths.end()?nullptr:&found->second;
}
static void report(const Entry *entry,const char *api,bool consumed){
    if(!entry->reported.exchange(true,std::memory_order_relaxed)){
        const char *leaf=std::strrchr(entry->target.c_str(),'/');
        __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","baidu_style_file_%s api=%s style=%s",
                consumed?"consumed":"fallback",api,leaf?leaf+1:"sty");
    }
}
static int file_open(const char *path,int flags,...){
    mode_t mode=0;
    if(needsMode(flags)){va_list args;va_start(args,flags);mode=va_arg(args,int);va_end(args);}
    const Entry *entry=readOnly(flags)?lookup(path):nullptr;
    if(entry){
        int fd=original_open(entry->target.c_str(),flags,mode);
        if(fd>=0){report(entry,"open",true);return fd;}report(entry,"open",false);
    }
    return original_open(path,flags,mode);
}
static int file_openat(int directory,const char *path,int flags,...){
    mode_t mode=0;
    if(needsMode(flags)){va_list args;va_start(args,flags);mode=va_arg(args,int);va_end(args);}
    const Entry *entry=readOnly(flags)?lookup(path):nullptr;
    if(entry){
        int fd=original_openat(directory,entry->target.c_str(),flags,mode);
        if(fd>=0){report(entry,"openat",true);return fd;}report(entry,"openat",false);
    }
    return original_openat(directory,path,flags,mode);
}
static FILE *file_fopen(const char *path,const char *mode){
    const Entry *entry=mode && mode[0]=='r' && !std::strchr(mode,'+')?lookup(path):nullptr;
    if(entry){
        FILE *file=original_fopen(entry->target.c_str(),mode);
        if(file){report(entry,"fopen",true);return file;}report(entry,"fopen",false);
    }
    return original_fopen(path,mode);
}
static void install(Hook hook){
    if(installStarted.exchange(true,std::memory_order_acq_rel))return;
    int failures=0;
#define FILE_HOOK(name,replacement,backup) do {void *target=dlsym(RTLD_DEFAULT,name); failures+=!target || hook(target,reinterpret_cast<void *>(replacement),reinterpret_cast<void **>(&backup))!=0;}while(0)
    FILE_HOOK("open",file_open,original_open);
    FILE_HOOK("openat",file_openat,original_openat);
    FILE_HOOK("fopen",file_fopen,original_fopen);
#undef FILE_HOOK
    ready.store(failures==0,std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","baidu_file_hooks ready=%d failures=%d",failures==0,failures);
}
static bool configure(JNIEnv *env,jobjectArray originals,jobjectArray filtered){
    struct ClearOnFailure {
        bool success=false;
        ~ClearOnFailure(){if(!success)active.store(nullptr,std::memory_order_release);}
    } guard;
    if(!originals || !filtered)return false;
    jsize count=env->GetArrayLength(originals);
    if(count!=env->GetArrayLength(filtered) || count>512)return false;
    if(!count){active.store(nullptr,std::memory_order_release);guard.success=true;return true;}
    std::unique_ptr<Table> table(new Table());
    for(jsize i=0;i<count;i++){
        auto source=static_cast<jstring>(env->GetObjectArrayElement(originals,i));
        auto target=static_cast<jstring>(env->GetObjectArrayElement(filtered,i));
        if(!source || !target){if(source)env->DeleteLocalRef(source);if(target)env->DeleteLocalRef(target);return false;}
        const char *sourceChars=env->GetStringUTFChars(source,nullptr);
        const char *targetChars=env->GetStringUTFChars(target,nullptr);
        if(!sourceChars || !targetChars){
            if(sourceChars)env->ReleaseStringUTFChars(source,sourceChars);
            if(targetChars)env->ReleaseStringUTFChars(target,targetChars);
            env->DeleteLocalRef(source);env->DeleteLocalRef(target);return false;
        }
        std::string from(sourceChars),to(targetChars);
        env->ReleaseStringUTFChars(source,sourceChars);env->ReleaseStringUTFChars(target,targetChars);
        env->DeleteLocalRef(source);env->DeleteLocalRef(target);
        struct stat info{};
        if(from.empty() || from[0]!='/' || to.find("/code_cache/mcpoi/")==std::string::npos
                || lstat(to.c_str(),&info)!=0 || !S_ISREG(info.st_mode) || info.st_size<1 || info.st_size>16*1024*1024)return false;
        auto existing=table->paths.find(from);
        if(existing!=table->paths.end()){
            if(existing->second.target!=to)return false;
        }else table->paths.emplace(std::piecewise_construct,std::forward_as_tuple(from),std::forward_as_tuple(to));
    }
    // Validate before selecting the host. If the framework callback arrives
    // later, the request and immutable mapping wait for its install-once hook.
    auto *published=table.get();
    {std::lock_guard<std::mutex> guard(versionsMutex);versions.push_back(std::move(table));}
    if(!requestBaiduNative([&]{active.store(published,std::memory_order_release);}))return false;
    guard.success=true;
    __android_log_print(ANDROID_LOG_INFO,"MapCleanLabels","baidu_file_mapping exact_paths=%d",count);
    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_ldxm666_mapclean_NativeLabelAssets_configureBaidu(JNIEnv *env,jclass,jobjectArray originals,jobjectArray filtered){
    return baidu_file::configure(env,originals,filtered)?JNI_TRUE:JNI_FALSE;
}
