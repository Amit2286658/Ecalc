/*
 * session.c — Independent Parallel In-Memory Session Lifecycle & Evaluation
 *
 * Implements isolated, unnamed in-memory sessions (`eng_session_open`,
 * `eng_session_close`, `eng_session_reset`, and `eng_session_eval`) and
 * delegates snapshot serialization to `Core/session/snapshot.c`.
 */

#include "session.h"
#include "snapshot.h"
#include "math_engine.h"
#include <stdlib.h>
#include <string.h>

int eng_session_open(Engine *eng) {
    if (!eng) return -1;
    mem_clear_err(&eng->mem);
    for (int i = 0; i < ENG_MAX_SESSIONS; ++i) {
        if (!eng->sessions[i].in_use) {
            memset(&eng->sessions[i], 0, sizeof(EngSession));
            eng->sessions[i].in_use = true;
            return i;
        }
    }
    mem_err(&eng->mem, "Maximum active session limit (%d) reached", ENG_MAX_SESSIONS);
    return -1;
}

bool eng_session_close(Engine *eng, int session_id) {
    if (!eng) return false;
    mem_clear_err(&eng->mem);
    if (session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        mem_err(&eng->mem, "Invalid or inactive session ID (%d)", session_id);
        return false;
    }
    memset(&eng->sessions[session_id], 0, sizeof(EngSession));
    return true;
}

bool eng_session_reset(Engine *eng, int session_id) {
    if (!eng) return false;
    mem_clear_err(&eng->mem);
    if (session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        mem_err(&eng->mem, "Invalid or inactive session ID (%d)", session_id);
        return false;
    }
    memset(&eng->sessions[session_id], 0, sizeof(EngSession));
    eng->sessions[session_id].in_use = true;
    return true;
}

bool eng_session_eval(Engine *eng, int session_id, const char *expr, Value *out_val) {
    if (!eng) return false;
    mem_clear_err(&eng->mem);
    mem_rewind_scratch(&eng->mem);

    if (session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        mem_err(&eng->mem, "Invalid or inactive session ID (%d)", session_id);
        return false;
    }
    EngSession *S = &eng->sessions[session_id];
    if (!expr) {
        mem_err(&eng->mem, "Null expression");
        return false;
    }
    size_t raw_len = strlen(expr);
    if (raw_len > 65534u) {
        mem_err(&eng->mem, "Expression length exceeds maximum (65534 bytes)");
        return false;
    }

    Parser P;
    parser_init(&P, &eng->mem, &eng->sym, expr, (uint16_t)raw_len, 0, false, false);

    /* 1. Pre-seed Parser with persistent session variables so slots 0..var_count-1 match S->vars */
    for (uint8_t i = 0; i < S->var_count; ++i) {
        uint16_t tid = sym_find_type(&eng->sym, &eng->mem,
                                     S->vars[i].type_name,
                                     (uint16_t)strlen(S->vars[i].type_name));
        if (tid == MEM_TYPE_VOID) tid = eng->mem.fast_real_type;
        (void)parser_add_local(&P, S->vars[i].name, (uint16_t)strlen(S->vars[i].name), tid);
        if (eng->mem.has_error) return false;
    }

    /* 2. Parse each existing session variable's canonical value expression into `init_asts[i]` */
    uint16_t init_asts[ENG_SESSION_MAX_VARS];
    for (uint8_t i = 0; i < S->var_count; ++i) {
        Parser VP;
        uint16_t vlen = (uint16_t)strlen(S->vars[i].val_text);
        parser_init(&VP, &eng->mem, &eng->sym, S->vars[i].val_text, vlen, 0, false, false);
        memcpy(VP.locals, P.locals, sizeof(P.locals));
        memcpy(VP.slot_types, P.slot_types, sizeof(P.slot_types));
        VP.vis_count   = P.vis_count;
        VP.local_count = P.local_count;

        lex_next(&VP.lex);
        init_asts[i] = parser_parse_expr(&VP, 0);
        if (eng->mem.has_error || init_asts[i] == MEM_NO_IDX) {
            return false;
        }
        /* Reserve any inner parameter slots allocated by restored lambda expressions */
        P.local_count = VP.local_count;
        memcpy(P.slot_types, VP.slot_types, sizeof(P.slot_types));
    }

    /* 3. Parse the new expression in the session context */
    lex_next(&P.lex);
    if (P.lex.cur.kind == TOK_EOF && !eng->mem.has_error) {
        mem_err(&eng->mem, "Empty expression");
        return false;
    }

    uint16_t root = parser_parse_expr(&P, 0);
    if (!eng->mem.has_error && P.lex.cur.kind != TOK_EOF) {
        mem_err(&eng->mem, "Unexpected trailing token '%.*s'",
                (int)P.lex.cur.len, P.lex.src + P.lex.cur.off);
    }
    if (!parser_check_labels(&P) || eng->mem.has_error || root == MEM_NO_IDX) {
        return false;
    }

    for (uint8_t i = 0; i < P.label_count && i < MEM_MAX_LABELS; ++i) {
        eng->mem.label_ast[i] = P.labels[i].body_ast;
    }

    /* 4. Push call frame and materialize prior session variables + type/unit locks */
    uint16_t frame_base = 0;
    if (P.local_count > 0) {
        frame_base = mem_push_frame(&eng->mem, P.local_count);
        if (eng->mem.has_error) return false;
        for (uint8_t i = 0; i < P.local_count; ++i) {
            mem_set_slot(&eng->mem, frame_base, i, val_make_f64(&eng->mem, eng->mem.fast_real_type, 0.0));
        }
    }

    for (int pass = 0; pass < 2; ++pass) {
        for (uint8_t i = 0; i < S->var_count; ++i) {
            bool is_fn = (strcmp(S->vars[i].type_name, "fn") == 0);
            if ((pass == 0 && is_fn) || (pass == 1 && !is_fn)) continue;

            Value iv = eval_ast(&eng->mem, &eng->sym, init_asts[i], frame_base, 0);
            if (eng->mem.has_error) {
                if (P.local_count > 0) mem_pop_frame(&eng->mem, frame_base);
                return false;
            }

            uint8_t uid = (strcmp(S->vars[i].unit_name, "none") == 0)
                          ? MEM_UNIT_NONE
                          : sym_find_unit(&eng->sym, &eng->mem,
                                          S->vars[i].unit_name,
                                          (uint16_t)strlen(S->vars[i].unit_name));
            if (uid >= SYM_UNIT_LOCK_NONE) uid = MEM_UNIT_NONE;
            if (val_get_type(&eng->mem, iv) == eng->mem.vec_type_id && eng->mem.vec_type_id != 0) {
                if (uid != MEM_UNIT_NONE) {
                    uint16_t hdr_idx  = val_get_pool_idx(iv);
                    uint16_t raw_hdr0 = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(hdr_idx + 0));
                    uint16_t elem_tid = (uint16_t)(raw_hdr0 & 0xFFu);
                    mem_write_pool(&eng->mem, (uint16_t)(hdr_idx + 0),
                                   (double)(elem_tid | ((uint16_t)uid << 8u)));
                }
            } else if (uid != MEM_UNIT_NONE) {
                iv = val_with_unit(&eng->mem, iv, uid);
            }

            uint8_t slot = P.locals[i].slot;
            mem_set_slot(&eng->mem, frame_base, slot, iv);

            if (strcmp(S->vars[i].lock_type_name, "*") != 0 && S->vars[i].lock_type_name[0] != '\0') {
                uint16_t ltid = sym_find_type(&eng->sym, &eng->mem,
                                              S->vars[i].lock_type_name,
                                              (uint16_t)strlen(S->vars[i].lock_type_name));
                if (ltid != MEM_TYPE_VOID) {
                    eng->mem.slot_locked_type[frame_base + slot] = (uint8_t)ltid;
                }
            }
            if (strcmp(S->vars[i].lock_unit_name, "*") != 0 && S->vars[i].lock_unit_name[0] != '\0') {
                if (strcmp(S->vars[i].lock_unit_name, "none") == 0) {
                    eng->mem.slot_locked_unit[frame_base + slot] = SYM_UNIT_LOCK_NONE;
                } else {
                    uint8_t luid = sym_find_unit(&eng->sym, &eng->mem,
                                                 S->vars[i].lock_unit_name,
                                                 (uint16_t)strlen(S->vars[i].lock_unit_name));
                    if (luid != MEM_UNIT_NONE) {
                        eng->mem.slot_locked_unit[frame_base + slot] = luid;
                    }
                }
            }
        }
    }

    /* 5. Evaluate the expression */
    Value res = eval_ast(&eng->mem, &eng->sym, root, frame_base, 0);
    if (eng->mem.jump_active == JUMP_RETURN) {
        res = eng->mem.jump_val;
        eng->mem.jump_active = JUMP_NONE;
    } else if (eng->mem.jump_active && !eng->mem.has_error) {
        eng->mem.jump_active = 0;
        mem_err(&eng->mem, "Unhandled jump, break, or continue outside active loop/scope");
    }
    if (eng->mem.has_error) {
        if (P.local_count > 0) mem_pop_frame(&eng->mem, frame_base);
        return false;
    }

    /* 6. Capture all active top-level variables into a transactional backup first so
     *    that if any variable value exceeds `ENG_SESSION_VAL_MAX` or the snapshot
     *    overflows `ENG_SNAPSHOT_MAX_LEN`, the session rolls back cleanly without wedging. */
    uint8_t saved_var_cnt = S->var_count;
    EngSessionVar *saved_vars = (EngSessionVar *)malloc(sizeof(S->vars));
    if (!saved_vars) {
        if (P.local_count > 0) mem_pop_frame(&eng->mem, frame_base);
        mem_err(&eng->mem, "Out of memory saving session state");
        return false;
    }
    memcpy(saved_vars, S->vars, sizeof(S->vars));

    uint8_t new_var_cnt = 0;
    for (uint8_t v = 0; v < P.vis_count; ++v) {
        if (P.locals[v].scope_depth != 0 || P.locals[v].is_loop_iter != 0) continue;
        uint8_t slot = P.locals[v].slot;
        Value   sval = mem_get_slot(&eng->mem, frame_base, slot);
        uint16_t stid = val_get_type(&eng->mem, sval);
        if (stid == MEM_TYPE_VOID) continue;
        if (new_var_cnt >= ENG_SESSION_MAX_VARS) {
            memcpy(S->vars, saved_vars, sizeof(S->vars));
            S->var_count = saved_var_cnt;
            free(saved_vars);
            if (P.local_count > 0) mem_pop_frame(&eng->mem, frame_base);
            mem_err(&eng->mem, "Session variable limit (%d) exceeded", ENG_SESSION_MAX_VARS);
            return false;
        }

        EngSessionVar *sv = &S->vars[new_var_cnt++];
        memset(sv, 0, sizeof(*sv));

        StrSlice vname = P.locals[v].name;
        uint16_t nlen  = vname.len < 31 ? vname.len : 31;
        memcpy(sv->name, mem_str_ptr(&eng->mem, vname), nlen);
        sv->name[nlen] = '\0';

        eng_type_name(eng, stid, sv->type_name, sizeof(sv->type_name));

        if (stid == eng->mem.vec_type_id && eng->mem.vec_type_id != 0) {
            uint16_t hdr_idx  = val_get_pool_idx(sval);
            uint16_t raw_hdr0 = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(hdr_idx + 0));
            uint8_t  elem_uid = (uint8_t)((raw_hdr0 >> 8u) & 0xFFu);
            eng_unit_name(eng, elem_uid, sv->unit_name, sizeof(sv->unit_name));
        } else {
            eng_unit_name(eng, val_get_unit(&eng->mem, sval), sv->unit_name, sizeof(sv->unit_name));
        }

        uint8_t lt = eng->mem.slot_locked_type[frame_base + slot];
        if (lt != 0 && lt != SYM_TYPE_ANY) {
            eng_type_name(eng, lt, sv->lock_type_name, sizeof(sv->lock_type_name));
        } else {
            memcpy(sv->lock_type_name, "*", 2);
        }

        uint8_t lu = eng->mem.slot_locked_unit[frame_base + slot];
        if (lu == SYM_UNIT_LOCK_NONE) {
            memcpy(sv->lock_unit_name, "none", 5);
        } else if (lu != 0 && lu != SYM_UNIT_GENERIC && lu != SYM_UNIT_OMITTED) {
            eng_unit_name(eng, lu, sv->lock_unit_name, sizeof(sv->lock_unit_name));
        } else {
            memcpy(sv->lock_unit_name, "*", 2);
        }

        if (!eng_snapshot_format_canonical_val(eng, sval, sv->val_text, sizeof(sv->val_text))) {
            char bad_name[32];
            memcpy(bad_name, sv->name, sizeof(bad_name));
            memcpy(S->vars, saved_vars, sizeof(S->vars));
            S->var_count = saved_var_cnt;
            free(saved_vars);
            if (P.local_count > 0) mem_pop_frame(&eng->mem, frame_base);
            mem_err(&eng->mem, "Session variable '%s' exceeds maximum serialized length (%d bytes)",
                    bad_name, ENG_SESSION_VAL_MAX - 1);
            return false;
        }
    }
    S->var_count = new_var_cnt;

    /* 7. Build and record the order-tagged raw-text snapshot for this evaluation */
    if (!eng_snapshot_record_eval(eng, S, res)) {
        memcpy(S->vars, saved_vars, sizeof(S->vars));
        S->var_count = saved_var_cnt;
        free(saved_vars);
        if (P.local_count > 0) mem_pop_frame(&eng->mem, frame_base);
        mem_err(&eng->mem, "Session snapshot exceeds maximum capacity (%d bytes)", ENG_SNAPSHOT_MAX_LEN - 1);
        return false;
    }
    free(saved_vars);

    if (P.local_count > 0) {
        mem_pop_frame(&eng->mem, frame_base);
    }
    if (out_val) *out_val = res;
    return true;
}
