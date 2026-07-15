// SPDX-License-Identifier: GPL-3.0-only
// Minimal JNI wrapper over llama.cpp for on-device text completion.
//
// Exposes a tiny synchronous surface (load / generate / free) matching the Kotlin InferenceBackend.
// Threading, debouncing and cancellation are owned by the Kotlin side; this file only does one
// blocking completion at a time and must only be called from a single worker thread.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama_jni"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model*       model   = nullptr;
    llama_context*     ctx     = nullptr;
    llama_sampler*     sampler = nullptr;
    const llama_vocab* vocab   = nullptr;
    int                n_ctx   = 0;
};

std::mutex g_mutex;
std::unordered_map<int64_t, Session*> g_sessions;
std::atomic<int64_t> g_next_handle{1};
std::once_flag g_backend_once;

Session* lookup(int64_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

void destroy(Session* s) {
    if (!s) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->ctx)     llama_free(s->ctx);
    if (s->model)   llama_model_free(s->model);
    delete s;
}

jlong nativeLoad(JNIEnv* env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::string modelPath = path ? path : "";
    env->ReleaseStringUTFChars(jpath, path);
    if (modelPath.empty()) return 0;

    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    llama_model* model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!model) { LOGE("model load failed: %s", modelPath.c_str()); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx       = (uint32_t) (n_ctx > 0 ? n_ctx : 256);
    cp.n_batch     = cp.n_ctx;
    cp.n_threads   = n_threads > 0 ? n_threads : 2;
    cp.n_threads_batch = cp.n_threads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    // Light sampling (not greedy): a repetition penalty + top-k/top-p + low temperature. Greedy on a
    // small base model produces repetition loops ("greasy, greasy, greasy") and robotic text; this
    // keeps output coherent but natural. Params are conservative for keyboard-appropriate stability.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(/*last_n*/ 64, /*repeat*/ 1.3f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    Session* s = new Session();
    s->model = model; s->ctx = ctx; s->sampler = smpl;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) cp.n_ctx;

    int64_t handle = g_next_handle.fetch_add(1);
    { std::lock_guard<std::mutex> lock(g_mutex); g_sessions[handle] = s; }
    return (jlong) handle;
}

jstring nativeGenerate(JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens) {
    Session* s = lookup((int64_t) handle);
    if (!s) return env->NewStringUTF("");

    const char* promptC = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = promptC ? promptC : "";
    env->ReleaseStringUTFChars(jprompt, promptC);
    if (prompt.empty()) return env->NewStringUTF("");

    // fresh, independent completion (no leakage between keystrokes)
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_sampler_reset(s->sampler);  // clear penalty history so each completion is independent

    // tokenize as raw continuation: add_special=true lets the model add BOS only if it needs it;
    // parse_special=false so user text is never interpreted as control tokens (no chat template)
    int n_max = (int) prompt.size() + 8;
    std::vector<llama_token> toks(n_max);
    int n = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                           toks.data(), n_max, /*add_special*/ true, /*parse_special*/ false);
    if (n < 0) { toks.resize(-n); n = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                           toks.data(), (int32_t) toks.size(), true, false); }
    if (n <= 0) return env->NewStringUTF("");
    toks.resize(n);

    // keep prompt + generation within n_ctx: left-truncate oldest tokens if needed
    int budget = s->n_ctx - (maxTokens > 0 ? maxTokens : 12) - 1;
    if (budget > 0 && (int) toks.size() > budget) {
        toks.erase(toks.begin(), toks.begin() + (toks.size() - budget));
    }

    // decode the prompt; llama_batch_get_one sets logits on the last token for us
    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    if (llama_decode(s->ctx, batch)) { LOGW("prompt decode failed"); return env->NewStringUTF(""); }

    std::string out;
    const int limit = maxTokens > 0 ? maxTokens : 12;
    for (int i = 0; i < limit; ++i) {
        llama_token id = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;
        char buf[256];
        int m = llama_token_to_piece(s->vocab, id, buf, sizeof(buf), 0, false);
        if (m < 0) break;
        std::string piece(buf, m);
        // stop at a line break; word-count policy is applied on the Kotlin side
        if (piece.find('\n') != std::string::npos || piece.find('\r') != std::string::npos) break;
        out += piece;
        llama_batch step = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, step)) break;
    }
    return env->NewStringUTF(out.c_str());
}

void nativeFree(JNIEnv*, jobject, jlong handle) {
    Session* s = nullptr;
    { std::lock_guard<std::mutex> lock(g_mutex);
      auto it = g_sessions.find((int64_t) handle);
      if (it != g_sessions.end()) { s = it->second; g_sessions.erase(it); } }
    destroy(s);
}

const JNINativeMethod kMethods[] = {
    {"load",     "(Ljava/lang/String;II)J",                    (void*) nativeLoad},
    {"generate", "(JLjava/lang/String;I)Ljava/lang/String;",   (void*) nativeGenerate},
    {"free",     "(J)V",                                        (void*) nativeFree},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("helium314/keyboard/llama/LlamaNative");
    if (!cls) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
