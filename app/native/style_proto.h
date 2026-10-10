#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
#include <unordered_map>
#include <algorithm>

namespace style {
using Bytes=std::vector<uint8_t>;
struct Field {uint32_t key,wire;uint64_t number;const uint8_t *begin,*value,*end;size_t size;};
class Proto {
    const uint8_t *cursor,*limit;
    bool read(uint64_t &v){
        v=0;
        for(int i=0;i<10 && cursor<limit;i++){
            uint8_t b=*cursor++;if(i==9 && b>1)return false;
            v|=uint64_t(b&127)<<(i*7);if(!(b&128))return true;
        }
        return false;
    }
public:
    bool valid=true;
    Proto(const uint8_t *data,size_t size):cursor(data),limit(data+size){}
    bool next(Field &f){
        if(!valid || cursor==limit)return false;
        f.begin=cursor;uint64_t tag=0,n=0;
        if(!read(tag) || tag<8 || (tag>>3)>0x1fffffff){valid=false;return false;}
        f.key=uint32_t(tag>>3);f.wire=tag&7;f.number=0;
        if(f.wire==0){f.value=cursor;if(!read(f.number)){valid=false;return false;}f.size=cursor-f.value;}
        else{
            if(f.wire==2){if(!read(n)){valid=false;return false;}}
            else if(f.wire==1)n=8;else if(f.wire==5)n=4;else{valid=false;return false;}
            if(n>uint64_t(limit-cursor)){valid=false;return false;}
            f.value=cursor;f.size=size_t(n);cursor+=n;
        }
        f.end=cursor;return true;
    }
};
inline void varint(Bytes &out,uint64_t n){while(n>=128){out.push_back(uint8_t(n)|128);n>>=7;}out.push_back(uint8_t(n));}
inline void number(Bytes &out,uint32_t key,uint64_t n){varint(out,uint64_t(key)<<3);varint(out,n);}
inline void message(Bytes &out,uint32_t key,const Bytes &data){varint(out,(uint64_t(key)<<3)|2);varint(out,data.size());out.insert(out.end(),data.begin(),data.end());}
inline void copy(Bytes &out,const Field &f){out.insert(out.end(),f.begin,f.end);}
inline uint64_t get(const uint8_t *data,size_t size,uint32_t key,uint64_t fallback=0){Proto p(data,size);Field f;while(p.next(f))if(f.key==key && f.wire==0)return f.number;return fallback;}
inline bool hideZoom(const uint8_t *data,size_t size,int depth,Bytes &out){
    static constexpr uint32_t path[]={3,1,2,1};
    Proto p(data,size);Field f;int found=0;
    while(p.next(f)){
        if(depth==4){
            if((f.key==1 || f.key==2) && f.wire==0){number(out,f.key,f.key==1?31:32);found|=f.key==1?1:2;}
            else copy(out,f);
        }else if(f.key==path[depth] && f.wire==2){
            Bytes nested;if(!hideZoom(f.value,f.size,depth+1,nested))return false;
            message(out,f.key,nested);found++;
        }else copy(out,f);
    }
    return p.valid && (depth==4?found==3:found>0);
}
inline bool safePointDrawing(const Field &drawing){
    Proto p(drawing.value,drawing.size);Field f;bool found=false;
    while(p.next(f))if(f.key==3 && f.wire==2){
        Proto frame(f.value,f.size);Field shape;bool point=false;
        while(frame.next(shape))if(shape.key==1 && shape.wire==2){
            if(get(shape.value,shape.size,1,~uint64_t(0))!=0)return false;
            point=true;
        }
        if(!frame.valid || !point)return false;
        Bytes hidden;if(!hideZoom(f.value,f.size,1,hidden))return false;
        found=true;
    }
    return p.valid && found;
}
inline bool points(const uint8_t *data,size_t size,Bytes &out,int &changed,uint32_t &nextState,uint32_t &nextDraw,int *fallbackDrawings){
    std::unordered_map<uint32_t,Field> states,draws;
    std::unordered_map<uint32_t,uint32_t> stateCopies,drawCopies;
    Proto ids(data,size);Field def;
    while(ids.next(def))if((def.key==2 || def.key==5) && def.wire==2){
        uint64_t id=get(def.value,def.size,1);
        if(id>1000000)return false;
        if(def.key==2){states[uint32_t(id)]=def;nextState=std::max(nextState,uint32_t(id)+1);}
        else {draws[uint32_t(id)]=def;nextDraw=std::max(nextDraw,uint32_t(id)+1);}
    }
    if(!ids.valid || states.empty() || draws.empty())return false;
    // Some original Pub scenes contain POI states whose drawing IDs are not
    // defined by any scene asset. Only a hidden POI's private state may substitute
    // a valid point drawing. Original states/drawings and every geographic rule
    // remain byte-for-byte intact. Pick deterministically, never an empty style.
    const Field *safeTemplate=nullptr;uint32_t templateId=~uint32_t(0);
    for(const auto &candidate:draws)if(candidate.first<templateId && safePointDrawing(candidate.second)){
        templateId=candidate.first;safeTemplate=&candidate.second;
    }
    Bytes clonedStates,clonedDraws;
    auto cloneDraw=[&](uint32_t id,uint32_t &fresh)->bool{
        auto cached=drawCopies.find(id);if(cached!=drawCopies.end()){fresh=cached->second;return true;}
        auto original=draws.find(id);
        const Field *source=original==draws.end()?safeTemplate:&original->second;
        if(!source)return false;
        fresh=nextDraw++;
        Bytes filtered;Proto p(source->value,source->size);Field f;bool found=false;
        while(p.next(f)){
            if(f.key==1 && f.wire==0)number(filtered,1,fresh);
            else if(f.key==3 && f.wire==2){Bytes nested;if(!hideZoom(f.value,f.size,1,nested))return false;message(filtered,3,nested);found=true;}
            else copy(filtered,f);
        }
        if(!p.valid || !found)return false;
        if(original==draws.end() && fallbackDrawings)(*fallbackDrawings)++;
        message(clonedDraws,5,filtered);drawCopies[id]=fresh;return true;
    };
    auto cloneState=[&](uint32_t id,uint32_t &fresh)->bool{
        auto cached=stateCopies.find(id);if(cached!=stateCopies.end()){fresh=cached->second;return true;}
        auto original=states.find(id);if(original==states.end())return false;
        fresh=nextState++;
        Bytes filtered;Proto p(original->second.value,original->second.size);Field f;bool found=false;
        while(p.next(f)){
            if(f.key==1 && f.wire==0)number(filtered,1,fresh);
            else if(f.key==5 && f.wire==0){uint32_t newDraw=0;if(!cloneDraw(uint32_t(f.number),newDraw))return false;number(filtered,5,newDraw);found=true;}
            else copy(filtered,f);
        }
        if(!p.valid || !found)return false;
        message(clonedStates,2,filtered);stateCopies[id]=fresh;return true;
    };
    Proto p(data,size);Field f;
    while(p.next(f)){
        if(f.key!=1 || f.wire!=2){copy(out,f);continue;}
        uint64_t main=get(f.value,f.size,1);
        // Complete normal/alternate base-map POI families, including unclassified
        // and newly introduced subkeys. Geographic labels and result/route markers
        // use separate families and remain untouched.
        bool selected=main==12024 || main==12029;
        if(!selected){copy(out,f);continue;}
        Bytes rule;Proto r(f.value,f.size);Field state;
        while(r.next(state)){
            if(state.key!=3 || state.wire!=2){copy(rule,state);continue;}
            Bytes record;Proto s(state.value,state.size);Field v;bool found=false;
            while(s.next(v)){
                if(v.key==3 && v.wire==0){uint32_t fresh=0;if(!cloneState(uint32_t(v.number),fresh))return false;number(record,3,fresh);found=true;}
                else copy(record,v);
            }
            if(!s.valid || !found)return false;message(rule,3,record);
        }
        if(!r.valid)return false;message(out,1,rule);changed++;
    }
    if(!p.valid)return false;
    out.insert(out.end(),clonedStates.begin(),clonedStates.end());
    out.insert(out.end(),clonedDraws.begin(),clonedDraws.end());
    return true;
}
inline bool amap(const uint8_t *data,size_t size,Bytes &out,int &changed,int *fallbackDrawings=nullptr){
    // Definition IDs are shared by all geometry groups, not only the point group.
    // Reserve above every original ID so cloned point styles cannot replace roads,
    // buildings, or another point's drawing with an unrelated existing definition.
    uint32_t nextState=1,nextDraw=1;
    Proto reservation(data,size);Field original;
    while(reservation.next(original))if(original.key==1 && original.wire==2){
        Proto group(original.value,original.size);Field payload;
        while(group.next(payload))if(payload.key==3 && payload.wire==2){
            Proto defs(payload.value,payload.size);Field def;
            while(defs.next(def))if((def.key==2 || def.key==5) && def.wire==2){
                uint64_t id=get(def.value,def.size,1);
                if(id>1000000)return false;
                if(def.key==2)nextState=std::max(nextState,uint32_t(id)+1);
                else nextDraw=std::max(nextDraw,uint32_t(id)+1);
            }
            if(!defs.valid)return false;
        }
        if(!group.valid)return false;
    }
    if(!reservation.valid)return false;
    Proto root(data,size);Field group;
    while(root.next(group)){
        if(group.key!=1 || group.wire!=2 || get(group.value,group.size,1,~uint64_t(0))!=0){copy(out,group);continue;}
        Bytes g;Proto p(group.value,group.size);Field f;
        while(p.next(f)){
            if(f.key!=3 || f.wire!=2){copy(g,f);continue;}
            Bytes filtered;int count=0;
            if(!points(f.value,f.size,filtered,count,nextState,nextDraw,fallbackDrawings))return false;
            changed+=count;message(g,3,filtered);
        }
        if(!p.valid)return false;
        message(out,1,g);
    }
    return root.valid;
}
}
