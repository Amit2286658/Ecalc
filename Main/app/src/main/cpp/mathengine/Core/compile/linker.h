/*
 * linker.h — Topological `<[dep]` Dependency Linker for Compiled `.math` Units
 *
 * Purpose:
 *   Handles Phase 2 (Linking) of extension building in `Core/compile/`:
 *     1. `ext_link_units`: Constructs the `<[dep]` dependency DAG over all
 *        staged compilation units in `ExtBuildCtx` (and modules already loaded
 *        in `SymTable`), detects missing or circular dependencies via Kahn's
 *        topological sort algorithm, and compiles module bodies in resolved
 *        topological order with full transactional rollback on error.
 *     2. `ext_compile_and_link_ctx` / `ext_compile_and_link`: Batch compiles an
 *        array of unordered `.math` source strings and links them into
 *        `MemArena` + `SymTable` with zero global/static state.
 *     3. `ext_load_str`: Single-string compile & link convenience wrapper.
 */

#ifndef LINKER_H
#define LINKER_H

#include "compiler.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Resolve `<[dep]` dependencies across all staged units in `ctx` (plus modules
 * already loaded in `tab`), detect missing or circular dependencies, order
 * modules topologically, and compile their bodies into `mem` + `tab` with
 * transactional rollback on error.
 */
bool ext_link_units(MemArena *mem, SymTable *tab, const ExtBuildCtx *ctx);

/*
 * Compile an array of unordered `.math` source texts using caller-supplied `ctx`
 * and link them topologically into `mem` + `tab` (zero heap allocation, zero globals).
 */
bool ext_compile_and_link_ctx(MemArena *mem, SymTable *tab, ExtBuildCtx *ctx,
                              const char *const *raw_texts, size_t unit_count);

/* Convenience: compile an array of unordered `.math` source texts and link them */
bool ext_compile_and_link(MemArena *mem, SymTable *tab,
                          const char *const *raw_texts, size_t unit_count);

/* Compile & link a single `.math` source text string into permanent state */
bool ext_load_str(MemArena *mem, SymTable *tab, const char *ext_src);

#ifdef __cplusplus
}
#endif

#endif /* LINKER_H */
