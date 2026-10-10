#include "../app/native/style_proto.h"
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
using namespace style;
static int assertions=0;
static void check(bool pass,const char *name){assertions++;if(!pass){std::fprintf(stderr,"FAIL %s\n",name);std::exit(1);}}
static Bytes child(const Bytes &b,uint32_t key,size_t index=0){Proto p(b.data(),b.size());Field f;while(p.next(f))if(f.key==key && f.wire==2){if(index--==0)return Bytes(f.value,f.value+f.size);}return {};}
static uint64_t value(const Bytes &b,uint32_t key){return get(b.data(),b.size(),key);}
static Bytes rule(uint32_t main,uint32_t sub,uint32_t id){Bytes b,s;number(b,1,main);number(b,2,sub);number(s,1,0);number(s,2,0);number(s,3,id);message(b,3,s);return b;}
static Bytes state(uint32_t id,uint32_t draw){Bytes b;number(b,1,id);number(b,3,0);number(b,5,draw);return b;}
static Bytes draw(uint32_t id){Bytes b,z,base,shape,frame,icon;number(z,1,3);number(z,2,21);message(base,1,z);number(base,2,0);number(shape,1,0);message(shape,2,base);number(icon,1,87);message(shape,3,icon);message(frame,1,shape);number(b,1,id);message(b,3,frame);return b;}
static Bytes group(uint32_t type,const Bytes &defs){Bytes b;number(b,1,type);message(b,3,defs);return b;}
static Bytes fixture(bool poi=true,bool suspended=false,bool unsafeTemplate=false){Bytes b,points,roads;
    if(poi){message(points,1,rule(12024,1,10));message(points,1,rule(12029,2,10));message(points,1,rule(12024,999,10));}
    message(points,1,rule(10001,1,10));message(points,1,rule(12000,1,10));
    message(points,2,state(10,suspended?404:100));
    if(unsafeTemplate){Bytes invalid;number(invalid,1,100);number(invalid,3,0);message(points,5,invalid);}
    else message(points,5,draw(100));
    message(roads,1,rule(20015,1,1100));message(roads,2,state(1100,10100));message(roads,5,draw(10100));
    message(b,1,group(0,points));message(b,1,group(1,roads));number(b,123,456);return b;
}
static void tests(){
    Bytes input=fixture(),output;int changed=0;
    check(amap(input.data(),input.size(),output,changed),"all POI transform succeeds");
    check(changed==3,"normal alternate and unknown POI subkeys all change");
    check(child(input,1,1)==child(output,1,1),"road geometry and names stay byte identical");
    auto before=child(child(input,1),3),after=child(child(output,1),3);
    check(child(before,1,3)==child(after,1,3),"geographic label sharing state stays byte identical");
    check(child(before,1,4)==child(after,1,4),"route search and custom markers stay byte identical");
    check(child(before,2)==child(after,2),"original shared state is untouched");
    check(child(before,5)==child(after,5),"original shared drawing is untouched");
    auto cloned=child(after,2,1);auto cloneId=value(cloned,1),drawId=value(cloned,5);
    check(cloneId>1100 && drawId>10100,"new IDs reserve above every geometry group");
    for(size_t i=0;i<3;i++)check(value(child(child(after,1,i),3),3)==cloneId,"each hidden family uses private state");
    auto cloneDraw=child(after,5,1);
    check(value(cloneDraw,1)==drawId,"private state refers to private drawing");
    auto shape=child(child(cloneDraw,3),1),z=child(child(shape,2),1);
    check(value(z,1)==31 && value(z,2)==32,"hidden drawing has no normal zoom levels");
    check(child(shape,3)==child(child(child(child(before,5),3),1),3),"icon resource and material stay valid");
    check(child(after,2,2).empty() && child(after,5,2).empty(),"shared clones are cached once");
    input=fixture(false);output.clear();changed=0;
    check(amap(input.data(),input.size(),output,changed) && output==input && !changed,"geography-only style remains byte identical");
    Bytes broken=fixture();broken.pop_back();output.clear();changed=0;
    check(!amap(broken.data(),broken.size(),output,changed),"truncated data fails safely");
    const uint8_t overflow[]={8,255,255,255,255,255,255,255,255,255,2};Proto p(overflow,sizeof(overflow));Field f;
    check(!p.next(f) && !p.valid,"varint overflow is rejected");
    input=fixture(true,true);output.clear();changed=0;int fallbackDrawings=0;
    check(amap(input.data(),input.size(),output,changed,&fallbackDrawings),"suspended POI drawing has safe private substitute");
    check(changed==3 && fallbackDrawings==1,"shared suspended drawing gets one counted cached substitute");
    before=child(child(input,1),3);after=child(child(output,1),3);
    check(child(before,2)==child(after,2),"suspended original state remains byte identical");
    check(child(before,5)==child(after,5),"safe template original remains byte identical");
    check(child(before,1,3)==child(after,1,3) && child(before,1,4)==child(after,1,4),"suspended geographic and custom rules remain byte identical");
    cloned=child(after,2,1);cloneDraw=child(after,5,1);
    check(value(cloned,5)==value(cloneDraw,1) && value(cloned,5)>10100,"suspended private state uses valid private drawing");
    shape=child(child(cloneDraw,3),1);z=child(child(shape,2),1);
    check(value(z,1)==31 && value(z,2)==32,"substitute is invisible at every supported zoom");
    check(child(shape,3)==child(child(child(child(before,5),3),1),3),"substitute preserves valid native icon resources");
    input=fixture(true,true,true);output.clear();changed=0;fallbackDrawings=0;
    check(!amap(input.data(),input.size(),output,changed,&fallbackDrawings),"no valid point template fails safely");
    check(fallbackDrawings==0,"failed template lookup records no successful substitution");
    auto originalFixture=fixture();Bytes multiple;
    message(multiple,1,child(originalFixture,1));message(multiple,1,child(originalFixture,1));
    message(multiple,1,child(originalFixture,1,1));output.clear();changed=0;
    check(amap(multiple.data(),multiple.size(),output,changed) && changed==6,"multiple point groups both transform");
    auto first=child(child(output,1),3),second=child(child(output,1,1),3);
    check(value(child(first,2,1),1)!=value(child(second,2,1),1),"private state IDs do not collide across point groups");
    check(value(child(first,5,1),1)!=value(child(second,5,1),1),"private drawing IDs do not collide across point groups");
    check(child(multiple,1,2)==child(output,1,2),"nonpoint geometry stays intact after multiple point groups");
    std::printf("NativeLabelTest OK assertions=%d\n",assertions);
}
static Bytes read(const char *path){std::ifstream f(path,std::ios::binary);check(bool(f),"input opens");return Bytes(std::istreambuf_iterator<char>(f),{});}
int main(int argc,char **argv){if(argc==1){tests();return 0;}if(argc!=3)return 2;auto b=read(argv[1]);Bytes out;int changed=0,fallbackDrawings=0;check(amap(b.data(),b.size(),out,changed,&fallbackDrawings),"actual scene parses");std::ofstream f(argv[2],std::ios::binary);f.write(reinterpret_cast<const char *>(out.data()),out.size());check(bool(f),"output writes");std::printf("ActualStyle OK rules=%d fallbackDraws=%d bytes=%zu\n",changed,fallbackDrawings,out.size());return 0;}
