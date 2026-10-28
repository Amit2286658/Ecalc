/*
 * user_loader.c — Platform-Agnostic User Extension Compiler, Linker & Loader
 *
 * Operates strictly on raw `.math` text strings and in-memory `.mathc` binary
 * buffers without any filesystem, directory, or path logic.
 * Zero global or static mutable state: each `Engine` owns its own isolated
 * `build_ctx` and `mathc_buf` (or callers can supply their own via
 * `user_build_create` and `user_link_ext_buf`).
 */

#include "user_loader.h"

void user_build_init(UserBuildCtx *ctx) {
    ext_build_init(ctx);
}

UserBuildCtx *user_build_create(void) {
    return ext_build_create();
}

void user_build_destroy(UserBuildCtx *ctx) {
    ext_build_destroy(ctx);
}

bool user_compile_unit(Engine *eng, UserBuildCtx *ctx, const char *raw_math_text) {
    if (!eng || !ctx) return false;
    return ext_compile_unit(&eng->mem, &eng->sym, ctx, raw_math_text);
}

bool user_link_ext_buf(Engine *eng, const UserBuildCtx *ctx,
                       uint8_t *out_buf, size_t buf_cap, size_t *out_mathc_len) {
    if (out_mathc_len) *out_mathc_len = 0;
    if (!eng || !ctx) return false;

    if (!ext_link_units(&eng->mem, &eng->sym, ctx)) {
        return false;
    }

    if (out_buf || out_mathc_len) {
        if (!out_buf) {
            if (out_mathc_len) {
                *out_mathc_len = (size_t)ext_calc_mathc_size(&eng->mem, &eng->sym);
            }
            return true;
        }
        size_t written = ext_save_mathc_mem(&eng->mem, &eng->sym, out_buf, buf_cap);
        if (written == 0) return false;
        if (out_mathc_len) *out_mathc_len = written;
    }
    return true;
}

bool user_link_ext(Engine *eng, const UserBuildCtx *ctx,
                   const uint8_t **out_mathc_data, size_t *out_mathc_len) {
    if (out_mathc_data) *out_mathc_data = NULL;
    if (out_mathc_len)  *out_mathc_len  = 0;
    if (!eng || !ctx) return false;

    if (!ext_link_units(&eng->mem, &eng->sym, ctx)) {
        return false;
    }

    if (out_mathc_data || out_mathc_len) {
        size_t written = ext_save_mathc_mem(&eng->mem, &eng->sym,
                                            eng->mathc_buf,
                                            sizeof(eng->mathc_buf));
        if (written == 0) return false;
        if (out_mathc_data) *out_mathc_data = eng->mathc_buf;
        if (out_mathc_len)  *out_mathc_len  = written;
    }
    return true;
}

bool user_compile_and_link(Engine *eng,
                           const char *const *raw_math_texts, size_t unit_count,
                           const uint8_t **out_mathc_data, size_t *out_mathc_len) {
    if (out_mathc_data) *out_mathc_data = NULL;
    if (out_mathc_len)  *out_mathc_len  = 0;
    if (!eng || (!raw_math_texts && unit_count > 0)) return false;

    UserBuildCtx *ctx = user_build_create();
    if (!ctx) {
        mem_err(&eng->mem, "Out of memory allocating UserBuildCtx");
        return false;
    }
    for (size_t i = 0; i < unit_count; ++i) {
        if (!user_compile_unit(eng, ctx, raw_math_texts[i])) {
            user_build_destroy(ctx);
            return false;
        }
    }
    bool ok = user_link_ext(eng, ctx, out_mathc_data, out_mathc_len);
    user_build_destroy(ctx);
    return ok;
}

bool user_compile_ext(Engine *eng, const char *raw_math_text,
                      const uint8_t **out_mathc_data, size_t *out_mathc_len) {
    if (out_mathc_data) *out_mathc_data = NULL;
    if (out_mathc_len)  *out_mathc_len  = 0;
    if (!eng || !raw_math_text) return false;
    return user_compile_and_link(eng, &raw_math_text, 1, out_mathc_data, out_mathc_len);
}

bool user_load_mathc(Engine *eng, const uint8_t *mathc_data, size_t mathc_len) {
    if (!eng || !mathc_data || mathc_len == 0) return false;
    return ext_load_mathc_mem(&eng->mem, &eng->sym, mathc_data, mathc_len);
}
