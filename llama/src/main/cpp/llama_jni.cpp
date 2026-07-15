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

    Session* s = new Session();
    s->model = model; s->ctx = ctx; s->sampler = nullptr;  // samplers are built per generation call
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) cp.n_ctx;

    int64_t handle = g_next_handle.fetch_add(1);
    { std::lock_guard<std::mutex> lock(g_mutex); g_sessions[handle] = s; }
    return (jlong) handle;
}

// Build a per-call sampler. Low repetition penalty (1.1) - a high one (1.3) is essay-anti-loop
// tuning that distorts short natural phrasing into "encyclopedia" text. Temperature/seed vary per
// candidate: a low temp gives a "safe" pick, higher temps give diverse alternatives.
static llama_sampler* build_sampler(float temp, uint32_t seed) {
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(/*last_n*/ 64, /*repeat*/ 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    return smpl;
}

// Generate one short continuation of an already-tokenized prompt using the given sampler.
static std::string generate_one(Session* s, const std::vector<llama_token>& toks,
                                llama_sampler* smpl, int maxTokens) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(toks.data()), (int32_t) toks.size());
    if (llama_decode(s->ctx, batch)) { LOGW("prompt decode failed"); return ""; }

    std::string out;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;
        char buf[256];
        int m = llama_token_to_piece(s->vocab, id, buf, sizeof(buf), 0, false);
        if (m < 0) break;
        std::string piece(buf, m);
        if (piece.find('\n') != std::string::npos || piece.find('\r') != std::string::npos) break;
        out += piece;
        llama_batch step = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, step)) break;
    }
    return out;
}

// Tokenize a prompt as raw continuation (no chat template) and left-truncate to fit n_ctx.
static std::vector<llama_token> tokenize_prompt(Session* s, const std::string& prompt, int maxGen) {
    int n_max = (int) prompt.size() + 8;
    std::vector<llama_token> toks(n_max);
    int n = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                           toks.data(), n_max, /*add_special*/ true, /*parse_special*/ false);
    if (n < 0) { toks.resize(-n); n = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                           toks.data(), (int32_t) toks.size(), true, false); }
    if (n <= 0) return {};
    toks.resize(n);
    int budget = s->n_ctx - maxGen - 1;
    if (budget > 0 && (int) toks.size() > budget)
        toks.erase(toks.begin(), toks.begin() + (toks.size() - budget));
    return toks;
}

jstring nativeGenerate(JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens) {
    Session* s = lookup((int64_t) handle);
    if (!s) return env->NewStringUTF("");
    const char* promptC = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = promptC ? promptC : "";
    env->ReleaseStringUTFChars(jprompt, promptC);
    if (prompt.empty()) return env->NewStringUTF("");

    const int limit = maxTokens > 0 ? maxTokens : 12;
    std::vector<llama_token> toks = tokenize_prompt(s, prompt, limit);
    if (toks.empty()) return env->NewStringUTF("");
    llama_sampler* smpl = build_sampler(0.2f, LLAMA_DEFAULT_SEED);
    std::string out = generate_one(s, toks, smpl, limit);
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

// Generate [count] diverse short continuations, newline-separated. Candidate 0 uses a low
// temperature (safe pick); the rest use a higher temperature with distinct seeds for variety.
jstring nativeGenerateMulti(JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens, jint count) {
    Session* s = lookup((int64_t) handle);
    if (!s) return env->NewStringUTF("");
    const char* promptC = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = promptC ? promptC : "";
    env->ReleaseStringUTFChars(jprompt, promptC);
    if (prompt.empty()) return env->NewStringUTF("");

    const int limit = maxTokens > 0 ? maxTokens : 8;
    const int n = count > 0 ? count : 3;
    std::vector<llama_token> toks = tokenize_prompt(s, prompt, limit);
    if (toks.empty()) return env->NewStringUTF("");

    std::string result;
    for (int i = 0; i < n; ++i) {
        float temp = (i == 0) ? 0.3f : 0.7f;
        llama_sampler* smpl = build_sampler(temp, (uint32_t) (1234 + i));
        std::string cand = generate_one(s, toks, smpl, limit);
        llama_sampler_free(smpl);
        if (i > 0) result += "\n";
        result += cand;
    }
    return env->NewStringUTF(result.c_str());
}

void nativeFree(JNIEnv*, jobject, jlong handle) {
    Session* s = nullptr;
    { std::lock_guard<std::mutex> lock(g_mutex);
      auto it = g_sessions.find((int64_t) handle);
      if (it != g_sessions.end()) { s = it->second; g_sessions.erase(it); } }
    destroy(s);
}

const JNINativeMethod kMethods[] = {
    {"load",          "(Ljava/lang/String;II)J",                    (void*) nativeLoad},
    {"generate",      "(JLjava/lang/String;I)Ljava/lang/String;",   (void*) nativeGenerate},
    {"generateMulti", "(JLjava/lang/String;II)Ljava/lang/String;",  (void*) nativeGenerateMulti},
    {"free",          "(J)V",                                        (void*) nativeFree},
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
