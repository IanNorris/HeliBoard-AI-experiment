# Completion desktop harness

A small standalone CLI that runs the **same GGUF base model** and the **same generation
parameters** as the on-device keyboard, so you can iterate on prompt/context/sampler choices on a
desktop (fast, scriptable) before shipping them to the phone.

It deliberately mirrors the device path:

- **Sampler** — identical chain and values to `llama/src/main/cpp/llama_jni.cpp` `build_sampler`
  (penalties(64, 1.1), min_p 0.10, top_k 40, temp; per-candidate temp 0.3 / 0.6, seeds 1234+i).
- **Prompt window** — the same "last N chars, cut to a word boundary, keep a single trailing space"
  rule as `PromptBuilder.kt` (`--context-chars`, default 256).
- **Shared-prefix KV reuse** and the **total time budget** with stop-at-last-complete-word, matching
  the device `generate_multi_shared`.

> These are kept in sync by hand. `llama_jni.cpp` is the source of truth; if you change sampler
> params there, mirror them in `harness.cpp` (search for `SYNC:`), and vice-versa. The harness prints
> the values it used so a run is self-describing.

## Build

Requires the `llama.cpp` submodule (already vendored at
`llama/src/main/cpp/llama.cpp`) and a host CMake/compiler.

```sh
# from the repo root
cmake -S llama/src/main/cpp/llama.cpp -B llama/src/main/cpp/llama.cpp/build-host \
      -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF \
      -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build llama/src/main/cpp/llama.cpp/build-host --target llama -j

cmake -S tools/completion_harness -B tools/completion_harness/build \
      -DCMAKE_BUILD_TYPE=Release
cmake --build tools/completion_harness/build -j
```

## Run

```sh
./tools/completion_harness/build/completion_harness \
    --model /path/to/Qwen2.5-0.5B.Q4_K_M.gguf \
    --context "what time is it? " \
    --candidates 3 --max-tokens 14 --budget-ms 2500 --context-chars 256
```

Output per run: the exact prompt, the sampler params used, prompt tokens, prefill ms, total ms,
tokens/sec, and each candidate with its confidence score, generated-token count and ms — the same
fields as the on-device debug panel, so numbers are directly comparable.

### Batch mode (for sweeps / replay)

Pass `--contexts-file lines.txt` (one context per line) to generate for many contexts and print a
tab-separated summary (context, best score, total ms, tok/s), suitable for piping into a spreadsheet
to compare prompt/context/sampler settings.
