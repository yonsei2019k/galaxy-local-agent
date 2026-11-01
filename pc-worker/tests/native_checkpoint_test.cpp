#include "../native/ziphap_checkpoint.hpp"
#include <cassert>
#include <iostream>

struct Tensor {
    std::vector<float> values=std::vector<float>(6,0.25f);
    std::vector<int64_t> dimensions{2,3};
    const std::vector<int64_t>& shape()const{return dimensions;}
    int64_t numel()const{return values.size();}
    float* data(){return values.data();}
    const float* data()const{return values.data();}
};
static void step(Tensor& x,int i,const std::vector<float>& sigmas){
    for(auto& v:x.values){float denoised=v*0.8f;float d=(v-denoised)/sigmas[i];v+=d*(sigmas[i+1]-sigmas[i]);}
}
int wmain(int argc,wchar_t** argv){
    assert(argc==2);std::filesystem::path path=argv[1];std::filesystem::remove(path);
    _wputenv_s(L"ZIPHAP_EULER_CHECKPOINT",path.c_str());
    _putenv_s("ZIPHAP_CHECKPOINT_BINDING",std::string(64,'a').c_str());
    std::vector<float> sigmas;for(int i=12;i>=0;--i)sigmas.push_back(float(i)/12);
    Tensor uninterrupted;for(int i=0;i<12;i++)step(uninterrupted,i,sigmas);
    Tensor partial;ziphap::EulerCheckpoint first(sigmas);
    for(int i=0;i<5;i++){step(partial,i,sigmas);first.save(partial,i+1);}
    Tensor resumed;ziphap::EulerCheckpoint second(sigmas);int start=second.restore(resumed);assert(start==5);
    int calls=0;for(int i=start;i<12;i++){step(resumed,i,sigmas);second.save(resumed,i+1);calls++;}
    assert(calls==7&&resumed.values==uninterrupted.values);
    Tensor decodeOnly;assert(second.restore(decodeOnly)==12&&decodeOnly.values==uninterrupted.values);
    auto reject=[&](auto fn){bool failed=false;try{fn();}catch(const std::exception&){failed=true;}assert(failed);};
    _putenv_s("ZIPHAP_CHECKPOINT_BINDING",std::string(64,'b').c_str());
    reject([&]{ziphap::EulerCheckpoint wrong(sigmas);wrong.restore(decodeOnly);});
    _putenv_s("ZIPHAP_CHECKPOINT_BINDING",std::string(64,'a').c_str());
    auto schedule=sigmas;schedule[0]+=0.01f;
    reject([&]{ziphap::EulerCheckpoint wrong(schedule);wrong.restore(decodeOnly);});
    Tensor shape;shape.dimensions={3,2};reject([&]{second.restore(shape);});
    std::fstream blob(path,std::ios::binary|std::ios::in|std::ios::out);blob.seekg(20);char bad=0;blob.read(&bad,1);bad^=1;blob.seekp(20);blob.write(&bad,1);blob.close();
    reject([&]{second.restore(decodeOnly);});
    std::filesystem::remove(path);
    std::cout<<"EULER_CHECKPOINT_ENCRYPTION_RESUME_DECODE_BINDING_TAMPER_PASS\n";
}
