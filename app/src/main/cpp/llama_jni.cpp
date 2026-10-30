// ZipHap Android JNI adapter. Upstream llama.cpp v0.8.0 is pinned in CMakeLists.txt.
// No network, subprocesses, tool execution, or persistent conversation history here.
#include <jni.h>
#include "llama.h"
#include "chat.h"
#include "log.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstddef>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {
constexpr size_t MAX_INPUT_BYTES = 256 * 1024;
constexpr size_t MAX_OUTPUT_BYTES = 1024 * 1024;
constexpr int MAX_HISTORY_TURNS = 64;
constexpr int BATCH_TOKENS = 128;
constexpr int CPU_THREADS = 4;
using Clock = std::chrono::steady_clock;
struct Cancelled {};
struct PendingJavaException {};

void require(bool condition, const char * message) {
    if (!condition) throw std::invalid_argument(message);
}
void throw_java(JNIEnv * env, const char * type, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception) {
        env->ThrowNew(exception, message); // All adapter exception messages are ASCII.
        env->DeleteLocalRef(exception);
    }
}
void translate_exception(JNIEnv * env) {
    try { throw; }
    catch (const PendingJavaException &) { }
    catch (const Cancelled &) { throw_java(env, "java/util/concurrent/CancellationException", "Native inference cancelled"); }
    catch (const std::invalid_argument & error) { throw_java(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::exception &) { throw_java(env, "java/lang/IllegalStateException", "llama.cpp model, template, allocation, or decode operation failed"); }
    catch (...) { throw_java(env, "java/lang/IllegalStateException", "Unexpected llama.cpp native failure"); }
}
void discard_log(ggml_log_level, const char *, void *) {}
std::once_flag backend_once;
void initialize_backend() {
    std::call_once(backend_once, [] {
        // Upstream parser warnings can contain full generated text. Never log user content.
        common_log_set_verbosity_thold(-1);
        common_log_set_stdo(common_log_main(), false);
        common_log_pause(common_log_main());
        llama_log_set(discard_log, nullptr);
        ggml_log_set(discard_log, nullptr);
        llama_backend_init();
    });
}

struct State {
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    common_chat_templates_ptr templates;
    int context_size = 4096;
    ~State() {
        templates.reset();
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::mutex handles_mutex;
std::unordered_map<jlong, std::shared_ptr<State>> handles;
jlong next_handle = 1;
std::shared_ptr<State> get_state(jlong handle) {
    std::lock_guard<std::mutex> lock(handles_mutex);
    auto found = handles.find(handle);
    if (found == handles.end()) throw std::invalid_argument("Native model handle is closed");
    return found->second;
}
void check_cancelled(const State & state) {
    if (state.cancelled.load(std::memory_order_relaxed)) throw Cancelled{};
}
bool abort_compute(void * data) { return static_cast<State *>(data)->cancelled.load(std::memory_order_relaxed); }
bool load_progress(float, void * data) { return !abort_compute(data); }

// JNI Modified UTF-8 cannot represent supplementary Unicode correctly. Decode Java
// UTF-16 explicitly, preserving Korean, emoji, embedded scalar boundaries, and lengths.
std::string from_java(JNIEnv * env, jstring value, size_t maximum = MAX_INPUT_BYTES) {
    require(value != nullptr, "Null text is not allowed");
    const jsize length = env->GetStringLength(value);
    require(static_cast<size_t>(length) <= maximum, "Text exceeds byte safety limit");
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (!chars) throw PendingJavaException{};
    std::string text;
    try {
        text.reserve(static_cast<size_t>(length));
        for (jsize i = 0; i < length; ++i) {
            uint32_t cp = chars[i];
            if (cp >= 0xD800 && cp <= 0xDBFF) {
                require(i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF, "Unpaired UTF-16 surrogate");
                cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[++i] - 0xDC00);
            } else require(cp < 0xDC00 || cp > 0xDFFF, "Unpaired UTF-16 surrogate");
            require(cp != 0, "NUL characters are unsupported");
            if (cp <= 0x7F) text.push_back(static_cast<char>(cp));
            else if (cp <= 0x7FF) {
                text.push_back(static_cast<char>(0xC0 | (cp >> 6)));
                text.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else if (cp <= 0xFFFF) {
                text.push_back(static_cast<char>(0xE0 | (cp >> 12)));
                text.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                text.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else {
                text.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                text.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                text.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                text.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            }
            require(text.size() <= maximum, "Text exceeds byte safety limit");
        }
    } catch (...) { env->ReleaseStringChars(value, chars); throw; }
    env->ReleaseStringChars(value, chars);
    return text;
}

std::vector<common_chat_msg> messages_from_java(JNIEnv * env, jstring system, jobjectArray roles, jobjectArray texts, jstring user) {
    require(roles != nullptr && texts != nullptr, "History arrays are required");
    const jsize count = env->GetArrayLength(roles);
    require(count == env->GetArrayLength(texts) && count <= MAX_HISTORY_TURNS, "Invalid history size");
    std::vector<common_chat_msg> messages;
    size_t bytes = 0;
    auto append = [&](std::string role, std::string text) {
        bytes += text.size();
        require(bytes <= MAX_INPUT_BYTES, "Combined input exceeds 256 KiB");
        common_chat_msg message;
        message.role = std::move(role);
        message.content = std::move(text);
        messages.push_back(std::move(message));
    };
    auto instruction = from_java(env, system);
    if (!instruction.empty()) append("system", std::move(instruction));
    for (jsize i = 0; i < count; ++i) {
        auto role_value = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto text_value = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
        try {
            auto role = from_java(env, role_value, 16);
            require(role == "user" || role == "assistant", "History roles must be user or assistant");
            append(std::move(role), from_java(env, text_value));
        } catch (...) {
            if (role_value) env->DeleteLocalRef(role_value);
            if (text_value) env->DeleteLocalRef(text_value);
            throw;
        }
        env->DeleteLocalRef(role_value);
        env->DeleteLocalRef(text_value);
    }
    auto request = from_java(env, user);
    require(!request.empty(), "User prompt is empty");
    append("user", std::move(request));
    return messages;
}

std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab, const common_chat_prompt & prompt) {
    std::vector<llama_token> tokens;
    bool add_special = true;
    size_t total_bytes = 0;
    for (const auto & part : prompt.parts) {
        total_bytes += part.text.size();
        require(total_bytes <= MAX_INPUT_BYTES + 64 * 1024, "Rendered prompt exceeds safety limit");
        if (part.text.empty()) continue;
        // Template delimiters are special; literal user/history parts must not become roles.
        auto part_tokens = common_tokenize(vocab, part.text, add_special, !part.is_input);
        add_special = false;
        tokens.insert(tokens.end(), part_tokens.begin(), part_tokens.end());
        require(tokens.size() <= MAX_INPUT_BYTES, "Tokenized prompt exceeds safety limit");
    }
    require(!tokens.empty(), "Template produced an empty prompt");
    return tokens;
}
struct PreparedChat {
    std::unique_ptr<common_chat_session> session;
    std::vector<llama_token> tokens;
};
PreparedChat prepare_chat(State & state, std::vector<common_chat_msg> messages, bool thinking, int reserved_output, bool trim_history) {
    const auto * vocab = llama_model_get_vocab(state.model);
    const bool has_system = !messages.empty() && messages.front().role == "system";
    const size_t history_start = has_system ? 1 : 0;
    while (true) {
        check_cancelled(state);
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.use_jinja = true;
        inputs.enable_thinking = thinking;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_NONE;
        inputs.parallel_tool_calls = false;
        inputs.add_generation_prompt = true;
        auto session = std::make_unique<common_chat_session>(state.templates.get(), vocab, inputs);
        auto tokens = tokenize_prompt(vocab, session->prompt());
        if (!trim_history || tokens.size() + static_cast<size_t>(reserved_output) + 8 <= static_cast<size_t>(state.context_size)) {
            return {std::move(session), std::move(tokens)};
        }
        // Preserve trusted system instruction + current request. Drop only oldest complete
        // history exchanges; oversized current documents fail explicitly, never truncate silently.
        require(messages.size() > history_start + 1, "Current request and system instruction exceed model context; shorten or split the input");
        messages.erase(messages.begin() + static_cast<ptrdiff_t>(history_start));
        while (messages.size() > history_start + 1 && messages[history_start].role == "assistant") {
            messages.erase(messages.begin() + static_cast<ptrdiff_t>(history_start));
        }
    }
}

class Utf16Emitter {
public:
    Utf16Emitter(JNIEnv * env, jobject sink, State & state) : env_(env), sink_(sink), state_(state) {
        require(sink != nullptr, "Token callback is required");
        jclass cls = env_->GetObjectClass(sink);
        method_ = env_->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env_->DeleteLocalRef(cls);
        if (!method_) throw PendingJavaException{};
    }
    void append(const std::string & bytes, bool final = false) {
        total_ += bytes.size();
        require(total_ <= MAX_OUTPUT_BYTES, "Generated text exceeds 1 MiB safety limit");
        pending_ += bytes;
        std::vector<jchar> utf16;
        size_t i = 0;
        while (i < pending_.size()) {
            const auto lead = static_cast<unsigned char>(pending_[i]);
            size_t width = 1;
            uint32_t cp = lead;
            if (lead < 0x80) { }
            else if (lead >= 0xC2 && lead <= 0xDF) { width = 2; cp = lead & 0x1F; }
            else if (lead >= 0xE0 && lead <= 0xEF) { width = 3; cp = lead & 0x0F; }
            else if (lead >= 0xF0 && lead <= 0xF4) { width = 4; cp = lead & 0x07; }
            else { utf16.push_back(0xFFFD); ++i; continue; }
            if (i + width > pending_.size()) {
                if (!final) break;
                utf16.push_back(0xFFFD); ++i; continue;
            }
            bool valid = true;
            for (size_t k = 1; k < width; ++k) {
                const auto next = static_cast<unsigned char>(pending_[i + k]);
                if ((next & 0xC0) != 0x80) { valid = false; break; }
                cp = (cp << 6) | (next & 0x3F);
            }
            if (!valid || (width == 2 && cp < 0x80) || (width == 3 && cp < 0x800) ||
                (width == 4 && cp < 0x10000) || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                utf16.push_back(0xFFFD); ++i; continue;
            }
            if (cp <= 0xFFFF) utf16.push_back(static_cast<jchar>(cp));
            else {
                cp -= 0x10000;
                utf16.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
                utf16.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
            }
            i += width;
        }
        pending_.erase(0, i);
        if (utf16.empty()) return;
        check_cancelled(state_);
        jstring text = env_->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
        if (!text) throw PendingJavaException{};
        const jboolean keep_going = env_->CallBooleanMethod(sink_, method_, text);
        env_->DeleteLocalRef(text);
        if (env_->ExceptionCheck()) throw PendingJavaException{};
        if (!keep_going) { state_.cancelled.store(true); throw Cancelled{}; }
        if (!has_visible_) { first_visible_ = Clock::now(); has_visible_ = true; }
    }
    bool has_visible() const { return has_visible_; }
    Clock::time_point first_visible() const { return first_visible_; }
private:
    JNIEnv * env_;
    jobject sink_;
    State & state_;
    jmethodID method_ = nullptr;
    std::string pending_;
    size_t total_ = 0;
    bool has_visible_ = false;
    Clock::time_point first_visible_;
};

using Batch = std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)>;
void evaluate(State & state, llama_batch_ext * batch, const llama_token * tokens, int count, int position) {
    check_cancelled(state);
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        const int index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        require(index >= 0, "Unable to create native token batch");
        const llama_pos pos = position + i;
        require(llama_batch_ext_set_pos(batch, index, &pos), "Unable to set native token position");
    }
    require(llama_batch_ext_set_output_logits(batch, count - 1, true), "Unable to request native logits");
    const int code = llama_process(state.context, LLAMA_PROCESS_TYPE_DECODE, batch);
    check_cancelled(state);
    if (code != 0) throw std::runtime_error("Native decode operation failed");
}
std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int length = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, true);
    if (length < 0) {
        require(length != std::numeric_limits<int>::min() && -length <= static_cast<int>(MAX_OUTPUT_BYTES), "Token piece exceeds safety limit");
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, true);
    }
    require(length >= 0 && static_cast<size_t>(length) <= buffer.size(), "Unable to decode generated token");
    return std::string(buffer.data(), static_cast<size_t>(length));
}
double seconds(Clock::time_point first, Clock::time_point last) { return std::chrono::duration<double>(last - first).count(); }
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeCreate(JNIEnv * env, jobject) {
    try {
        initialize_backend();
        auto state = std::make_shared<State>();
        std::lock_guard<std::mutex> lock(handles_mutex);
        require(handles.empty(), "Only one llama.cpp model may be resident");
        require(next_handle < std::numeric_limits<jlong>::max(), "Native handle space exhausted");
        const jlong handle = next_handle++;
        handles.emplace(handle, std::move(state));
        return handle;
    } catch (...) { translate_exception(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeLoad(JNIEnv * env, jobject, jlong handle, jstring path, jint context_size, jint threads) {
    try {
        require(context_size == 4096 && threads == CPU_THREADS, "Native runtime requires 4096 context and four CPU threads");
        auto state = get_state(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        require(!state->model && !state->context, "Native model is already initialized");
        check_cancelled(*state);
        const auto model_path = from_java(env, path, 4096);
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.load_mtp = false;
        model_params.check_tensors = true;
        model_params.progress_callback = load_progress;
        model_params.progress_callback_user_data = state.get();
        state->model = llama_model_load_from_file(model_path.c_str(), model_params);
        check_cancelled(*state);
        if (!state->model) throw std::runtime_error("Unable to load GGUF model");
        char architecture[64] = {};
        const int arch_length = llama_model_meta_val_str(state->model, "general.architecture", architecture, sizeof(architecture));
        require(arch_length > 0 && arch_length < static_cast<int>(sizeof(architecture)) && std::string(architecture) == "qwen35", "This native adapter supports verified Qwen3.5 text GGUF models only");
        require(!llama_model_has_encoder(state->model) && llama_model_has_decoder(state->model), "Only decoder-only text models are supported");
        const char * model_template = llama_model_chat_template(state->model, nullptr);
        require(model_template != nullptr && model_template[0] != '\0', "GGUF must contain its official chat template");
        state->templates = common_chat_templates_init(state->model, "");
        require(static_cast<bool>(state->templates), "Unable to load GGUF Jinja chat template");
        auto context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = BATCH_TOKENS;
        context_params.n_ubatch = BATCH_TOKENS;
        context_params.n_seq_max = 1;
        context_params.n_threads = CPU_THREADS;
        context_params.n_threads_batch = CPU_THREADS;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        context_params.no_perf = true;
        context_params.abort_callback = abort_compute;
        context_params.abort_callback_data = state.get();
        state->context = llama_init_from_model(state->model, context_params);
        check_cancelled(*state);
        if (!state->context) throw std::runtime_error("Unable to allocate native model context");
        state->context_size = static_cast<int>(llama_n_ctx(state->context));
        require(state->context_size == context_size, "Native context size mismatch");
    } catch (...) { translate_exception(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeResetCancellation(JNIEnv * env, jobject, jlong handle) {
    try { get_state(handle)->cancelled.store(false, std::memory_order_relaxed); }
    catch (...) { translate_exception(env); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(handles_mutex);
    auto found = handles.find(handle);
    if (found != handles.end()) found->second->cancelled.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<State> state;
    {
        std::lock_guard<std::mutex> lock(handles_mutex);
        auto found = handles.find(handle);
        if (found == handles.end()) return;
        state = std::move(found->second);
        handles.erase(found);
    }
    state->cancelled.store(true, std::memory_order_relaxed);
    // Any outstanding JNI call retains its own shared_ptr. Destruction occurs after it exits.
}

extern "C" JNIEXPORT jint JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeCountTokens(JNIEnv * env, jobject, jlong handle, jstring system, jobjectArray roles, jobjectArray texts, jstring user, jboolean thinking) {
    try {
        auto state = get_state(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        require(state->model && state->context, "Native model is not initialized");
        auto prepared = prepare_chat(*state, messages_from_java(env, system, roles, texts, user), thinking == JNI_TRUE, 0, false);
        return static_cast<jint>(prepared.tokens.size());
    } catch (...) { translate_exception(env); return 0; }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_user_localagent_inference_LlamaNative_nativeGenerate(JNIEnv * env, jobject, jlong handle, jstring system, jobjectArray roles, jobjectArray texts, jstring user, jboolean thinking, jint max_output, jobject sink) {
    try {
        require(max_output >= 1 && max_output <= 2048, "Output cap must be 1-2048 tokens");
        auto state = get_state(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        require(state->model && state->context, "Native model is not initialized");
        check_cancelled(*state);
        auto prepared = prepare_chat(*state, messages_from_java(env, system, roles, texts, user), thinking == JNI_TRUE, max_output, true);
        llama_memory_clear(llama_get_memory(state->context), true);
        struct ClearMemory {
            llama_context * context;
            ~ClearMemory() { llama_memory_clear(llama_get_memory(context), true); }
        } clear_memory{state->context};
        Batch batch(llama_batch_ext_init(state->context), llama_batch_ext_free);
        if (!batch) throw std::bad_alloc();
        auto sampler_params = llama_sampler_chain_default_params();
        sampler_params.no_perf = true;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sampler_params), llama_sampler_free);
        if (!sampler) throw std::bad_alloc();
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(thinking == JNI_TRUE ? 0.95f : 0.8f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(thinking == JNI_TRUE ? 1.0f : 0.7f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        const auto started = Clock::now();
        const int prompt_count = static_cast<int>(prepared.tokens.size());
        for (int offset = 0; offset < prompt_count; offset += BATCH_TOKENS) {
            evaluate(*state, batch.get(), prepared.tokens.data() + offset, std::min(BATCH_TOKENS, prompt_count - offset), offset);
        }
        const auto prefill_end = Clock::now();
        Utf16Emitter emitter(env, sink, *state);
        const auto * vocab = llama_model_get_vocab(state->model);
        std::string published;
        size_t generated_bytes = 0;
        int generated_tokens = 0;
        auto publish = [&](const common_chat_msg & message) {
            require(message.content.size() <= MAX_OUTPUT_BYTES, "Generated content exceeds safety limit");
            require(message.content.compare(0, published.size(), published) == 0, "Native streaming parser revised already published text");
            const std::string delta = message.content.substr(published.size());
            published = message.content;
            emitter.append(delta);
            // reasoning_content and tool_calls are deliberately never executed or emitted.
        };
        for (int i = 0; i < max_output; ++i) {
            check_cancelled(*state);
            const llama_token token = llama_sampler_sample(sampler.get(), state->context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            const auto piece = token_piece(vocab, token);
            generated_bytes += piece.size();
            require(generated_bytes <= MAX_OUTPUT_BYTES, "Generated output exceeds safety limit");
            ++generated_tokens;
            common_chat_input chunk;
            chunk.append(piece, token);
            publish(prepared.session->feed(chunk));
            if (i + 1 < max_output) evaluate(*state, batch.get(), &token, 1, prompt_count + i);
        }
        check_cancelled(*state);
        publish(prepared.session->finish());
        emitter.append("", true);
        const auto finished = Clock::now();
        const double prefill_seconds = seconds(started, prefill_end);
        const double generation_seconds = seconds(prefill_end, finished);
        // Exact tokenizer/sample counts and wall-clock measurements, never character estimates.
        // Decode count includes hidden reasoning tokens if thinking is enabled.
        const jdouble values[5] = {
            emitter.has_visible() ? seconds(started, emitter.first_visible()) : -1.0,
            static_cast<double>(prompt_count), static_cast<double>(generated_tokens),
            prefill_seconds > 0 ? prompt_count / prefill_seconds : 0.0,
            generation_seconds > 0 ? generated_tokens / generation_seconds : 0.0,
        };
        jdoubleArray result = env->NewDoubleArray(5);
        if (!result) throw PendingJavaException{};
        env->SetDoubleArrayRegion(result, 0, 5, values);
        return result;
    } catch (...) { translate_exception(env); return nullptr; }
}


