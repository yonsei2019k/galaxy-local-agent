#include <jni.h>
#include "../cpu_features.h"
#include "stable-diffusion.h"
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Cancelled {};
struct JavaFailure {};
void require(bool condition,const char* message){if(!condition)throw std::invalid_argument(message);}
void throw_java(JNIEnv* env,const char* name,const char* message){
    if(env->ExceptionCheck())return;
    jclass type=env->FindClass(name);
    if(type){env->ThrowNew(type,message);env->DeleteLocalRef(type);}
}
void translate(JNIEnv* env){
    try{throw;}
    catch(const JavaFailure&){}
    catch(const Cancelled&){throw_java(env,"java/util/concurrent/CancellationException","Image generation cancelled");}
    catch(const ziphap_native::UnsupportedCpu& error){throw_java(env,"java/lang/UnsupportedOperationException",error.what());}
    catch(const std::invalid_argument& error){throw_java(env,"java/lang/IllegalArgumentException",error.what());}
    catch(const std::exception&){throw_java(env,"java/lang/IllegalStateException","Local image model, memory allocation, or generation failed");}
    catch(...){throw_java(env,"java/lang/IllegalStateException","Unexpected local image runtime failure");}
}
void discard_log(sd_log_level_t,const char*,void*){}
std::atomic<bool> singleton_active{false};

struct State {
    std::mutex operation_mutex;
    std::mutex context_mutex;
    std::mutex callback_mutex;
    std::atomic<bool> cancelled{false};
    std::atomic<bool> callback_failed{false};
    sd_ctx_t* context=nullptr;
    JavaVM* vm=nullptr;
    jobject progress_sink=nullptr;
    jmethodID progress_method=nullptr;
    ~State(){
        sd_set_progress_callback(nullptr,nullptr);
        if(context)free_sd_ctx(context);
        singleton_active.store(false,std::memory_order_release);
    }
};
std::mutex handles_mutex;
std::unordered_map<jlong,std::shared_ptr<State>> handles;
jlong next_handle=1;
std::shared_ptr<State> lookup(jlong handle,bool required=true){
    std::lock_guard<std::mutex> guard(handles_mutex);
    auto found=handles.find(handle);
    if(found!=handles.end())return found->second;
    if(required)throw std::invalid_argument("Invalid image runtime handle");
    return {};
}
void request_cancel(State& state){
    state.cancelled.store(true,std::memory_order_release);
    std::lock_guard<std::mutex> guard(state.context_mutex);
    if(state.context)sd_cancel_generation(state.context,SD_CANCEL_ALL);
}
void check_cancel(const State& state){if(state.cancelled.load(std::memory_order_acquire))throw Cancelled{};}

std::string utf8(JNIEnv* env,jstring value,size_t max_bytes){
    require(value!=nullptr,"Missing text argument");
    const jsize length=env->GetStringLength(value);
    const jchar* chars=env->GetStringChars(value,nullptr);
    if(!chars)throw JavaFailure{};
    std::string result;
    try{
        result.reserve(static_cast<size_t>(length));
        for(jsize i=0;i<length;++i){
            uint32_t cp=chars[i];
            if(cp>=0xD800&&cp<=0xDBFF){
                require(i+1<length&&chars[i+1]>=0xDC00&&chars[i+1]<=0xDFFF,"Invalid text encoding");
                cp=0x10000+((cp-0xD800)<<10)+(chars[++i]-0xDC00);
            }else require(cp<0xDC00||cp>0xDFFF,"Invalid text encoding");
            require(cp!=0,"NUL is not a valid text argument");
            if(cp<0x80)result.push_back(static_cast<char>(cp));
            else if(cp<0x800){result.push_back(static_cast<char>(0xC0|(cp>>6)));result.push_back(static_cast<char>(0x80|(cp&63)));}
            else if(cp<0x10000){result.push_back(static_cast<char>(0xE0|(cp>>12)));result.push_back(static_cast<char>(0x80|((cp>>6)&63)));result.push_back(static_cast<char>(0x80|(cp&63)));}
            else{result.push_back(static_cast<char>(0xF0|(cp>>18)));result.push_back(static_cast<char>(0x80|((cp>>12)&63)));result.push_back(static_cast<char>(0x80|((cp>>6)&63)));result.push_back(static_cast<char>(0x80|(cp&63)));}
            require(result.size()<=max_bytes,"Text argument exceeds byte limit");
        }
    }catch(...){env->ReleaseStringChars(value,chars);throw;}
    env->ReleaseStringChars(value,chars);
    return result;
}

void progress_callback(int step,int total,float seconds,void* data){
    auto& state=*static_cast<State*>(data);
    std::lock_guard<std::mutex> guard(state.callback_mutex);
    if(!state.progress_sink||!state.vm||state.cancelled.load(std::memory_order_acquire))return;
    JNIEnv* env=nullptr;
    bool attached=false;
    jint status=state.vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6);
    if(status==JNI_EDETACHED){
        if(state.vm->AttachCurrentThread(&env,nullptr)!=JNI_OK){request_cancel(state);return;}
        attached=true;
    }else if(status!=JNI_OK){request_cancel(state);return;}
    env->CallVoidMethod(state.progress_sink,state.progress_method,static_cast<jint>(step),static_cast<jint>(total),static_cast<jfloat>(seconds));
    if(env->ExceptionCheck()){
        env->ExceptionClear();state.callback_failed.store(true,std::memory_order_release);request_cancel(state);
    }
    if(attached)state.vm->DetachCurrentThread();
}

struct ProgressScope {
    JNIEnv* env;
    State& state;
    ProgressScope(JNIEnv* source,State& active,jobject sink):env(source),state(active){
        require(sink!=nullptr,"Missing image progress receiver");
        if(env->GetJavaVM(&state.vm)!=JNI_OK)throw std::runtime_error("Unable to access VM");
        jclass type=env->GetObjectClass(sink);
        if(!type)throw JavaFailure{};
        auto method=env->GetMethodID(type,"onStep","(IIF)V");
        env->DeleteLocalRef(type);
        if(!method)throw JavaFailure{};
        auto reference=env->NewGlobalRef(sink);
        if(!reference)throw JavaFailure{};
        {
            std::lock_guard<std::mutex> guard(state.callback_mutex);
            state.progress_sink=reference;state.progress_method=method;
        }
        state.callback_failed.store(false,std::memory_order_release);
        sd_set_progress_callback(progress_callback,&state);
    }
    ~ProgressScope(){
        sd_set_progress_callback(nullptr,nullptr);
        std::lock_guard<std::mutex> guard(state.callback_mutex);
        if(state.progress_sink)env->DeleteGlobalRef(state.progress_sink);
        state.progress_sink=nullptr;state.progress_method=nullptr;
    }
};
struct Images {
    sd_image_generation_result_t* value=nullptr;
    ~Images(){if(value)free_sd_image_generation_result(value);}
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_user_localagent_inference_ImageNative_nativeCreate(JNIEnv* env,jobject){
    try{
        ziphap_native::require_fp16_dotprod_cpu();
        bool expected=false;
        require(singleton_active.compare_exchange_strong(expected,true),"Another image context is active");
        std::shared_ptr<State> state;
        try{state=std::make_shared<State>();}catch(...){singleton_active.store(false);throw;}
        sd_set_log_callback(discard_log,nullptr);
        std::lock_guard<std::mutex> guard(handles_mutex);
        const jlong id=next_handle++;
        handles.emplace(id,std::move(state));
        return id;
    }catch(...){translate(env);return 0;}
}

extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_ImageNative_nativeLoad(JNIEnv* env,jobject,jlong handle,jstring path,jint threads){
    try{
        ziphap_native::require_fp16_dotprod_cpu();
        auto state=lookup(handle);
        std::lock_guard<std::mutex> operation(state->operation_mutex);
        check_cancel(*state);
        require(threads>=1&&threads<=4,"Image CPU thread count must be 1 to 4");
        const auto model_path=utf8(env,path,4096);
        require(!model_path.empty()&&model_path.front()=='/',"Model path must be absolute");
        sd_ctx_t* previous=nullptr;
        {
            std::lock_guard<std::mutex> guard(state->context_mutex);
            previous=state->context;state->context=nullptr;
        }
        if(previous)free_sd_ctx(previous);
        sd_ctx_params_t params;
        sd_ctx_params_init(&params);
        params.model_path=model_path.c_str();
        params.n_threads=threads;
        params.wtype=SD_TYPE_COUNT; // Preserve the verified checkpoint's stored weight types.
        params.enable_mmap=true;
        params.flash_attn=true;
        params.diffusion_flash_attn=true;
        params.disable_prefetch=true;
        params.conditioning_cache_size=0;
        params.backend="cpu";
        params.params_backend="cpu";
        auto* created=new_sd_ctx(&params);
        if(!created)throw std::runtime_error("Image model initialization failed");
        bool cancelled=false;
        {
            std::lock_guard<std::mutex> guard(state->context_mutex);
            cancelled=state->cancelled.load(std::memory_order_acquire);
            if(!cancelled)state->context=created;
        }
        if(cancelled){free_sd_ctx(created);throw Cancelled{};}
        require(sd_ctx_supports_image_generation(created),"The selected model does not support image generation");
    }catch(...){translate(env);}
}

extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_ImageNative_nativeResetCancellation(JNIEnv* env,jobject,jlong handle){
    try{
        auto state=lookup(handle);
        std::lock_guard<std::mutex> operation(state->operation_mutex);
        state->cancelled.store(false,std::memory_order_release);
        std::lock_guard<std::mutex> guard(state->context_mutex);
        if(state->context)sd_cancel_generation(state->context,SD_CANCEL_RESET);
    }catch(...){translate(env);}
}

extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_ImageNative_nativeCancel(JNIEnv* env,jobject,jlong handle){
    try{auto state=lookup(handle,false);if(state)request_cancel(*state);}
    catch(...){translate(env);}
}

extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_ImageNative_nativeDestroy(JNIEnv* env,jobject,jlong handle){
    try{
        std::shared_ptr<State> removed;
        {
            std::lock_guard<std::mutex> guard(handles_mutex);
            auto found=handles.find(handle);
            if(found!=handles.end()){removed=std::move(found->second);handles.erase(found);}
        }
        if(removed)request_cancel(*removed);
    }catch(...){translate(env);}
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_user_localagent_inference_ImageNative_nativeGenerate(JNIEnv* env,jobject,jlong handle,
    jstring prompt,jstring negative,jlong seed,jint steps,jint width,jint height,jfloat guidance,jint sampler,jobject sink){
    try{
        require(width>=256&&width<=512&&height>=256&&height<=512&&width%64==0&&height%64==0,"Invalid initial image dimensions");
        require(steps>=1&&steps<=8&&seed>=0&&guidance>=1&&guidance<=15,"Invalid initial image sampling parameters");
        require(sampler>=0&&sampler<=2,"Unsupported image sampler");
        const auto text=utf8(env,prompt,16000);
        const auto negative_text=utf8(env,negative,8000);
        require(!text.empty(),"Image prompt must not be empty");
        auto state=lookup(handle);
        std::lock_guard<std::mutex> operation(state->operation_mutex);
        check_cancel(*state);
        sd_ctx_t* context=nullptr;
        {std::lock_guard<std::mutex> guard(state->context_mutex);context=state->context;}
        require(context!=nullptr,"Image model is not loaded");
        ProgressScope callback(env,*state,sink);
        sd_img_gen_params_t params;
        sd_img_gen_params_init(&params);
        params.prompt=text.c_str();params.negative_prompt=negative_text.c_str();
        params.width=width;params.height=height;params.seed=seed;params.batch_count=1;
        params.sample_params.sample_steps=steps;
        params.sample_params.guidance.txt_cfg=guidance;
        params.sample_params.sample_method=sampler==0?LCM_SAMPLE_METHOD:(sampler==1?EULER_SAMPLE_METHOD:EULER_A_SAMPLE_METHOD);
        params.sample_params.scheduler=sd_get_default_scheduler(context,params.sample_params.sample_method);
        params.vae_tiling_params.enabled=true;
        params.vae_tiling_params.tile_size_w=256;params.vae_tiling_params.tile_size_h=256;
        Images images;
        const bool success=generate_image(context,&params,&images.value);
        check_cancel(*state);
        if(state->callback_failed.load(std::memory_order_acquire))throw std::runtime_error("Image progress receiver failed");
        require(success&&images.value&&images.value->count==1,"Image generation did not return one image");
        const auto& image=images.value->items[0].image;
        require(image.data&&image.width==static_cast<uint32_t>(width)&&image.height==static_cast<uint32_t>(height)&&
            (image.channel==3||image.channel==4),"Image output dimensions or channels are invalid");
        const size_t count=static_cast<size_t>(width)*static_cast<size_t>(height);
        std::vector<jint> pixels(count);
        for(size_t i=0;i<count;++i){
            const auto* pixel=image.data+i*image.channel;
            const uint32_t alpha=image.channel==4?pixel[3]:255;
            pixels[i]=static_cast<jint>((alpha<<24)|(static_cast<uint32_t>(pixel[0])<<16)|(static_cast<uint32_t>(pixel[1])<<8)|pixel[2]);
        }
        jintArray result=env->NewIntArray(static_cast<jsize>(count));
        if(!result)throw JavaFailure{};
        env->SetIntArrayRegion(result,0,static_cast<jsize>(count),pixels.data());
        if(env->ExceptionCheck()){env->DeleteLocalRef(result);throw JavaFailure{};}
        return result;
    }catch(...){translate(env);return nullptr;}
}
