// Audio-only Qwen3-ASR adapter, using the already pinned official llama.cpp/libmtmd.
// No network, subprocess, tools, personal-context lookup or audio logging occurs here.
#include <jni.h>
#include "cpu_features.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <malloc.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr int CONTEXT = 2048;
constexpr int BATCH = 32;
constexpr int OUTPUT = 512;
using Clock=std::chrono::steady_clock;
struct Cancelled{};
struct JavaPending{};
void require(bool value,const char * message){if(!value)throw std::invalid_argument(message);}
void throw_java(JNIEnv * env,const char * kind,const char * message){if(env->ExceptionCheck())return;auto cls=env->FindClass(kind);if(cls){env->ThrowNew(cls,message);env->DeleteLocalRef(cls);}}
void translate(JNIEnv * env){
    try{throw;}
    catch(const JavaPending &){}
    catch(const Cancelled &){throw_java(env,"java/util/concurrent/CancellationException","Audio recognition cancelled");}
    catch(const ziphap_native::UnsupportedCpu & e){throw_java(env,"java/lang/UnsupportedOperationException",e.what());}
    catch(const std::invalid_argument & e){throw_java(env,"java/lang/IllegalArgumentException",e.what());}
    catch(...){throw_java(env,"java/lang/IllegalStateException","Local speech model loading or audio decoding failed");}
}
std::string ascii(JNIEnv * env,jstring value,size_t limit){
    require(value&&static_cast<size_t>(env->GetStringLength(value))<=limit,"Invalid audio model parameter");
    const char * chars=env->GetStringUTFChars(value,nullptr);if(!chars)throw JavaPending{};
    std::string out(chars);env->ReleaseStringUTFChars(value,chars);
    require(out.size()<=limit&&std::all_of(out.begin(),out.end(),[](unsigned char c){return c>0&&c<128;}),"Owned model/cache paths must be ASCII");return out;
}
jstring utf8(JNIEnv * env,const std::string & value){
    require(value.size()<=128*1024,"Audio text exceeded output bound");
    auto bytes=env->NewByteArray(static_cast<jsize>(value.size()));if(!bytes)throw JavaPending{};
    env->SetByteArrayRegion(bytes,0,static_cast<jsize>(value.size()),reinterpret_cast<const jbyte *>(value.data()));
    auto cls=env->FindClass("java/lang/String");if(!cls){env->DeleteLocalRef(bytes);throw JavaPending{};}
    auto ctor=env->GetMethodID(cls,"<init>","([BLjava/lang/String;)V");if(!ctor){env->DeleteLocalRef(cls);env->DeleteLocalRef(bytes);throw JavaPending{};}
    auto charset=env->NewStringUTF("UTF-8");
    auto result=static_cast<jstring>(env->NewObject(cls,ctor,bytes,charset));
    env->DeleteLocalRef(charset);env->DeleteLocalRef(bytes);env->DeleteLocalRef(cls);if(!result)throw JavaPending{};return result;
}
struct State {
    std::mutex mutex;std::atomic<bool> cancelled{false};llama_model * model=nullptr;llama_context * context=nullptr;mtmd_context * audio=nullptr;
    ~State(){if(audio)mtmd_free(audio);if(context)llama_free(context);if(model)llama_model_free(model);mallopt(M_PURGE,0);}
};
std::mutex handles_mutex;std::unordered_map<jlong,std::shared_ptr<State>> handles;jlong next_handle=1;std::once_flag initialized;
void quiet(ggml_log_level,const char *,void *){}
void check(const State & state){if(state.cancelled.load(std::memory_order_relaxed))throw Cancelled{};}
bool aborting(void * data){return static_cast<State *>(data)->cancelled.load(std::memory_order_relaxed);}
bool progress(float,void * data){return !aborting(data);}
bool eval_callback(ggml_tensor * tensor,bool ask,void * data){if(ask)return tensor->op==GGML_OP_MUL_MAT||tensor->op==GGML_OP_RMS_NORM||aborting(data);return !aborting(data);}
std::shared_ptr<State> get(jlong handle){std::lock_guard<std::mutex> guard(handles_mutex);auto it=handles.find(handle);require(it!=handles.end(),"Audio model is closed");return it->second;}
double milliseconds(Clock::time_point first,Clock::time_point last){return std::chrono::duration<double,std::milli>(last-first).count();}
std::string piece(const llama_vocab * vocab,llama_token token){
    std::vector<char> value(256);int size=llama_token_to_piece(vocab,token,value.data(),static_cast<int>(value.size()),0,true);
    if(size<0){require(size>=-128*1024,"Invalid audio token");value.resize(static_cast<size_t>(-size));size=llama_token_to_piece(vocab,token,value.data(),static_cast<int>(value.size()),0,true);}
    require(size>=0&&static_cast<size_t>(size)<=value.size(),"Invalid speech token");return std::string(value.data(),static_cast<size_t>(size));
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_user_localagent_inference_QwenAsrNative_create(JNIEnv * env,jobject){
    try {
        ziphap_native::require_fp16_dotprod_cpu();
        std::call_once(initialized,[]{llama_log_set(quiet,nullptr);mtmd_helper_log_set(quiet,nullptr);ggml_log_set(quiet,nullptr);llama_backend_init();});
        std::lock_guard<std::mutex> guard(handles_mutex);require(handles.empty(),"One speech model may be loaded at a time");
        const auto id=next_handle++;handles.emplace(id,std::make_shared<State>());return id;
    }catch(...){translate(env);return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_com_user_localagent_inference_QwenAsrNative_load(JNIEnv * env,jobject,jlong handle,jstring model_path,jstring projector_path,jint threads){
    try {
        require(threads==2||threads==4,"Invalid speech CPU thread count");auto state=get(handle);std::lock_guard<std::mutex> guard(state->mutex);check(*state);
        require(!state->model&&!state->context&&!state->audio,"Audio model already loaded");
        auto params=llama_model_default_params();params.n_gpu_layers=0;params.load_mode=LLAMA_LOAD_MODE_MMAP;params.load_mtp=false;params.check_tensors=true;params.progress_callback=progress;params.progress_callback_user_data=state.get();
        const auto model_file=ascii(env,model_path,4096);state->model=llama_model_load_from_file(model_file.c_str(),params);check(*state);require(state->model,"Unable to load speech GGUF");
        char architecture[64]={};const auto length=llama_model_meta_val_str(state->model,"general.architecture",architecture,sizeof(architecture));
        require(length>0&&length<64&&std::string(architecture)=="qwen3vl","Only verified Qwen3-ASR models are enabled");
        auto config=llama_context_default_params();config.n_ctx=CONTEXT;config.n_batch=BATCH;config.n_ubatch=BATCH;config.n_seq_max=1;config.n_threads=threads;config.n_threads_batch=threads;config.offload_kqv=false;config.op_offload=false;config.no_perf=true;config.abort_callback=aborting;config.abort_callback_data=state.get();
        state->context=llama_init_from_model(state->model,config);check(*state);require(state->context&&llama_n_ctx(state->context)==CONTEXT,"Unable to create speech context");
        auto encoder=mtmd_context_params_default();encoder.use_gpu=false;encoder.n_threads=threads;encoder.print_timings=false;encoder.warmup=false;encoder.batch_max_tokens=512;encoder.progress_callback=progress;encoder.progress_callback_user_data=state.get();encoder.cb_eval=eval_callback;encoder.cb_eval_user_data=state.get();
        const auto projector=ascii(env,projector_path,4096);state->audio=mtmd_init_from_file(projector.c_str(),state->model,encoder);check(*state);
        require(state->audio&&mtmd_support_audio(state->audio)&&!mtmd_support_vision(state->audio),"Expected the matching audio-only speech encoder");
        require(mtmd_get_audio_sample_rate(state->audio)==16000,"Speech encoder must use 16 kHz audio");
    }catch(...){translate(env);}
}
extern "C" JNIEXPORT void JNICALL Java_com_user_localagent_inference_QwenAsrNative_resetCancellation(JNIEnv * env,jobject,jlong handle){try{get(handle)->cancelled.store(false);}catch(...){translate(env);}}
extern "C" JNIEXPORT void JNICALL Java_com_user_localagent_inference_QwenAsrNative_cancel(JNIEnv *,jobject,jlong handle){std::lock_guard<std::mutex> guard(handles_mutex);auto it=handles.find(handle);if(it!=handles.end())it->second->cancelled.store(true);}
extern "C" JNIEXPORT void JNICALL Java_com_user_localagent_inference_QwenAsrNative_destroy(JNIEnv *,jobject,jlong handle){std::shared_ptr<State> state;{std::lock_guard<std::mutex> guard(handles_mutex);auto it=handles.find(handle);if(it==handles.end())return;state=std::move(it->second);handles.erase(it);}state->cancelled.store(true);}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_user_localagent_inference_QwenAsrNative_transcribe(JNIEnv * env,jobject,jlong handle,jstring wav_path,jstring language_value){
    try {
        auto state=get(handle);std::lock_guard<std::mutex> guard(state->mutex);require(state->model&&state->context&&state->audio,"Speech model is not loaded");check(*state);
        const auto language=ascii(env,language_value,8);require(language=="ko"||language=="en"||language=="auto","Unsupported speech language option");
        const auto started=Clock::now();const auto path=ascii(env,wav_path,4096);
        auto wrapper=mtmd_helper_bitmap_init_from_file(state->audio,path.c_str(),false,mtmd_helper_init_opt_default());
        require(!wrapper.video_ctx,"Video is not a speech input");
        std::unique_ptr<mtmd_bitmap,decltype(&mtmd_bitmap_free)> bitmap(wrapper.bitmap,mtmd_bitmap_free);
        require(bitmap&&mtmd_bitmap_is_audio(bitmap.get()),"Expected a short WAV speech chunk");
        // Matches the official Qwen3-ASR chat_template.json; audio data is interleaved
        // as embeddings, never text that could create new roles or tool authority.
        const std::string prefix="<|im_start|>system\n<|im_end|>\n<|im_start|>user\n";
        const std::string suffix="<|im_end|>\n<|im_start|>assistant\n"+(language=="auto"?std::string():std::string("language ")+(language=="ko"?"Korean":"English")+"<asr_text>");
        mtmd_input_text before{prefix.data(),prefix.size(),false,true},after{suffix.data(),suffix.size(),false,true};
        mtmd_input_part first{&before,nullptr},media{nullptr,bitmap.get()},last{&after,nullptr};
        const mtmd_input_part * parts[]={&first,&media,&last};
        std::unique_ptr<mtmd_input_chunks,decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(),mtmd_input_chunks_free);
        require(chunks&&mtmd_tokenize_from_parts(state->audio,chunks.get(),parts,3,true)==0,"Unable to prepare speech embeddings");
        const int input_tokens=static_cast<int>(mtmd_helper_get_n_tokens(chunks.get()));const int positions=mtmd_helper_get_n_pos(chunks.get());
        require(input_tokens>0&&input_tokens+OUTPUT+8<=CONTEXT&&positions+OUTPUT+8<=CONTEXT,"Speech chunk is too long; shorten it");
        llama_memory_clear(llama_get_memory(state->context),true);
        struct Clear{llama_context * value;~Clear(){llama_memory_clear(llama_get_memory(value),true);}}clear{state->context};
        llama_pos actual=0;const int encoded=mtmd_helper_eval_chunks(state->audio,state->context,chunks.get(),0,0,BATCH,true,&actual);check(*state);require(encoded==0&&actual==positions,"Speech encoding failed");
        const auto prefilled=Clock::now();
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
        std::unique_ptr<llama_batch_ext,decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(state->context),llama_batch_ext_free);
        require(sampler&&batch,"Unable to allocate speech decoder");
        const auto * vocab=llama_model_get_vocab(state->model);std::string text;int output_tokens=0;bool ended=false;
        for(int i=0;i<OUTPUT;i++){
            check(*state);const auto token=llama_sampler_sample(sampler.get(),state->context,-1);
            if(llama_vocab_is_eog(vocab,token)){ended=true;break;}
            text+=piece(vocab,token);output_tokens++;require(text.size()<=128*1024,"Speech output is too large");
            llama_batch_ext_clear(batch.get());const int index=llama_batch_ext_add_token(batch.get(),0,token);require(index>=0,"Invalid speech decoder batch");
            const llama_pos pos=positions+i;const llama_pos coordinates[4]={pos,pos,pos,pos};
            require(llama_batch_ext_set_pos(batch.get(),index,coordinates)&&llama_batch_ext_set_output_logits(batch.get(),index,true),"Invalid speech decoder position");
            const int decoded=llama_process(state->context,LLAMA_PROCESS_TYPE_DECODE,batch.get());check(*state);require(decoded==0,"Speech decoding failed");
        }
        require(ended,"Speech output reached its limit; use a shorter chunk");check(*state);
        const auto finished=Clock::now();
        const std::string values[]={text,std::to_string(input_tokens),std::to_string(output_tokens),std::to_string(milliseconds(started,prefilled)),std::to_string(milliseconds(prefilled,finished))};
        auto cls=env->FindClass("java/lang/String");if(!cls)throw JavaPending{};auto result=env->NewObjectArray(5,cls,nullptr);env->DeleteLocalRef(cls);if(!result)throw JavaPending{};
        for(jsize i=0;i<5;i++){auto value=utf8(env,values[i]);env->SetObjectArrayElement(result,i,value);env->DeleteLocalRef(value);if(env->ExceptionCheck())throw JavaPending{};}
        return result;
    }catch(...){translate(env);return nullptr;}
}
