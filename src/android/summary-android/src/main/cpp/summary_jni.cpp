#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Cancelled : std::runtime_error { using std::runtime_error::runtime_error; };
struct Model {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
    std::atomic<bool> closed{false};
    std::mutex operation;
    ~Model() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::once_flag backend;
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Model>> registry;
std::atomic<jlong> next_id{1};

std::shared_ptr<Model> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it = registry.find(id);
    if (it == registry.end()) throw std::runtime_error("LLM handle is closed");
    return it->second;
}
void check_cancelled(const Model & state) {
    if (state.cancelled.load() || state.closed.load()) throw Cancelled("Summary cancelled");
}
void error(JNIEnv * env, const char * kind, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(kind), message);
}
std::string bytes(JNIEnv * env, jbyteArray array) {
    if (!array) throw std::runtime_error("Missing UTF-8 byte array");
    const jsize size = env->GetArrayLength(array);
    std::string result(size, '\0');
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read UTF-8 array");
    return result;
}
std::string chat(const Model & state, const std::string & system, const std::string & user) {
    const char * format = llama_model_chat_template(state.model, nullptr);
    if (!format) throw std::runtime_error("GGUF is missing an instruct chat template");
    const llama_chat_message messages[] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> output(2 * (system.size() + user.size()) + 256);
    int length = llama_chat_apply_template(format, messages, 2, true, output.data(), static_cast<int32_t>(output.size()));
    if (length < 0) throw std::runtime_error("Unsupported model chat template");
    if (static_cast<size_t>(length) > output.size()) {
        output.resize(length);
        length = llama_chat_apply_template(format, messages, 2, true, output.data(), static_cast<int32_t>(output.size()));
    }
    if (length < 0 || static_cast<size_t>(length) > output.size()) throw std::runtime_error("Cannot format model prompt");
    return std::string(output.data(), length);
}
std::vector<llama_token> tokenize(const Model & state, const std::string & prompt) {
    if (prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) throw std::runtime_error("Prompt is too large");
    const auto * vocab = llama_model_get_vocab(state.model);
    int length = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (length == std::numeric_limits<int32_t>::min()) throw std::runtime_error("Token count overflow");
    if (length >= 0) throw std::runtime_error("Empty tokenized prompt");
    std::vector<llama_token> result(-length);
    length = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), result.data(), static_cast<int32_t>(result.size()), true, true);
    if (length <= 0) throw std::runtime_error("Cannot tokenize prompt");
    result.resize(length);
    return result;
}
std::string generate(Model & state, const std::vector<llama_token> & tokens, const std::string & grammar, int limit) {
    if (limit <= 0 || tokens.size() + static_cast<size_t>(limit) > llama_n_ctx(state.context)) throw std::runtime_error("Generation exceeds model context");
    std::lock_guard<std::mutex> lock(state.operation);
    check_cancelled(state);
    llama_memory_clear(llama_get_memory(state.context), true);
    llama_set_abort_callback(state.context, [](void * data) { return static_cast<Model *>(data)->cancelled.load(); }, &state);
    const auto * vocab = llama_model_get_vocab(state.model);
    using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
    Sampler chain(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    Sampler constrained(llama_sampler_init_grammar(vocab, grammar.c_str(), "root"), llama_sampler_free);
    if (!chain || !constrained) throw std::runtime_error("Cannot initialize JSON grammar sampler");
    llama_sampler_chain_add(chain.get(), constrained.release());
    llama_sampler_chain_add(chain.get(), llama_sampler_init_greedy());
    for (size_t offset = 0; offset < tokens.size();) {
        check_cancelled(state);
        const int count = static_cast<int>(std::min<size_t>(llama_n_batch(state.context), tokens.size() - offset));
        auto batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data()) + offset, count);
        const int status = llama_decode(state.context, batch);
        check_cancelled(state);
        if (status != 0) throw std::runtime_error("Prompt inference failed");
        offset += count;
    }
    std::string result;
    for (int i = 0; i < limit; ++i) {
        check_cancelled(state);
        // llama_sampler_sample already accepts the token; do not accept it a second time.
        llama_token token = llama_sampler_sample(chain.get(), state.context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(128);
        int count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (count < 0) {
            piece.resize(-count);
            count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (count < 0) throw std::runtime_error("Cannot decode generated token");
        result.append(piece.data(), count);
        if (i + 1 < limit) {
            const int status = llama_decode(state.context, llama_batch_get_one(&token, 1));
            check_cancelled(state);
            if (status != 0) throw std::runtime_error("Token inference failed");
        }
    }
    return result; // Kotlin rejects truncated/invalid JSON; no partial result is shown.
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_ru_stenographer_summary_android_NativeLlama_open(JNIEnv * env, jobject, jbyteArray path, jint context, jint threads) {
    try {
        if (context < 1024 || context > 32768 || threads < 1 || threads > 16) throw std::runtime_error("Invalid LLM configuration");
        std::call_once(backend, [] { llama_backend_init(); });
        auto state = std::make_shared<Model>();
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        state->model = llama_model_load_from_file(bytes(env, path).c_str(), model_params);
        if (!state->model) throw std::runtime_error("Cannot load GGUF model");
        auto params = llama_context_default_params();
        params.n_ctx = context;
        params.n_batch = 512;
        params.n_ubatch = 128;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        state->context = llama_init_from_model(state->model, params);
        if (!state->context) throw std::runtime_error("Cannot allocate LLM context");
        const jlong id = next_id.fetch_add(1);
        std::lock_guard<std::mutex> lock(registry_mutex);
        registry[id] = state;
        return id;
    } catch (const std::exception & e) { error(env, "java/lang/IllegalStateException", e.what()); return 0; }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_stenographer_summary_android_NativeLlama_contextSize(JNIEnv * env, jobject, jlong id) {
    try { return static_cast<jint>(llama_n_ctx(lookup(id)->context)); }
    catch (const std::exception & e) { error(env, "java/lang/IllegalStateException", e.what()); return 0; }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_stenographer_summary_android_NativeLlama_countTokens(JNIEnv * env, jobject, jlong id, jbyteArray system, jbyteArray user) {
    try {
        auto state = lookup(id);
        return static_cast<jint>(tokenize(*state, chat(*state, bytes(env, system), bytes(env, user))).size());
    } catch (const std::exception & e) { error(env, "java/lang/IllegalStateException", e.what()); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_ru_stenographer_summary_android_NativeLlama_generate(JNIEnv * env, jobject, jlong id, jbyteArray system, jbyteArray user, jbyteArray grammar, jint limit) {
    try {
        auto state = lookup(id);
        const auto prompt = chat(*state, bytes(env, system), bytes(env, user));
        const auto result = generate(*state, tokenize(*state, prompt), bytes(env, grammar), limit);
        jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size()));
        if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte *>(result.data()));
        return output;
    } catch (const Cancelled & e) { error(env, "java/util/concurrent/CancellationException", e.what()); return nullptr; }
    catch (const std::exception & e) { error(env, "java/lang/IllegalStateException", e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_ru_stenographer_summary_android_NativeLlama_cancel(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto it = registry.find(id);
    if (it != registry.end()) it->second->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL Java_ru_stenographer_summary_android_NativeLlama_close(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto it = registry.find(id);
    if (it != registry.end()) {
        it->second->closed.store(true);
        it->second->cancelled.store(true);
        registry.erase(it); // An in-flight operation keeps its shared_ptr until cancellation completes.
    }
}
