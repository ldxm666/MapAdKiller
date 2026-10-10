#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <memory>
#include <string>
#include <unordered_map>
#include <cstdlib>
#include <cerrno>
#include <cstdint>
using Hook=int (*)(void *,void *,void **);
template<class Prepare> static bool requestBaiduNative(Prepare prepare){prepare();return true;}
#include "../app/native/baidu_file_assets.h"

// Exercise the release wrappers themselves, with original libc operations
// mocked. No inline hooks are installed and no host application files are read.
struct Call {std::string api,path,mode;int flags=0,directory=0;mode_t createMode=0;};
static std::vector<Call> calls;
static int assertions=0;
static bool failReplacement=false;
static const char *source="/original/cfg/a/mode_1/map.sty";
static const char *replacement="/private/code_cache/mcpoi/test/mode_1/map.sty";
static void check(bool condition,const char *name){
    assertions++;if(!condition){std::fprintf(stderr,"FAIL %s\n",name);std::exit(1);}
}
static int result(const char *path){
    if(path && std::strcmp(path,replacement)==0){if(failReplacement){errno=ENOENT;return -1;}return 301;}
    return 302;
}
static int mockOpen(const char *path,int flags,...){
    Call call;call.api="open";call.path=path?path:"<null>";call.flags=flags;
    if(baidu_file::needsMode(flags)){va_list args;va_start(args,flags);call.createMode=va_arg(args,int);va_end(args);}
    calls.push_back(call);return result(path);
}
static int mockOpenAt(int directory,const char *path,int flags,...){
    Call call;call.api="openat";call.path=path?path:"<null>";call.flags=flags;call.directory=directory;
    if(baidu_file::needsMode(flags)){va_list args;va_start(args,flags);call.createMode=va_arg(args,int);va_end(args);}
    calls.push_back(call);return result(path);
}
static FILE *mockFopen(const char *path,const char *mode){
    Call call;call.api="fopen";call.path=path?path:"<null>";call.mode=mode?mode:"<null>";
    calls.push_back(call);int fd=result(path);
    return fd<0?nullptr:reinterpret_cast<FILE *>(static_cast<uintptr_t>(fd));
}
static void expectOne(const char *path,const char *name){
    check(calls.size()==1 && calls[0].path==path,name);
}
int main(){
    baidu_file::original_open=mockOpen;
    baidu_file::original_openat=mockOpenAt;
    baidu_file::original_fopen=mockFopen;
    baidu_file::Table table;
    table.paths.emplace(std::piecewise_construct,std::forward_as_tuple(source),std::forward_as_tuple(replacement));
    baidu_file::active.store(&table,std::memory_order_release);

    check(baidu_file::file_open(source,O_RDONLY)==301,"read-only open returns replacement result");
    expectOne(replacement,"read-only open changes exact source only");
    check(calls[0].flags==O_RDONLY,"read-only flags preserved");
    calls.clear();
    check(baidu_file::file_openat(42,source,O_RDONLY|O_CLOEXEC)==301,"read-only openat replaced");
    expectOne(replacement,"openat exact source changed");
    check(calls[0].directory==42 && calls[0].flags==(O_RDONLY|O_CLOEXEC),"openat directory and flags preserved");

    const char *unmapped[]={"mode_1/map.sty","/original/cfg/a/mode_2/map.sty","/original/cfg/a/mode_1/map.sty.bak","/other/cfg/a/mode_1/map.sty",replacement};
    for(const char *path:unmapped){
        calls.clear();check(baidu_file::file_open(path,O_RDONLY)==(std::strcmp(path,replacement)==0?301:302),"unmapped open preserves result");
        expectOne(path,"unmapped and relative open path unchanged");
        calls.clear();baidu_file::file_openat(77,path,O_RDONLY);expectOne(path,"unmapped and relative openat path unchanged");
        check(calls[0].directory==77,"unmapped openat directory unchanged");
        calls.clear();baidu_file::file_fopen(path,"rb");expectOne(path,"unmapped and relative fopen path unchanged");
    }
    const int writing[]={O_WRONLY,O_RDWR,O_RDONLY|O_CREAT,O_RDONLY|O_TRUNC,O_RDONLY|O_APPEND,O_RDWR|O_CREAT|O_TRUNC
#ifdef O_TMPFILE
        ,O_TMPFILE|O_RDWR,O_TMPFILE|O_RDONLY
#endif
    };
    for(int flags:writing){
        calls.clear();check(baidu_file::file_open(source,flags,0640)==302,"writing open returns original result");
        expectOne(source,"write/create/truncate/append open never replaced");
        check(calls[0].flags==flags,"writing open flags preserved");
        if(baidu_file::needsMode(flags))check(calls[0].createMode==0640,"open creation mode preserved");
        calls.clear();baidu_file::file_openat(91,source,flags,0600);expectOne(source,"writing openat never replaced");
        check(calls[0].flags==flags && calls[0].directory==91,"writing openat arguments preserved");
        if(baidu_file::needsMode(flags))check(calls[0].createMode==0600,"openat creation mode preserved");
    }
    for(const char *mode:{"r","rb","re"}){
        calls.clear();check(baidu_file::file_fopen(source,mode)==reinterpret_cast<FILE *>(301),"readonly fopen replacement result");
        expectOne(replacement,"readonly fopen replaced");check(calls[0].mode==mode,"fopen mode preserved");
    }
    for(const char *mode:{"r+","rb+","r+b","w","wb","w+","a","a+"}){
        calls.clear();check(baidu_file::file_fopen(source,mode)==reinterpret_cast<FILE *>(302),"writing fopen original result");
        expectOne(source,"writing fopen never replaced");check(calls[0].mode==mode,"writing fopen mode preserved");
    }
    failReplacement=true;
    calls.clear();check(baidu_file::file_open(source,O_RDONLY)==302,"failed replacement open falls back result");
    check(calls.size()==2 && calls[0].path==replacement && calls[1].path==source,"open fallback retries original exact path");
    calls.clear();check(baidu_file::file_openat(53,source,O_RDONLY|O_CLOEXEC)==302,"failed replacement openat falls back result");
    check(calls.size()==2 && calls[0].path==replacement && calls[1].path==source && calls[1].directory==53 && calls[1].flags==(O_RDONLY|O_CLOEXEC),"openat fallback preserves arguments");
    calls.clear();check(baidu_file::file_fopen(source,"rb")==reinterpret_cast<FILE *>(302),"failed replacement fopen falls back result");
    check(calls.size()==2 && calls[0].path==replacement && calls[1].path==source && calls[1].mode=="rb","fopen fallback preserves arguments");
    failReplacement=false;

    baidu_file::active.store(nullptr,std::memory_order_release);
    calls.clear();check(baidu_file::file_open(source,O_RDONLY)==302,"disabled open preserves original result");expectOne(source,"disabled open original path");
    calls.clear();baidu_file::file_openat(7,source,O_RDONLY);expectOne(source,"disabled openat original path");
    calls.clear();baidu_file::file_fopen(source,"rb");expectOne(source,"disabled fopen original path");
    baidu_file::Table empty;baidu_file::active.store(&empty,std::memory_order_release);
    calls.clear();baidu_file::file_open(source,O_RDONLY);expectOne(source,"empty table open passthrough");
    calls.clear();baidu_file::file_openat(8,source,O_RDONLY);expectOne(source,"empty table openat passthrough");
    calls.clear();baidu_file::file_fopen(source,"r");expectOne(source,"empty table fopen passthrough");
    baidu_file::active.store(nullptr,std::memory_order_release);
    std::printf("BaiduFileAssetsTest: %d assertions passed\n",assertions);return 0;
}
