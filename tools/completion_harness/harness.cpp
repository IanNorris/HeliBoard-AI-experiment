// SPDX-License-Identifier: GPL-3.0-only
//
// Desktop completion harness: runs the same GGUF base model and generation parameters as the
// on-device keyboard (see llama/src/main/cpp/llama_jni.cpp) so prompt/context/sampler choices can be
// tuned on a desktop before shipping to the phone. Keep the SYNC: blocks below in step with the JNI.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "llama.h"

namespace {

// ---- Prompt window: mirrors PromptBuilder.kt --------------------------------------------------
// Keep only a bounded, recent window of the left context; avoid a partial leading word; preserve a
// single trailing space so the model starts a new word.
std::string build_prompt(const std::string& leftContext, int maxChars) {
    if (leftContext.empty()) return "";
    std::string window = leftContext;
    if ((int) window.size() > maxChars) {
        size_t start = window.size() - maxChars;
        // prefer the next word boundary inside the window, but keep a final long word whole
        size_t sp = window.find(' ', start);
        if (sp != std::string::npos && sp < window.size() - 1) start = sp + 1;
        window = window.substr(start);
    }
    // collapse trailing whitespace to a single space if there was any
    size_t end = window.find_last_not_of(" \t\n\r");
    bool hadTrailingSpace = (end != window.size() - 1);
    if (end == std::string::npos) return "";
    window = window.substr(0, end + 1);
    if (hadTrailingSpace) window += ' ';
    return window;
}

// ---- Sampler: SYNC: mirror of llama_jni.cpp build_sampler ------------------------------------
llama_sampler* build_sampler(float temp, uint32_t seed) {
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(/*last_n*/ 64, /*repeat*/ 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.10f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    return smpl;
}

float token_logprob(llama_context* ctx, const llama_vocab* vocab, llama_token id) {
    const float* logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return -100.0f;
    const int n_vocab = llama_vocab_n_tokens(vocab);
    if (id < 0 || id >= n_vocab) return -100.0f;
    float maxl = logits[0];
    for (int i = 1; i < n_vocab; ++i) if (logits[i] > maxl) maxl = logits[i];
    double sumexp = 0.0;
    for (int i = 0; i < n_vocab; ++i) sumexp += std::exp((double)(logits[i] - maxl));
    return (float)((logits[id] - maxl) - std::log(sumexp));
}

struct Cand { std::string text; float score; int genTokens; long genMs; };

// SYNC: mirror of decode_generate + generate_multi_shared in llama_jni.cpp
std::vector<Cand> generate(llama_context* ctx, const llama_vocab* vocab,
                           const std::vector<llama_token>& toks, int n, int maxTokens,
                           long budgetMs, long* outPrefillMs) {
    using clock = std::chrono::steady_clock;
    std::vector<Cand> out;
    llama_memory_t mem = llama_get_memory(ctx);
    llama_memory_clear(mem, true);
    const int P = (int) toks.size();
    if (P == 0) { if (outPrefillMs) *outPrefillMs = 0; return out; }

    const auto start = clock::now();
    llama_batch pb = llama_batch_get_one(const_cast<llama_token*>(toks.data()), P);
    if (llama_decode(ctx, pb)) { if (outPrefillMs) *outPrefillMs = 0; return out; }
    if (outPrefillMs) *outPrefillMs = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
        clock::now() - start).count();

    const bool useBudget = budgetMs > 0;
    const auto budgetEnd = start + std::chrono::milliseconds(budgetMs);
    llama_token lastTok = toks[P - 1];
    for (int i = 0; i < n; ++i) {
        if (useBudget && i > 0 && clock::now() >= budgetEnd) break;
        const auto candStart = clock::now();
        if (i > 0) {
            llama_memory_seq_rm(mem, 0, P - 1, -1);
            llama_batch lb = llama_batch_get_one(&lastTok, 1);
            if (llama_decode(ctx, lb)) break;
        }
        llama_sampler* smpl = build_sampler((i == 0) ? 0.3f : 0.6f, (uint32_t)(1234 + i));
        std::string text;
        size_t lastBoundary = 0;
        double wordSum = 0, totalWord = 0;
        int wc = 0, gen = 0; bool inWord = false, hitDeadline = false;
        for (int t = 0; t < maxTokens; ++t) {
            if (useBudget && clock::now() >= budgetEnd) { hitDeadline = true; break; }
            llama_token id = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(vocab, id)) break;
            const float lp = token_logprob(ctx, vocab, id);
            char buf[256];
            int m = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
            if (m < 0) break;
            std::string piece(buf, m);
            if (piece.find('\n') != std::string::npos || piece.find('\r') != std::string::npos) break;
            const bool nw = !inWord || (!piece.empty() && piece[0] == ' ');
            if (nw) { if (inWord) { totalWord += wordSum; wc++; } wordSum = lp; inWord = true; lastBoundary = text.size(); }
            else wordSum += lp;
            text += piece; gen++;
            llama_batch sb = llama_batch_get_one(&id, 1);
            if (llama_decode(ctx, sb)) break;
        }
        if (hitDeadline && inWord) text.resize(lastBoundary);
        if (inWord && !hitDeadline) { totalWord += wordSum; wc++; }
        llama_sampler_free(smpl);
        const long candMs = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
            clock::now() - candStart).count();
        out.push_back(Cand{text, wc > 0 ? (float)(totalWord / wc) : -100.0f, gen, candMs});
    }
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& prompt) {
    int n_max = (int) prompt.size() + 8;
    std::vector<llama_token> toks(n_max);
    int n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n_max, true, false);
    if (n < 0) { toks.resize(-n); n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, false); }
    if (n <= 0) return {};
    toks.resize(n);
    return toks;
}

const char* arg(int argc, char** argv, const char* key, const char* def) {
    for (int i = 1; i < argc - 1; ++i) if (std::strcmp(argv[i], key) == 0) return argv[i + 1];
    return def;
}

} // namespace

int main(int argc, char** argv) {
    const char* modelPath = arg(argc, argv, "--model", nullptr);
    if (!modelPath) { std::fprintf(stderr, "usage: --model <gguf> [--context <text> | --contexts-file <file>] [--candidates N] [--max-tokens N] [--budget-ms N] [--context-chars N]\n"); return 2; }
    const int candidates = std::atoi(arg(argc, argv, "--candidates", "3"));
    const int maxTokens = std::atoi(arg(argc, argv, "--max-tokens", "14"));
    const long budgetMs = std::atol(arg(argc, argv, "--budget-ms", "2500"));
    const int contextChars = std::atoi(arg(argc, argv, "--context-chars", "256"));
    const char* single = arg(argc, argv, "--context", nullptr);
    const char* file = arg(argc, argv, "--contexts-file", nullptr);

    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(modelPath, mp);
    if (!model) { std::fprintf(stderr, "failed to load model: %s\n", modelPath); return 1; }
    const llama_vocab* vocab = llama_model_get_vocab(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 512; cp.n_batch = 512;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { std::fprintf(stderr, "failed to create context\n"); return 1; }

    std::vector<std::string> contexts;
    if (file) { std::ifstream in(file); std::string line; while (std::getline(in, line)) if (!line.empty()) contexts.push_back(line); }
    else if (single) contexts.push_back(single);
    else { std::fprintf(stderr, "provide --context or --contexts-file\n"); return 2; }

    const bool batch = file != nullptr;
    if (batch) std::printf("context\tbest_score\ttotal_ms\ttok_per_s\n");

    for (const auto& c : contexts) {
        std::string prompt = build_prompt(c, contextChars);
        std::vector<llama_token> toks = tokenize(vocab, prompt);
        long prefillMs = 0;
        const auto t0 = std::chrono::steady_clock::now();
        std::vector<Cand> cands = generate(ctx, vocab, toks, candidates, maxTokens, budgetMs, &prefillMs);
        const long totalMs = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        int totalGen = 0; float best = -1e9f;
        for (auto& x : cands) { totalGen += x.genTokens; best = std::max(best, x.score); }
        const double tps = totalMs > 0 ? totalGen * 1000.0 / totalMs : 0.0;

        if (batch) {
            std::printf("%s\t%.3f\t%ld\t%.1f\n", c.c_str(), best, totalMs, tps);
        } else {
            std::printf("model: %s\n", modelPath);
            std::printf("params: candidates=%d max_tokens=%d budget_ms=%ld context_chars=%d\n",
                        candidates, maxTokens, budgetMs, contextChars);
            std::printf("sampler: penalties(64,1.1) min_p=0.10 top_k=40 temp=0.3/0.6 seed=1234+i\n");
            std::printf("prompt (%zu tokens): |%s|\n", toks.size(), prompt.c_str());
            std::printf("prefill=%ldms total=%ldms %.1f tok/s\n", prefillMs, totalMs, tps);
            for (size_t i = 0; i < cands.size(); ++i)
                std::printf("  %zu [score=%.2f %dt %ldms] %s\n", i + 1,
                            cands[i].score, cands[i].genTokens, cands[i].genMs, cands[i].text.c_str());
        }
    }

    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
