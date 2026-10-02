// JNI bridge to llama.cpp for on-device call summaries (see engine/Summarizer.kt).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "Longhand"

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancelled{false};
};

std::string to_string(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void on_log(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "llama: %s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "llama: %s", text);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_christiantwu_longhand_engine_Summarizer_nativeLoad(JNIEnv * env, jclass, jstring path, jint n_ctx, jint n_threads) {
    llama_log_set(on_log, nullptr);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(to_string(env, path).c_str(), mp);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto * s = new Session();
    s->model = model;
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

// Runs one prompt (already in the model's chat format) and returns the reply as UTF-8
// bytes, or null if the prompt doesn't fit, decoding fails, or the run was cancelled.
// The grammar constrains the reply, so it is always the JSON shape asked for.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_christiantwu_longhand_engine_Summarizer_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt, jstring jgrammar, jint max_tokens) {
    auto * s = reinterpret_cast<Session *>(handle);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt = to_string(env, jprompt);
    const std::string grammar = to_string(env, jgrammar);

    llama_memory_clear(llama_get_memory(s->ctx), true);

    // parse_special: the chat markers (<|im_start|> ...) must become control tokens.
    const int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0 || n + max_tokens > (int) llama_n_ctx(s->ctx)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "summary prompt too long: %d tokens", n);
        return nullptr;
    }
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, true, true);

    for (int i = 0; i < n; i += 512) {
        llama_batch batch = llama_batch_get_one(tokens.data() + i, std::min(512, n - i));
        if (llama_decode(s->ctx, batch) != 0 || s->cancelled) return nullptr;
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_grammar(vocab, grammar.c_str(), "root"));
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string out;
    bool ok = true;
    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[256];
        const int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0 || s->cancelled) {
            ok = false;
            break;
        }
    }
    llama_sampler_free(sampler);
    if (!ok) return nullptr;

    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

// Cancelling sticks until the next reset, so a cancel that lands between attempts still counts.
extern "C" JNIEXPORT void JNICALL
Java_io_github_christiantwu_longhand_engine_Summarizer_nativeCancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Session *>(handle)->cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_christiantwu_longhand_engine_Summarizer_nativeReset(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Session *>(handle)->cancelled = false;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_christiantwu_longhand_engine_Summarizer_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}
