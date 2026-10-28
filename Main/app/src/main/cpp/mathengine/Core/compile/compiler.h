/*
 * compiler.h — In-Memory `.math` Compilation Unit Stager & Declaration Compiler
 *
 * Purpose:
 *   Handles Phase 1 (Compilation) of extension building in `Core/compile/`:
 *     1. `ext_build_init`: Initializes a multi-unit `ExtBuildCtx` build context
 *        (with unbounded dynamic unit & dependency capacity).
 *     2. `ext_build_create` / `ext_build_destroy`: Heap-allocates/frees an
 *        `ExtBuildCtx` (~296 KB) so it never has to live on a thread stack.
 *     3. `ext_compile_unit`: Scans raw `.math` source text in any order, splits
 *        it into `[module]` blocks, extracts `<[dep]` dependency headers into
 *        `ExtBuildCtx`, and rejects duplicate named modules (already loaded in
 *        `SymTable` or duplicated across staged compilation units).
 *     4. `ext_compile_slice`: Compiles the declarations (`$type`, syntax
 *        delimiters, constants, operators, and functions) of a single module
 *        slice into `MemArena` + `SymTable`.
 */

#ifndef COMPILER_H
#define COMPILER_H

#include "mem_arena.h"
#include "sym_table.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ============================================================================
 * 1. COMPILATION UNIT & STAGING CONTEXT (`ExtCompiledUnit`, `ExtBuildCtx`)
 * ============================================================================ */

#define EXT_SBO_BUILD_UNITS    64u
#define EXT_SBO_UNIT_DEPS      16u
#define EXT_STAGE_POOL_CAP 262144u          /* 256 KB self-contained staging  */
#define EXT_BUILD_HEAP_MAGIC 0x45424348u    /* "EBCH"                         */

typedef struct {
    char     mod_name[32];                  /* Module name (or "default")     */
    bool     is_default_mod;                /* True if headerless [default]   */
    char     sbo_deps[EXT_SBO_UNIT_DEPS][32]; /* Inline `<[dep_name]` SBO     */
    char   (*heap_deps)[32];                /* Heap-spilled dependencies      */
    uint16_t dep_cap;                       /* Allocated dependency capacity  */
    uint16_t dep_count;                     /* Number of dependencies         */
    uint32_t src_off;                       /* Offset in ExtBuildCtx.src_pool */
    uint16_t src_len;                       /* Length in ExtBuildCtx.src_pool */
} ExtCompiledUnit;

typedef struct {
    ExtCompiledUnit  sbo_units[EXT_SBO_BUILD_UNITS];
    ExtCompiledUnit *heap_units;
    uint32_t         heap_magic;
    uint16_t         unit_cap;
    uint16_t         unit_count;
    uint32_t         pool_used;
    bool             has_error;
    char             err_msg[128];
    char             src_pool[EXT_STAGE_POOL_CAP];
} ExtBuildCtx;

static inline const ExtCompiledUnit *ext_ctx_units(const ExtBuildCtx *ctx) {
    return (ctx->heap_magic == EXT_BUILD_HEAP_MAGIC && ctx->heap_units != NULL)
           ? ctx->heap_units
           : ctx->sbo_units;
}

static inline ExtCompiledUnit *ext_ctx_units_mut(ExtBuildCtx *ctx) {
    return (ctx->heap_magic == EXT_BUILD_HEAP_MAGIC && ctx->heap_units != NULL)
           ? ctx->heap_units
           : ctx->sbo_units;
}

static inline const char (*ext_unit_deps(const ExtCompiledUnit *u))[32] {
    return u->heap_deps ? (const char (*)[32])u->heap_deps : u->sbo_deps;
}

static inline char (*ext_unit_deps_mut(ExtCompiledUnit *u))[32] {
    return u->heap_deps ? u->heap_deps : u->sbo_deps;
}

/* ============================================================================
 * 2. COMPILATION API
 * ============================================================================ */

/* Initialize or reset a multi-module compilation & linking context */
void         ext_build_init(ExtBuildCtx *ctx);
void         ext_build_free_heap(ExtBuildCtx *ctx);

/* Heap-allocate and initialize an `ExtBuildCtx` (safe for small thread stacks) */
ExtBuildCtx *ext_build_create(void);
void         ext_build_destroy(ExtBuildCtx *ctx);

/*
 * Stage `[module]` headers and `<[dep]` dependency declarations from
 * `raw_math_text` (which may contain one or more modules in any order) into `ctx`.
 * Rejects modules that are already loaded in `tab` or duplicated in `ctx`.
 */
bool ext_compile_unit(MemArena *mem, const SymTable *tab,
                      ExtBuildCtx *ctx, const char *raw_math_text);

/*
 * Compile all declarations (`$type`, syntax bindings, constants, operators, and
 * functions) within a single module slice into `mem` + `tab`.
 * Invoked in resolved topological order by the linker (`linker.c`).
 */
bool ext_compile_slice(MemArena *mem, SymTable *tab,
                       const char *slice_src, uint16_t slice_len);

#ifdef __cplusplus
}
#endif

#endif /* COMPILER_H */
