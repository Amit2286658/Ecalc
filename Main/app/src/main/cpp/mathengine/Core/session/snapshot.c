/*
 * snapshot.c — Order-Tagged Raw-Text Session Snapshots & Continuation
 *
 * Implements:
 *   - `eng_snapshot_format_canonical_val`: Lossless MEP expression serializer
 *     for scalars, strings, structs, vectors, matrices, maps, and lambda closures
 *     with strict truncation detection (`bool` return).
 *   - `eng_snapshot_record_eval`: Order-tagged (`seq:1..N`), 32-bit FNV-1a
 *     integrity hash-chained (`prev`/`hash`) raw-text snapshot builder with
 *     sliding window continuation when `eval_count` reaches `ENG_SESSION_MAX_EVALS`.
 *   - `eng_session_snapshot_count`, `eng_session_snapshot_rewind`,
 *     `eng_session_snapshot_next`, `eng_session_snapshot_at`: Iterative and
 *     indexed snapshot accessors.
 *   - `eng_session_restore`: Verifies full, contiguous, uncorrupted snapshot
 *     history (even if supplied out of order), validates that every restored
 *     variable expression parses cleanly, and reconstructs an active in-memory
 *     session ready to continue evaluation.
 */

#include "snapshot.h"
#include "math_engine.h"
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static bool snap_buf_append(char *buf, size_t cap, size_t *used, const char *fmt, ...) {
    if (!buf || cap == 0 || *used + 1 >= cap) return false;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf + *used, cap - *used, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= cap - *used) {
        *used = cap - 1;
        buf[cap - 1] = '\0';
        return false;
    }
    *used += (size_t)n;
    return true;
}

static bool format_canonical_f64(double dv, char *buf, size_t cap, bool is_iota) {
    if (isnan(dv)) {
        int n = snprintf(buf, cap, is_iota ? "(nan)i" : "nan");
        return n >= 0 && (size_t)n < cap;
    }
    if (isinf(dv)) {
        if (dv < 0.0) {
            int n = snprintf(buf, cap, is_iota ? "(-inf)i" : "(-inf)");
            return n >= 0 && (size_t)n < cap;
        }
        int n = snprintf(buf, cap, is_iota ? "(inf)i" : "inf");
        return n >= 0 && (size_t)n < cap;
    }
    if (dv == 0.0) dv = 0.0;
    int n = snprintf(buf, cap, is_iota ? "(%.17g)i" : "%.17g", dv);
    return n >= 0 && (size_t)n < cap;
}

static bool format_canonical_str_escaped(const char *raw, uint16_t raw_len, char *buf, size_t cap) {
    size_t w = 0;
    if (cap < 3) return false;
    buf[w++] = '"';
    for (uint16_t i = 0; i < raw_len; ++i) {
        char c = raw[i];
        const char *esc = NULL;
        if (c == '\\')      esc = "\\\\";
        else if (c == '"')  esc = "\\\"";
        else if (c == '\n') esc = "\\n";
        else if (c == '\t') esc = "\\t";
        else if (c == '\r') esc = "\\r";
        if (esc) {
            if (w + 3 >= cap) { buf[w] = '\0'; return false; }
            buf[w++] = esc[0];
            buf[w++] = esc[1];
        } else {
            if (w + 2 >= cap) { buf[w] = '\0'; return false; }
            buf[w++] = c;
        }
    }
    buf[w++] = '"';
    buf[w] = '\0';
    return true;
}

static bool format_canonical_struct_at(const Engine *eng, uint16_t type_id, uint16_t pool_idx,
                                       uint8_t depth, char *buf, size_t cap);

static bool format_canonical_vec_at(const Engine *eng, uint16_t hdr_idx, uint8_t depth,
                                    char *buf, size_t cap) {
    if (depth > 8 || cap == 0) return false;
    uint16_t raw_hdr0 = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(hdr_idx + 0));
    uint16_t elem_tid = (uint16_t)(raw_hdr0 & 0xFFu);
    uint32_t len      = (uint32_t)mem_read_pool(&eng->mem, (uint16_t)(hdr_idx + 1));
    uint16_t data_idx = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(hdr_idx + 3));
    uint16_t stride   = (elem_tid > 0 && elem_tid < eng->sym.type_count)
                        ? eng->sym.types[elem_tid].flat_size : 1;

    if (elem_tid == 0 || elem_tid >= eng->sym.type_count) {
        int n = snprintf(buf, cap, "vec()");
        return n >= 0 && (size_t)n < cap;
    }

    char etname[32];
    eng_type_name(eng, elem_tid, etname, sizeof(etname));
    size_t used = 0;
    buf[0] = '\0';
    if (!snap_buf_append(buf, cap, &used, "list<%s>(", etname)) return false;

    for (uint32_t i = 0; i < len; ++i) {
        uint32_t off = i * (uint32_t)stride;
        char elem_str[512];
        bool ok = false;
        if (elem_tid == eng->mem.str_type_id && elem_tid != 0) {
            uint32_t packed = (uint32_t)mem_data_read(&eng->mem, data_idx, off);
            StrSlice s = { (uint16_t)(packed >> 16), (uint16_t)(packed & 0xFFFFu) };
            ok = format_canonical_str_escaped(mem_str_ptr(&eng->mem, s), s.len, elem_str, sizeof(elem_str));
        } else if (elem_tid == eng->mem.vec_type_id && elem_tid != 0) {
            ok = format_canonical_vec_at(eng, (uint16_t)(data_idx + off), (uint8_t)(depth + 1), elem_str, sizeof(elem_str));
        } else if (!eng->sym.types[elem_tid].is_consuming) {
            ok = format_canonical_struct_at(eng, elem_tid, (uint16_t)(data_idx + off), (uint8_t)(depth + 1), elem_str, sizeof(elem_str));
        } else if (eng->sym.types[elem_tid].parser_intr == INTR_PARSE_F64_TRIM1) {
            ok = format_canonical_f64(mem_data_read(&eng->mem, data_idx, off), elem_str, sizeof(elem_str), true);
        } else {
            ok = format_canonical_f64(mem_data_read(&eng->mem, data_idx, off), elem_str, sizeof(elem_str), false);
        }
        if (!ok) return false;
        if (!snap_buf_append(buf, cap, &used, "%s%s", (i > 0 ? ", " : ""), elem_str)) return false;
    }
    return snap_buf_append(buf, cap, &used, ")");
}

static bool format_canonical_struct_at(const Engine *eng, uint16_t type_id, uint16_t pool_idx,
                                       uint8_t depth, char *buf, size_t cap) {
    if (depth > 8 || cap == 0) return false;
    if (type_id == eng->mem.vec_type_id && type_id != 0) {
        return format_canonical_vec_at(eng, pool_idx, depth, buf, cap);
    }
    if (type_id == eng->mem.mat_type_id && type_id != 0) {
        eng_format_mat_at(eng, pool_idx, true, buf, cap);
        return strlen(buf) + 1 < cap;
    }
    if (type_id == eng->mem.fn_type_id && type_id != 0) {
        uint16_t body_ast = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(pool_idx + 0));
        for (uint8_t i = 0; i < eng->mem.fn_src_count; ++i) {
            if (eng->mem.fn_body_ast[i] == body_ast) {
                StrSlice s = eng->mem.fn_src_slice[i];
                if ((size_t)s.len + 1u > cap) return false;
                memcpy(buf, mem_str_ptr(&eng->mem, s), s.len);
                buf[s.len] = '\0';
                return true;
            }
        }
        int n = snprintf(buf, cap, "() => 0");
        return n >= 0 && (size_t)n < cap;
    }
    if (type_id == eng->mem.map_type_id && type_id != 0) {
        uint16_t len      = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(pool_idx + 1));
        uint16_t data_idx = (uint16_t)mem_read_pool(&eng->mem, (uint16_t)(pool_idx + 3));
        size_t used = 0;
        buf[0] = '\0';
        if (!snap_buf_append(buf, cap, &used, "dict(")) return false;
        for (uint16_t i = 0; i < len; ++i) {
            StrSlice kslice = mem_map_entry_key(&eng->mem, data_idx, i);
            Value    vval   = mem_map_entry_val(&eng->mem, data_idx, i);
            char kstr[256], vstr[512];
            if (!format_canonical_str_escaped(mem_str_ptr(&eng->mem, kslice), kslice.len, kstr, sizeof(kstr))) {
                return false;
            }
            if (!eng_snapshot_format_canonical_val(eng, vval, vstr, sizeof(vstr))) {
                return false;
            }
            if (!snap_buf_append(buf, cap, &used, "%s%s, %s", (i > 0 ? ", " : ""), kstr, vstr)) {
                return false;
            }
        }
        return snap_buf_append(buf, cap, &used, ")");
    }

    const TypeDef *td = &eng->sym.types[type_id];
    char tname[32];
    eng_type_name(eng, type_id, tname, sizeof(tname));

    size_t used = 0;
    buf[0] = '\0';
    if (!snap_buf_append(buf, cap, &used, "%s(", tname)) return false;
    for (uint8_t i = 0; i < td->field_count; ++i) {
        const FieldDef *fd = &eng->sym.fields[td->start_idx + i];
        char f_str[256];
        bool ok = false;
        if (!eng->sym.types[fd->type_id].is_consuming) {
            ok = format_canonical_struct_at(eng, fd->type_id, (uint16_t)(pool_idx + fd->flat_offset),
                                            (uint8_t)(depth + 1), f_str, sizeof(f_str));
        } else {
            ok = format_canonical_f64(mem_read_pool(&eng->mem, (uint16_t)(pool_idx + fd->flat_offset)),
                                      f_str, sizeof(f_str), false);
        }
        if (!ok) return false;
        if (!snap_buf_append(buf, cap, &used, "%s%s", (i > 0 ? ", " : ""), f_str)) return false;
    }
    return snap_buf_append(buf, cap, &used, ")");
}

bool eng_snapshot_format_canonical_val(const Engine *eng, Value val, char *buf, size_t cap) {
    if (!eng || !buf || cap == 0) return false;
    uint16_t tid = eng_val_type(eng, val);
    if (tid == 0 || tid >= eng->sym.type_count) {
        int n = snprintf(buf, cap, "0");
        return n >= 0 && (size_t)n < cap;
    }
    const TypeDef *td = &eng->sym.types[tid];
    if (td->is_consuming) {
        if (td->parser_intr == INTR_PARSE_STR_SLICE) {
            StrSlice s = val_get_str(&eng->mem, val);
            return format_canonical_str_escaped(mem_str_ptr(&eng->mem, s), s.len, buf, cap);
        } else if (td->parser_intr == INTR_PARSE_F64_TRIM1) {
            return format_canonical_f64(val_get_f64(&eng->mem, val), buf, cap, true);
        } else {
            return format_canonical_f64(val_get_f64(&eng->mem, val), buf, cap, false);
        }
    } else {
        return format_canonical_struct_at(eng, tid, val_get_pool_idx(val), 0, buf, cap);
    }
}

static bool escape_snap_field(const char *in, char *out, size_t out_cap) {
    size_t w = 0;
    for (size_t i = 0; in[i] != '\0'; ++i) {
        char c = in[i];
        if (c == '\\' || c == '|' || c == ';' || c == '\n' || c == '\r') {
            if (w + 2 >= out_cap) { out[w] = '\0'; return false; }
            out[w++] = '\\';
            if (c == '\\')      out[w++] = '\\';
            else if (c == '|')  out[w++] = 'p';
            else if (c == ';')  out[w++] = 's';
            else if (c == '\n') out[w++] = 'n';
            else                out[w++] = 'r';
        } else {
            if (w + 1 >= out_cap) { out[w] = '\0'; return false; }
            out[w++] = c;
        }
    }
    out[w] = '\0';
    return true;
}

static bool unescape_snap_field(const char *in, size_t in_len, char *out, size_t out_cap) {
    size_t w = 0;
    for (size_t i = 0; i < in_len; ++i) {
        if (w + 1 >= out_cap) {
            out[w] = '\0';
            return false;
        }
        if (in[i] == '\\' && i + 1 < in_len) {
            char nxt = in[++i];
            if (nxt == 'p')       out[w++] = '|';
            else if (nxt == 's')  out[w++] = ';';
            else if (nxt == 'n')  out[w++] = '\n';
            else if (nxt == 'r')  out[w++] = '\r';
            else                  out[w++] = nxt;
        } else {
            out[w++] = in[i];
        }
    }
    out[w] = '\0';
    return true;
}

static uint32_t calc_snapshot_hash(uint32_t seq, uint32_t prev_hash, const char *payload) {
    uint32_t h = 2166136261u;
    for (int b = 0; b < 4; ++b) {
        h ^= (uint32_t)((seq >> (b * 8)) & 0xFFu);
        h *= 16777619u;
    }
    for (int b = 0; b < 4; ++b) {
        h ^= (uint32_t)((prev_hash >> (b * 8)) & 0xFFu);
        h *= 16777619u;
    }
    if (payload) {
        for (size_t i = 0; payload[i] != '\0'; ++i) {
            h ^= (uint8_t)payload[i];
            h *= 16777619u;
        }
    }
    return (h == 0) ? 1u : h;
}

/* When a session exceeds `ENG_SESSION_MAX_EVALS`, slide the snapshot history window
 * by 1 and re-chain `seq:1..ENG_SESSION_MAX_EVALS-1` so the session can continue
 * indefinitely while keeping its latest `ENG_SESSION_MAX_EVALS` snapshots restorable. */
static void slide_snapshot_window(EngSession *S) {
    uint32_t prev_h = 0;
    for (uint16_t i = 0; i + 1 < ENG_SESSION_MAX_EVALS; ++i) {
        const char *src = S->snapshots[i + 1];
        const char *p_vars = strstr(src, "|vars:");
        const char *payload = p_vars ? (p_vars + 1) : "";
        uint16_t seq = (uint16_t)(i + 1);
        uint32_t cur_h = calc_snapshot_hash((uint32_t)seq, prev_h, payload);
        char rebuilt[ENG_SNAPSHOT_MAX_LEN];
        snprintf(rebuilt, sizeof(rebuilt),
                 "MSP1|seq:%u|prev:%08X|hash:%08X|%s",
                 (unsigned)seq, (unsigned)prev_h, (unsigned)cur_h, payload);
        memcpy(S->snapshots[i], rebuilt, sizeof(rebuilt));
        prev_h = cur_h;
    }
    S->eval_count = (uint16_t)(ENG_SESSION_MAX_EVALS - 1);
    S->chain_hash = prev_h;
}

bool eng_snapshot_record_eval(const Engine *eng, EngSession *S, Value res) {
    if (!eng || !S) return false;

    size_t pay_cap = ENG_SNAPSHOT_MAX_LEN - 48u;
    char *payload = (char *)malloc(pay_cap);
    char *esc_val = (char *)malloc(ENG_SESSION_VAL_MAX * 2u);
    if (!payload || !esc_val) {
        free(payload);
        free(esc_val);
        return false;
    }

    size_t p_used = 0;
    payload[0] = '\0';
    bool ok = snap_buf_append(payload, pay_cap, &p_used, "vars:%u|", (unsigned)S->var_count);
    for (uint8_t i = 0; ok && i < S->var_count; ++i) {
        if (!escape_snap_field(S->vars[i].val_text, esc_val, ENG_SESSION_VAL_MAX * 2u)) {
            ok = false;
            break;
        }
        ok = snap_buf_append(payload, pay_cap, &p_used,
                             "%s%s:%s:%s:%s:%s=%s",
                             (i > 0 ? ";" : ""),
                             S->vars[i].name,
                             S->vars[i].type_name,
                             S->vars[i].unit_name,
                             S->vars[i].lock_type_name,
                             S->vars[i].lock_unit_name,
                             esc_val);
    }

    char res_type[32], res_unit[32], res_raw[256], res_esc[512];
    if (ok) {
        eng_type_name(eng, val_get_type(&eng->mem, res), res_type, sizeof(res_type));
        eng_unit_name(eng, val_get_unit(&eng->mem, res), res_unit, sizeof(res_unit));
        eng_format_value(eng, res, res_raw, sizeof(res_raw));
        if (!escape_snap_field(res_raw, res_esc, sizeof(res_esc))) {
            ok = false;
        } else {
            ok = snap_buf_append(payload, pay_cap, &p_used,
                                 "|res:%s:%s=%s", res_type, res_unit, res_esc);
        }
    }

    free(esc_val);
    if (!ok) {
        free(payload);
        return false;
    }

    if (S->eval_count >= ENG_SESSION_MAX_EVALS) {
        slide_snapshot_window(S);
    }

    uint16_t seq       = (uint16_t)(S->eval_count + 1);
    uint32_t prev_hash = S->chain_hash;
    uint32_t cur_hash  = calc_snapshot_hash((uint32_t)seq, prev_hash, payload);

    int written = snprintf(S->snapshots[S->eval_count], ENG_SNAPSHOT_MAX_LEN,
                           "MSP1|seq:%u|prev:%08X|hash:%08X|%s",
                           (unsigned)seq, (unsigned)prev_hash, (unsigned)cur_hash, payload);
    free(payload);
    if (written < 0 || (size_t)written >= ENG_SNAPSHOT_MAX_LEN) {
        return false;
    }

    S->eval_count       = seq;
    S->chain_hash       = cur_hash;
    S->snap_iter_cursor = 0;
    return true;
}

uint16_t eng_session_snapshot_count(const Engine *eng, int session_id) {
    if (!eng || session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        return 0;
    }
    return eng->sessions[session_id].eval_count;
}

bool eng_session_snapshot_rewind(Engine *eng, int session_id) {
    if (!eng) return false;
    mem_clear_err(&eng->mem);
    if (session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        mem_err(&eng->mem, "Invalid or inactive session ID (%d)", session_id);
        return false;
    }
    eng->sessions[session_id].snap_iter_cursor = 0;
    return true;
}

const char *eng_session_snapshot_next(Engine *eng, int session_id) {
    if (!eng || session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        return NULL;
    }
    EngSession *S = &eng->sessions[session_id];
    if (S->snap_iter_cursor >= S->eval_count) {
        S->snap_iter_cursor = 0;
        return NULL;
    }
    return S->snapshots[S->snap_iter_cursor++];
}

const char *eng_session_snapshot_at(const Engine *eng, int session_id, uint16_t idx) {
    if (!eng || session_id < 0 || session_id >= ENG_MAX_SESSIONS || !eng->sessions[session_id].in_use) {
        return NULL;
    }
    const EngSession *S = &eng->sessions[session_id];
    if (idx >= S->eval_count) return NULL;
    return S->snapshots[idx];
}

static bool parse_snapshot_header_and_verify(MemArena *mem, const char *snap,
                                             uint16_t *out_seq, uint32_t *out_prev,
                                             uint32_t *out_hash, const char **out_payload) {
    if (!snap || strncmp(snap, "MSP1|seq:", 9) != 0) {
        mem_err(mem, "Invalid snapshot header (expected 'MSP1|seq:...')");
        return false;
    }
    const char *p = snap + 9;
    char *endp = NULL;
    unsigned long seq_ul = strtoul(p, &endp, 10);
    if (endp == p || seq_ul == 0 || seq_ul > 65534ul || strncmp(endp, "|prev:", 6) != 0) {
        mem_err(mem, "Malformed 'seq' field in session snapshot");
        return false;
    }
    p = endp + 6;
    if (strlen(p) < 8) {
        mem_err(mem, "Truncated 'prev' hash in session snapshot");
        return false;
    }
    char hex_buf[9];
    memcpy(hex_buf, p, 8);
    hex_buf[8] = '\0';
    uint32_t prev_h = (uint32_t)strtoul(hex_buf, &endp, 16);
    if (endp != hex_buf + 8 || strncmp(p + 8, "|hash:", 6) != 0) {
        mem_err(mem, "Malformed 'prev' hash in session snapshot");
        return false;
    }
    p += 14;
    if (strlen(p) < 9 || p[8] != '|') {
        mem_err(mem, "Malformed 'hash' field in session snapshot");
        return false;
    }
    memcpy(hex_buf, p, 8);
    hex_buf[8] = '\0';
    uint32_t cur_h = (uint32_t)strtoul(hex_buf, &endp, 16);
    if (endp != hex_buf + 8) {
        mem_err(mem, "Invalid hex digit in snapshot hash");
        return false;
    }
    const char *payload = p + 9;
    uint32_t expected_h = calc_snapshot_hash((uint32_t)seq_ul, prev_h, payload);
    if (cur_h != expected_h) {
        mem_err(mem, "Corrupted session snapshot checksum mismatch at seq:%lu", seq_ul);
        return false;
    }
    *out_seq     = (uint16_t)seq_ul;
    *out_prev    = prev_h;
    *out_hash    = cur_h;
    *out_payload = payload;
    return true;
}

static bool parse_snapshot_vars(MemArena *mem, const char *payload,
                                EngSessionVar *out_vars, uint8_t *out_var_count) {
    *out_var_count = 0;
    if (strncmp(payload, "vars:", 5) != 0) {
        mem_err(mem, "Malformed snapshot payload (missing 'vars:')");
        return false;
    }
    const char *p = payload + 5;
    char *endp = NULL;
    unsigned long vcnt_ul = strtoul(p, &endp, 10);
    if (endp == p || vcnt_ul > ENG_SESSION_MAX_VARS || *endp != '|') {
        mem_err(mem, "Invalid variable count in snapshot payload");
        return false;
    }
    p = endp + 1;

    const char *vars_end = NULL;
    for (const char *scan = p; *scan != '\0'; ++scan) {
        if (*scan == '\\' && scan[1] != '\0') {
            scan++;
            continue;
        }
        if (*scan == '|' && strncmp(scan, "|res:", 5) == 0) {
            vars_end = scan;
            break;
        }
    }
    if (!vars_end) {
        mem_err(mem, "Malformed snapshot payload (missing '|res:' section)");
        return false;
    }

    if (vcnt_ul == 0) {
        if (p != vars_end) {
            mem_err(mem, "Snapshot variable count is 0 but variable entries are present");
            return false;
        }
        return true;
    }

    uint8_t parsed_cnt = 0;
    const char *cur = p;
    while (cur < vars_end && parsed_cnt < (uint8_t)vcnt_ul) {
        const char *entry_end = vars_end;
        for (const char *scan = cur; scan < vars_end; ++scan) {
            if (*scan == '\\' && scan + 1 < vars_end) {
                scan++;
                continue;
            }
            if (*scan == ';') {
                entry_end = scan;
                break;
            }
        }

        const char *fields[5];
        size_t      flens[5];
        const char *fcur = cur;
        for (int f = 0; f < 5; ++f) {
            char sep = (f < 4) ? ':' : '=';
            const char *delim = (const char *)memchr(fcur, sep, (size_t)(entry_end - fcur));
            if (!delim || delim == fcur || (size_t)(delim - fcur) >= 32u) {
                mem_err(mem, "Malformed variable descriptor in session snapshot");
                return false;
            }
            fields[f] = fcur;
            flens[f]  = (size_t)(delim - fcur);
            fcur      = delim + 1;
        }

        EngSessionVar *sv = &out_vars[parsed_cnt++];
        memset(sv, 0, sizeof(*sv));
        memcpy(sv->name,           fields[0], flens[0]); sv->name[flens[0]]           = '\0';
        memcpy(sv->type_name,      fields[1], flens[1]); sv->type_name[flens[1]]      = '\0';
        memcpy(sv->unit_name,      fields[2], flens[2]); sv->unit_name[flens[2]]      = '\0';
        memcpy(sv->lock_type_name, fields[3], flens[3]); sv->lock_type_name[flens[3]] = '\0';
        memcpy(sv->lock_unit_name, fields[4], flens[4]); sv->lock_unit_name[flens[4]] = '\0';

        if (!unescape_snap_field(fcur, (size_t)(entry_end - fcur), sv->val_text, sizeof(sv->val_text)) ||
            sv->val_text[0] == '\0') {
            mem_err(mem, "Invalid or truncated variable value expression in session snapshot");
            return false;
        }

        cur = (entry_end < vars_end) ? (entry_end + 1) : vars_end;
    }

    if (parsed_cnt != (uint8_t)vcnt_ul || cur != vars_end) {
        mem_err(mem, "Snapshot variable count mismatch");
        return false;
    }
    *out_var_count = parsed_cnt;
    return true;
}

static bool validate_restored_session_vars(Engine *eng, const EngSession *S) {
    mem_clear_err(&eng->mem);
    mem_rewind_scratch(&eng->mem);

    Parser P;
    parser_init(&P, &eng->mem, &eng->sym, "", 0, 0, false, false);
    for (uint8_t i = 0; i < S->var_count; ++i) {
        uint16_t tid = sym_find_type(&eng->sym, &eng->mem,
                                     S->vars[i].type_name,
                                     (uint16_t)strlen(S->vars[i].type_name));
        if (tid == MEM_TYPE_VOID) tid = eng->mem.fast_real_type;
        (void)parser_add_local(&P, S->vars[i].name, (uint16_t)strlen(S->vars[i].name), tid);
        if (eng->mem.has_error) return false;
    }
    for (uint8_t i = 0; i < S->var_count; ++i) {
        Parser VP;
        uint16_t vlen = (uint16_t)strlen(S->vars[i].val_text);
        parser_init(&VP, &eng->mem, &eng->sym, S->vars[i].val_text, vlen, 0, false, false);
        memcpy(VP.locals, P.locals, sizeof(P.locals));
        memcpy(VP.slot_types, P.slot_types, sizeof(P.slot_types));
        VP.vis_count   = P.vis_count;
        VP.local_count = P.local_count;
        lex_next(&VP.lex);
        uint16_t r = parser_parse_expr(&VP, 0);
        if (eng->mem.has_error || r == MEM_NO_IDX || VP.lex.cur.kind != TOK_EOF) {
            if (!eng->mem.has_error) {
                mem_err(&eng->mem, "Corrupted variable expression '%s' in restored session", S->vars[i].name);
            }
            return false;
        }
        P.local_count = VP.local_count;
        memcpy(P.slot_types, VP.slot_types, sizeof(P.slot_types));
    }
    return true;
}

int eng_session_restore(Engine *eng, const char *const *snapshots, size_t snapshot_count) {
    if (!eng) return -1;
    mem_clear_err(&eng->mem);

    if (!snapshots || snapshot_count == 0 || snapshot_count > ENG_SESSION_MAX_EVALS) {
        mem_err(&eng->mem, "Invalid snapshot count (%zu) for session restore", snapshot_count);
        return -1;
    }

    int         ordered_idx[ENG_SESSION_MAX_EVALS];
    uint32_t    ordered_prev[ENG_SESSION_MAX_EVALS];
    uint32_t    ordered_hash[ENG_SESSION_MAX_EVALS];
    const char *ordered_payload[ENG_SESSION_MAX_EVALS];

    for (size_t i = 0; i < ENG_SESSION_MAX_EVALS; ++i) {
        ordered_idx[i]     = -1;
        ordered_prev[i]    = 0;
        ordered_hash[i]    = 0;
        ordered_payload[i] = NULL;
    }

    for (size_t i = 0; i < snapshot_count; ++i) {
        if (!snapshots[i] || strlen(snapshots[i]) >= ENG_SNAPSHOT_MAX_LEN) {
            mem_err(&eng->mem, "Null or oversized snapshot at index %zu", i);
            return -1;
        }
        uint16_t    seq     = 0;
        uint32_t    prev_h  = 0;
        uint32_t    cur_h   = 0;
        const char *payload = NULL;
        if (!parse_snapshot_header_and_verify(&eng->mem, snapshots[i],
                                              &seq, &prev_h, &cur_h, &payload)) {
            return -1;
        }
        if (seq < 1 || seq > snapshot_count) {
            mem_err(&eng->mem,
                    "Incomplete session snapshots: order tag seq:%u out of contiguous range 1..%zu",
                    (unsigned)seq, snapshot_count);
            return -1;
        }
        if (ordered_idx[seq - 1] >= 0) {
            mem_err(&eng->mem, "Duplicate evaluation order tag seq:%u in snapshots", (unsigned)seq);
            return -1;
        }
        ordered_idx[seq - 1]     = (int)i;
        ordered_prev[seq - 1]    = prev_h;
        ordered_hash[seq - 1]    = cur_h;
        ordered_payload[seq - 1] = payload;
    }

    uint32_t expected_prev = 0;
    for (size_t k = 0; k < snapshot_count; ++k) {
        if (ordered_idx[k] < 0) {
            mem_err(&eng->mem, "Incomplete session snapshots: missing evaluation seq:%zu", k + 1);
            return -1;
        }
        if (ordered_prev[k] != expected_prev) {
            mem_err(&eng->mem,
                    "Broken session snapshot chain at seq:%zu (prev hash %08X != %08X)",
                    k + 1, (unsigned)ordered_prev[k], (unsigned)expected_prev);
            return -1;
        }
        expected_prev = ordered_hash[k];
    }

    int sid = eng_session_open(eng);
    if (sid < 0) return -1;

    EngSession *S = &eng->sessions[sid];
    uint8_t final_var_cnt = 0;
    for (size_t k = 0; k < snapshot_count; ++k) {
        if (!parse_snapshot_vars(&eng->mem, ordered_payload[k], S->vars, &final_var_cnt)) {
            memset(S, 0, sizeof(*S));
            return -1;
        }
    }

    S->eval_count       = (uint16_t)snapshot_count;
    S->snap_iter_cursor = 0;
    S->chain_hash       = expected_prev;
    S->var_count        = final_var_cnt;

    if (!validate_restored_session_vars(eng, S)) {
        memset(S, 0, sizeof(*S));
        return -1;
    }

    for (size_t k = 0; k < snapshot_count; ++k) {
        snprintf(S->snapshots[k], ENG_SNAPSHOT_MAX_LEN, "%s", snapshots[ordered_idx[k]]);
    }

    return sid;
}
