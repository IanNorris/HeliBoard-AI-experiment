// SPDX-License-Identifier: GPL-3.0-only
// Minimal JNI wrapper over llama.cpp for on-device text completion.
//
// Exposes a tiny synchronous surface (load / generate / free) matching the Kotlin InferenceBackend.
// Threading, debouncing and cancellation are owned by the Kotlin side; this file only does one
// blocking completion at a time and must only be called from a single worker thread.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama_jni"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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
    // Repetition penalty on GENERATED tokens only (llama only accept()s sampled tokens), kept mild
    // so it curbs loops without pushing the model into thesaurus/"encyclopedia" phrasing.
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(/*last_n*/ 64, /*repeat*/ 1.1f, 0.0f, 0.0f));
    // min_p is the honest low-probability cap ("cap the log probs"): keep only tokens with
    // P >= 0.10 * P_max (a ~2.3 nat gap). Raised from 0.05 to suppress low-confidence junk at the
    // source. top_p is intentionally dropped (redundant with min_p; small models degrade when
    // top_k/top_p/min_p are stacked). A modest top_k guards the tail.
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.10f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    return smpl;
}

// Raw-model log-probability of [id] given the current logits at position -1 (the ones that were
// just used to sample). Uses the UNMODIFIED model logits (not the sampler-adjusted distribution) so
// the score reflects the model's genuine confidence, independent of temperature/penalties. Cheap:
// one softmax pass over the vocab, only for tokens we actually generate.
static float token_logprob(Session* s, llama_token id) {
    const float* logits = llama_get_logits_ith(s->ctx, -1);
    if (!logits) return -100.0f;
    const int n_vocab = llama_vocab_n_tokens(s->vocab);
    if (id < 0 || id >= n_vocab) return -100.0f;
    float maxl = logits[0];
    for (int i = 1; i < n_vocab; ++i) if (logits[i] > maxl) maxl = logits[i];
    double sumexp = 0.0;
    for (int i = 0; i < n_vocab; ++i) sumexp += std::exp((double)(logits[i] - maxl));
    return (float)((logits[id] - maxl) - std::log(sumexp));
}

// Generate one short continuation of an already-tokenized prompt using the given sampler. If
// [outScore] is non-null it receives the candidate's confidence: the mean over words of the summed
// per-token raw-model logprob (length-invariant, so short and long candidates compare fairly, and
// tokenizer fragmentation within a word doesn't distort it). An empty generation scores very low.
// [outGenTokens] receives the number of generated tokens and [outPrefillMs] the prompt-decode time
// in milliseconds (diagnostics).
static std::string generate_one(Session* s, const std::vector<llama_token>& toks,
                                llama_sampler* smpl, int maxTokens, float* outScore = nullptr,
                                int* outGenTokens = nullptr, long* outPrefillMs = nullptr) {
    using clock = std::chrono::steady_clock;
    llama_memory_clear(llama_get_memory(s->ctx), true);
    const auto prefillStart = clock::now();
    llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(toks.data()), (int32_t) toks.size());
    if (llama_decode(s->ctx, batch)) {
        LOGW("prompt decode failed");
        if (outScore) *outScore = -100.0f;
        if (outGenTokens) *outGenTokens = 0;
        if (outPrefillMs) *outPrefillMs = 0;
        return "";
    }
    if (outPrefillMs) *outPrefillMs = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
        clock::now() - prefillStart).count();

    std::string out;
    double wordSum = 0.0;   // running logprob sum of the current word
    double totalWordLogprob = 0.0;
    int    wordCount = 0;
    int    genTokens = 0;
    bool   inWord = false;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;
        const float lp = token_logprob(s, id);  // read BEFORE the next decode overwrites the logits
        char buf[256];
        int m = llama_token_to_piece(s->vocab, id, buf, sizeof(buf), 0, false);
        if (m < 0) break;
        std::string piece(buf, m);
        if (piece.find('\n') != std::string::npos || piece.find('\r') != std::string::npos) break;
        // a new word starts when the piece begins with a space (or on the very first token)
        const bool newWord = !inWord || (!piece.empty() && piece[0] == ' ');
        if (newWord) {
            if (inWord) { totalWordLogprob += wordSum; wordCount++; }
            wordSum = lp;
            inWord = true;
        } else {
            wordSum += lp;
        }
        out += piece;
        genTokens++;
        llama_batch step = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, step)) break;
    }
    if (inWord) { totalWordLogprob += wordSum; wordCount++; }
    if (outScore) *outScore = wordCount > 0 ? (float)(totalWordLogprob / wordCount) : -100.0f;
    if (outGenTokens) *outGenTokens = genTokens;
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

// Generate [count] diverse short continuations. Output format (one entry per line):
//   line 0: "#STATS\t<promptTokens>\t<prefillMs>\t<totalMs>"
//   lines 1..N: "<score>\t<genTokens>\t<genMs>\t<text>"
// score is the mean-per-word logprob confidence. Candidate 0 is a low-temperature "safe" pick; the
// rest use a moderate temperature with distinct seeds for variety. The extra fields feed the debug
// panel / stats; the Kotlin parser tolerates the header and the per-candidate timing fields.
jstring nativeGenerateMulti(JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens, jint count) {
    using clock = std::chrono::steady_clock;
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

    const auto totalStart = clock::now();
    long sumPrefillMs = 0;
    std::string body;
    for (int i = 0; i < n; ++i) {
        float temp = (i == 0) ? 0.3f : 0.6f;
        llama_sampler* smpl = build_sampler(temp, (uint32_t) (1234 + i));
        float score = -100.0f;
        int genTokens = 0;
        long prefillMs = 0;
        const auto candStart = clock::now();
        std::string cand = generate_one(s, toks, smpl, limit, &score, &genTokens, &prefillMs);
        const long candMs = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
            clock::now() - candStart).count();
        llama_sampler_free(smpl);
        sumPrefillMs += prefillMs;
        if (i > 0) body += "\n";
        char lineBuf[64];
        std::snprintf(lineBuf, sizeof(lineBuf), "%.4f\t%d\t%ld\t", score, genTokens, candMs);
        body += lineBuf;
        body += cand;
    }
    const long totalMs = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
        clock::now() - totalStart).count();

    char header[96];
    std::snprintf(header, sizeof(header), "#STATS\t%d\t%ld\t%ld\n",
                  (int) toks.size(), sumPrefillMs, totalMs);
    std::string result = header;
    result += body;
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
