/*
 * user_loader.h — Platform-Agnostic User Extension Compiler, Linker & Loader
 *
 * Purpose:
 *   Provides a strictly in-memory interface for user-defined `.math` extensions
 *   inside `Core/compile/`. All file paths, folders, and platform storage are
 *   handled by the frontend; the math engine does not touch files or directories.
 *
 *   Separates compilation and linking so user-defined modules do not need
 *   numerical filename prefixes and can depend on one another in any order:
 *     1. `user_build_init` / `user_build_create` / `user_build_destroy`:
 *        Initializes or heap-allocates a `UserBuildCtx` staging context.
 *     2. `user_compile_unit`: Compiles module headers & `<[dep]` metadata from
 *        each raw `.math` source text into `UserBuildCtx` (order-independent;
 *        rejects duplicate modules already loaded in `Engine` or staged twice).
 *     3. `user_link_ext` / `user_link_ext_buf`: Topologically resolves `<[dep]`
 *        dependencies across all compiled units, links them into `Engine`, and
 *        produces a single unified `.mathc` binary image guaranteed to work
 *        (either in the engine's isolated per-engine buffer or a caller-provided
 *        buffer — zero global/static buffers shared across engines!).
 *     4. `user_compile_and_link`: One-call batch helper for compiling & linking
 *        an array of unordered `.math` source texts into `Engine` + `.mathc`.
 *     5. `user_compile_ext`: Single-string convenience wrapper around compile+link.
 *     6. `user_load_mathc`: Loads a precompiled `.mathc` binary image in O(1) time.
 */

#ifndef USER_LOADER_H
#define USER_LOADER_H

#include "math_engine.h"
#include "compiler.h"
#include "linker.h"
#include "mathc_bin.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef ExtBuildCtx UserBuildCtx;

/* Initialize a multi-unit compilation & linking context */
MATH_ENGINE_API void          user_build_init(UserBuildCtx *ctx);

/* Heap-allocate and free a `UserBuildCtx` (~149 KB) for small-stack threads */
MATH_ENGINE_API UserBuildCtx *user_build_create(void);
MATH_ENGINE_API void          user_build_destroy(UserBuildCtx *ctx);

/*
 * Compilation Step:
 *   Stage a raw `.math` extension source text (`raw_math_text`) into `ctx`.
 *   Modules may be supplied in any order, regardless of `<[dep]` dependencies.
 *   Fails if a named `[module]` is already loaded in `eng` or duplicated in `ctx`.
 */
MATH_ENGINE_API bool user_compile_unit(Engine *eng, UserBuildCtx *ctx,
                                       const char *raw_math_text);

/*
 * Linking Step (Caller-Supplied Output Buffer):
 *   Topologically sort all staged modules in `ctx` by their `<[dep]` dependencies,
 *   link and compile them into `eng` (with full transactional rollback on error),
 *   and serialize the linked `.mathc` binary image into `out_buf` (`buf_cap`).
 */
MATH_ENGINE_API bool user_link_ext_buf(Engine *eng, const UserBuildCtx *ctx,
                                       uint8_t *out_buf, size_t buf_cap,
                                       size_t *out_mathc_len);

/*
 * Linking Step (Per-Engine Buffer):
 *   Topologically sort all staged modules in `ctx` by their `<[dep]` dependencies,
 *   link and compile them into `eng` (with full transactional rollback on error),
 *   and return the single linked `.mathc` binary image via `*out_mathc_data`
 *   (pointing to `eng->mathc_buf`, isolated per `Engine` instance) and
 *   `*out_mathc_len`.
 */
MATH_ENGINE_API bool user_link_ext(Engine *eng, const UserBuildCtx *ctx,
                                   const uint8_t **out_mathc_data,
                                   size_t *out_mathc_len);

/*
 * Batch Compile & Link:
 *   Compile an array of `unit_count` unordered `.math` source texts using
 *   `eng->build_ctx`, link them in dependency order into `eng`, and return the
 *   single linked `.mathc` binary via `eng->mathc_buf`.
 */
MATH_ENGINE_API bool user_compile_and_link(Engine *eng,
                                           const char *const *raw_math_texts,
                                           size_t unit_count,
                                           const uint8_t **out_mathc_data,
                                           size_t *out_mathc_len);

/*
 * Single-String Compile & Link Convenience Wrapper:
 *   Take raw `.math` source text (`raw_math_text`), compile & link its module(s)
 *   into `eng`, and hand back the compiled `.mathc` binary image via
 *   `*out_mathc_data` (`eng->mathc_buf`) and `*out_mathc_len`.
 */
MATH_ENGINE_API bool user_compile_ext(Engine *eng, const char *raw_math_text,
                                      const uint8_t **out_mathc_data,
                                      size_t *out_mathc_len);

/*
 * Take in a compiled `.mathc` binary image (`mathc_data`, `mathc_len`) from the
 * frontend and load it directly into `eng` in O(1) `memcpy` time.
 */
MATH_ENGINE_API bool user_load_mathc(Engine *eng, const uint8_t *mathc_data,
                                     size_t mathc_len);

#ifdef __cplusplus
}
#endif

#endif /* USER_LOADER_H */
