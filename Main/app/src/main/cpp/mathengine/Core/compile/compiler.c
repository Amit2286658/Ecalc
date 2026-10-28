/*
 * compiler.c — In-Memory `.math` Compilation Unit Stager & Declaration Compiler
 *
 * Implements Phase 1 (Compilation) of extension building:
 *   - `ext_build_init` / `ext_build_create` / `ext_build_destroy` / `ext_compile_unit`:
 *     Scans raw `.math` source strings, splits them into `[module]` compilation units,
 *     extracts `<[dep]` module dependencies, and rejects duplicate named modules.
 *   - `ext_compile_slice`: Compiles `$type`, syntax delimiter, constant, and
 *     function/operator overload declarations for a single module slice.
 */

#include "compiler.h"
#include "parser.h"
#include "hardcode.h"
#include "evaluator.h"
#include "../lib/delegate/delegate.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    MemArena   *mem;
    SymTable   *tab;
    const char *src;
    uint16_t    len;
    uint16_t    pos;
    uint16_t    cur_mod;
} ExtLoader;

static void ext_skip_ws(ExtLoader *L) {
    while (L->pos < L->len) {
        char c = L->src[L->pos];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') L->pos++;
        else if (c == '#') {
            while (L->pos < L->len && L->src[L->pos] != '\n') L->pos++;
        } else break;
    }
}

static bool ext_match_char(ExtLoader *L, char expected) {
    ext_skip_ws(L);
    if (L->pos < L->len && L->src[L->pos] == expected) {
        L->pos++;
        return true;
    }
    return false;
}

static StrSlice ext_read_ident(ExtLoader *L) {
    ext_skip_ws(L);
    uint16_t start = L->pos;
    while (L->pos < L->len && lex_is_ident_cont((unsigned char)L->src[L->pos])) {
        L->pos++;
    }
    return mem_intern_str(L->mem, L->src + start, (uint16_t)(L->pos - start));
}

static StrSlice ext_read_quoted(ExtLoader *L) {
    ext_skip_ws(L);
    if (L->pos >= L->len || L->src[L->pos] != '"') {
        mem_err(L->mem, "Expected quoted string symbol in declaration");
        StrSlice empty = { 0, 0 };
        return empty;
    }
    L->pos++;
    char buf[64];
    uint16_t blen = 0;
    while (L->pos < L->len && L->src[L->pos] != '"') {
        if (L->src[L->pos] == '\\' && L->pos + 1 < L->len) {
            L->pos++;
        }
        if (blen >= sizeof(buf) - 1) {
            mem_err(L->mem, "Quoted symbol exceeds maximum length (63 bytes)");
            StrSlice empty = { 0, 0 };
            return empty;
        }
        buf[blen++] = L->src[L->pos];
        L->pos++;
    }
    if (L->pos >= L->len || L->src[L->pos] != '"') {
        mem_err(L->mem, "Unterminated quoted string symbol in declaration");
        StrSlice empty = { 0, 0 };
        return empty;
    }
    L->pos++;
    return mem_intern_str(L->mem, buf, blen);
}

static bool ext_has_duplicate_overload(const SymTable *tab, uint16_t sym_id, uint16_t mod_id,
                                       uint8_t fixity, uint8_t pcount,
                                       const uint16_t *ptypes, const uint8_t *punits) {
    if (sym_id >= tab->sym_count) return false;
    for (uint16_t i = tab->sym_first_ov[sym_id]; i != MEM_NO_IDX; i = tab->ov_next[i]) {
        const OpOverload *ov = &tab->overloads[i];
        if (ov->mod_id != mod_id || ov->fixity != fixity || ov->param_count != pcount) continue;
        bool same = true;
        for (uint8_t p = 0; p < pcount; ++p) {
            if (ov->param_types[p] != (uint8_t)ptypes[p] || ov->param_units[p] != punits[p]) {
                same = false;
                break;
            }
        }
        if (same) return true;
    }
    return false;
}

static void ext_parse_type_decl(ExtLoader *L) {
    MemArena *mem = L->mem;
    SymTable *tab = L->tab;
    L->pos++; /* skip '$' */
    StrSlice tname = ext_read_ident(L);
    if (mem->has_error || tname.len == 0) return;

    /* Reject duplicate type declarations */
    for (uint16_t i = 1; i < tab->type_count; ++i) {
        if (mem_slice_eq(mem, tab->types[i].name, tname)) {
            mem_err(mem, "Type '$%.*s' is already defined",
                    (int)tname.len, mem_str_ptr(mem, tname));
            return;
        }
    }

    if (tab->type_count >= SYM_MAX_TYPES) {
        mem_err(mem, "Type table overflow (max %d)", SYM_MAX_TYPES);
        return;
    }

    uint16_t tid = tab->type_count++;
    TypeDef *td  = &tab->types[tid];
    memset(td, 0, sizeof(*td));
    td->name   = tname;
    td->mod_id = L->cur_mod;

    ext_skip_ws(L);
    if (ext_match_char(L, '~')) {
        td->is_consuming = 1;
        td->flat_size    = 1;
        td->start_idx    = tab->pat_elem_count;
        uint8_t elem_cnt = 0;

        while (L->pos < L->len && !mem->has_error) {
            ext_skip_ws(L);
            if (L->pos + 1 < L->len && L->src[L->pos] == '=' && L->src[L->pos + 1] == '>') {
                L->pos = (uint16_t)(L->pos + 2);
                break;
            }
            if (tab->pat_elem_count >= SYM_MAX_PAT_ELEMS) {
                mem_err(mem, "Pattern element pool overflow");
                return;
            }
            PatElem *pe = &tab->pat_elems[tab->pat_elem_count++];
            memset(pe, 0, sizeof(*pe));
            elem_cnt++;

            char c = L->src[L->pos];
            if (c == '\\' && L->pos + 1 < L->len) {
                char esc = L->src[L->pos + 1];
                L->pos = (uint16_t)(L->pos + 2);
                if (esc == 'd') {
                    pe->kind = PAT_DIGIT;
                } else if (esc == 'a') {
                    pe->kind = PAT_ALPHA;
                } else {
                    mem_err(mem, "Invalid pattern escape '\\%c' (expected \\d or \\a)", esc);
                    return;
                }
            } else if (c == '"') {
                pe->kind  = PAT_LIT;
                pe->chars = ext_read_quoted(L);
            } else if (c == '[') {
                bool is_neg = (L->pos + 1 < L->len && L->src[L->pos + 1] == '^');
                L->pos = (uint16_t)(L->pos + (is_neg ? 2 : 1));
                char cset[32];
                uint16_t elen = 0;
                while (L->pos < L->len && L->src[L->pos] != ']') {
                    if (L->src[L->pos] == '\\' && L->pos + 1 < L->len) L->pos++;
                    if (elen >= sizeof(cset) - 1) {
                        mem_err(mem, "Character class exceeds maximum length (31 bytes)");
                        return;
                    }
                    cset[elen++] = L->src[L->pos];
                    L->pos++;
                }
                if (L->pos < L->len && L->src[L->pos] == ']') L->pos++;
                pe->kind  = is_neg ? PAT_NOT_CHARS : PAT_CHARS;
                pe->chars = mem_intern_str(mem, cset, elen);
            } else if (c == '(') {
                L->pos++;
                pe->kind = PAT_GROUP_START;
            } else if (c == ')') {
                L->pos++;
                pe->kind = PAT_GROUP_END;
            } else if (c == '|') {
                L->pos++;
                pe->kind = PAT_ALT;
            } else {
                mem_err(mem, "Invalid pattern syntax near '%c'", c);
                return;
            }

            if (pe->kind != PAT_GROUP_START && L->pos < L->len) {
                char q = L->src[L->pos];
                if (q == '+') { pe->quant = QUANT_PLUS; L->pos++; }
                else if (q == '*') { pe->quant = QUANT_STAR; L->pos++; }
                else if (q == '?') { pe->quant = QUANT_OPT;  L->pos++; }
            }
        }
        td->field_count = elem_cnt;

        if (!ext_match_char(L, '@')) {
            mem_err(mem, "Expected '@parser_intrinsic' after '=>' in consuming type");
            return;
        }
        StrSlice iname = ext_read_ident(L);
        td->parser_intr = (uint8_t)sym_lookup_intrinsic(mem_str_ptr(mem, iname), iname.len);
        if (td->parser_intr == INTR_PARSE_F64 && mem->fast_real_type == 0) {
            mem->fast_real_type = (uint8_t)tid;
        } else if (td->parser_intr == INTR_PARSE_STR_SLICE && mem->str_type_id == 0) {
            mem->str_type_id = (uint8_t)tid;
        } else if (td->parser_intr == INTR_VEC_TYPE) {
            td->is_consuming = 0;
            td->field_count  = 0;
            td->flat_size    = MEM_VEC_HDR_SLOTS;
            if (mem->vec_type_id == 0) {
                mem->vec_type_id = (uint8_t)tid;
            }
        } else if (td->parser_intr == INTR_MAT_TYPE) {
            td->is_consuming = 0;
            td->field_count  = 0;
            td->flat_size    = MEM_MAT_HDR_SLOTS;
            if (mem->mat_type_id == 0) {
                mem->mat_type_id = (uint8_t)tid;
            }
        } else if (td->parser_intr == INTR_FN_TYPE) {
            td->is_consuming = 0;
            td->field_count  = 0;
            td->flat_size    = MEM_FN_HDR_SLOTS;
            if (mem->fn_type_id == 0) {
                mem->fn_type_id = (uint8_t)tid;
            }
        } else if (td->parser_intr == INTR_MAP_TYPE) {
            td->is_consuming = 0;
            td->field_count  = 0;
            td->flat_size    = MEM_MAP_HDR_SLOTS;
            if (mem->map_type_id == 0) {
                mem->map_type_id = (uint8_t)tid;
            }
        } else if (td->parser_intr == INTR_ANY_TYPE || td->parser_intr == INTR_NONE_UNIT) {
            td->is_consuming = 0;
            td->field_count  = 0;
            td->flat_size    = 0;
        }
        if (tid < 64u) mem->type_stride[tid] = (uint8_t)td->flat_size;
    } else if (ext_match_char(L, '(')) {
        td->is_consuming = 0;
        td->start_idx    = tab->field_count;
        uint16_t flat_off = 0;
        uint8_t  fcount   = 0;

        while (L->pos < L->len && !mem->has_error) {
            if (ext_match_char(L, ')')) break;
            StrSlice fname = ext_read_ident(L);
            uint16_t ftid  = mem->fast_real_type;
            if (ext_match_char(L, ':')) {
                StrSlice ftype_s = ext_read_ident(L);
                ftid = sym_find_type(tab, mem, mem_str_ptr(mem, ftype_s), ftype_s.len);
                if (ftid == MEM_TYPE_VOID || ftid == SYM_TYPE_ANY) {
                    mem_err(mem, "Unknown field type '%.*s'", (int)ftype_s.len, mem_str_ptr(mem, ftype_s));
                    return;
                }
            }
            if (ftid == tid) {
                mem_err(mem, "Self-referential struct field '%.*s' in '$%.*s' is not allowed",
                        (int)fname.len, mem_str_ptr(mem, fname),
                        (int)tname.len, mem_str_ptr(mem, tname));
                return;
            }
            if (ftid >= tab->type_count || tab->types[ftid].flat_size == 0) {
                mem_err(mem, "Incomplete or invalid field type in '$%.*s'",
                        (int)tname.len, mem_str_ptr(mem, tname));
                return;
            }
            if (tab->field_count >= SYM_MAX_FIELDS) {
                mem_err(mem, "Struct field table overflow (max %d)", SYM_MAX_FIELDS);
                return;
            }
            FieldDef *fd = &tab->fields[tab->field_count++];
            fd->name        = fname;
            fd->type_id     = ftid;
            fd->flat_offset = flat_off;

            flat_off = (uint16_t)(flat_off + tab->types[ftid].flat_size);
            fcount++;
            ext_match_char(L, ',');
        }
        td->field_count = fcount;
        td->flat_size   = flat_off;
        if (tid < 64u) mem->type_stride[tid] = (uint8_t)flat_off;

        /* Optional same-line `=> @type_intrinsic` tag on struct type (e.g. `$complex(re, im) => @complex_type`) */
        while (L->pos < L->len && (L->src[L->pos] == ' ' || L->src[L->pos] == '\t')) L->pos++;
        if (L->pos + 1 < L->len && L->src[L->pos] == '=' && L->src[L->pos + 1] == '>') {
            L->pos = (uint16_t)(L->pos + 2);
            if (!ext_match_char(L, '@')) {
                mem_err(mem, "Expected '@type_intrinsic' after '=>' in struct type declaration");
                return;
            }
            StrSlice iname = ext_read_ident(L);
            td->parser_intr = (uint8_t)sym_lookup_intrinsic(mem_str_ptr(mem, iname), iname.len);
        }
    } else {
        mem_err(mem, "Expected '~' (consuming/container) or '(' (struct) in type declaration");
    }
}

static bool ext_resolve_type_and_unit(ExtLoader *L, StrSlice first_id,
                                      bool has_second, StrSlice second_id,
                                      uint16_t *out_type, uint8_t *out_unit) {
    MemArena *mem = L->mem;
    SymTable *tab = L->tab;
    const char *s1 = mem_str_ptr(mem, first_id);

    if (has_second) {
        uint16_t tid = sym_find_type(tab, mem, s1, first_id.len);
        if (tid == MEM_TYPE_VOID) {
            mem_err(mem, "Unknown entity type '%.*s'", (int)first_id.len, s1);
            return false;
        }
        const char *s2 = mem_str_ptr(mem, second_id);
        uint8_t uid = sym_find_or_add_unit(tab, mem, s2, second_id.len);
        *out_type = tid;
        *out_unit = uid;
        return !mem->has_error;
    }

    uint16_t tid = sym_find_type(tab, mem, s1, first_id.len);
    if (tid != MEM_TYPE_VOID) {
        *out_type = tid;
        *out_unit = SYM_UNIT_OMITTED;
        return true;
    }

    uint8_t uid = sym_find_or_add_unit(tab, mem, s1, first_id.len);
    *out_type = SYM_TYPE_ANY;
    *out_unit = uid;
    return !mem->has_error;
}

static bool ext_parse_one_param(ExtLoader *L, StrSlice *out_name,
                                uint16_t *out_type, uint8_t *out_unit) {
    if (!ext_match_char(L, '(')) return false;
    *out_name = ext_read_ident(L);
    if (sym_is_reserved_kw(L->tab, L->mem, mem_str_ptr(L->mem, *out_name), out_name->len)) {
        mem_err(L->mem, "Cannot use reserved keyword '%.*s' as a variable name",
                (int)out_name->len, mem_str_ptr(L->mem, *out_name));
        return false;
    }
    if (!ext_match_char(L, ':')) {
        *out_type = L->mem->fast_real_type ? L->mem->fast_real_type : 1;
        *out_unit = SYM_UNIT_OMITTED;
        if (!ext_match_char(L, ')')) {
            mem_err(L->mem, "Expected ')' after parameter declaration");
            return false;
        }
        return true;
    }
    StrSlice tname = ext_read_ident(L);
    bool has_unit  = false;
    StrSlice uname = { 0, 0 };
    if (ext_match_char(L, ',')) {
        has_unit = true;
        uname    = ext_read_ident(L);
    }
    if (!ext_resolve_type_and_unit(L, tname, has_unit, uname, out_type, out_unit)) {
        return false;
    }
    if (!ext_match_char(L, ')')) {
        mem_err(L->mem, "Expected ')' after parameter declaration");
        return false;
    }
    return true;
}

static bool ext_peek_ident_is_unit_annot(ExtLoader *L) {
    ext_skip_ws(L);
    uint16_t p = L->pos;
    if (p >= L->len || !lex_is_ident_start((unsigned char)L->src[p])) return false;
    uint16_t id_start = p;
    while (p < L->len && lex_is_ident_cont((unsigned char)L->src[p])) p++;
    uint16_t id_len = (uint16_t)(p - id_start);
    while (p < L->len && (L->src[p] == ' ' || L->src[p] == '\t' || L->src[p] == '\r' || L->src[p] == '\n')) p++;
    if (p < L->len && (L->src[p] == ':' || L->src[p] == '=')) return false;
    return sym_find_unit(L->tab, L->mem, L->src + id_start, id_len) != MEM_UNIT_NONE;
}

static bool ext_parse_return_spec(ExtLoader *L, uint16_t *out_rtype, uint8_t *out_runit) {
    ext_skip_ws(L);
    if (L->pos < L->len && L->src[L->pos] == '=') {
        *out_rtype = L->mem->fast_real_type ? L->mem->fast_real_type : 1;
        *out_runit = SYM_UNIT_OMITTED;
        return true;
    }
    if (L->pos + 1 < L->len && L->src[L->pos] == '-' && L->src[L->pos + 1] == '>') {
        L->pos = (uint16_t)(L->pos + 2);
    }
    StrSlice rtype_s = ext_read_ident(L);
    bool has_unit    = false;
    StrSlice runit_s = { 0, 0 };
    if (ext_match_char(L, ',')) {
        has_unit = true;
        runit_s  = ext_read_ident(L);
    }
    return ext_resolve_type_and_unit(L, rtype_s, has_unit, runit_s, out_rtype, out_runit);
}

static void ext_skip_default_expr(ExtLoader *L, uint16_t *out_start, uint16_t *out_end) {
    ext_skip_ws(L);
    *out_start = L->pos;
    int depth = 0;
    while (L->pos < L->len) {
        char c = L->src[L->pos];
        if (c == '"') {
            L->pos++;
            while (L->pos < L->len && L->src[L->pos] != '"') {
                if (L->src[L->pos] == '\\' && L->pos + 1 < L->len) L->pos++;
                L->pos++;
            }
            if (L->pos < L->len) L->pos++;
            continue;
        }
        if (c == '(' || c == '[' || c == '{') depth++;
        else if (c == ')' || c == ']' || c == '}') {
            if (depth == 0) break;
            depth--;
        } else if (c == ',' && depth == 0) {
            break;
        }
        L->pos++;
    }
    *out_end = L->pos;
}

static void ext_compile_body(ExtLoader *L, OpOverload *ov, StrSlice *pnames) {
    MemArena *mem = L->mem;
    SymTable *tab = L->tab;
    ext_skip_ws(L);

    Parser P;
    parser_init(&P, mem, tab, L->src, L->len, L->pos, true, true);
    P.param_count = ov->param_count;

    for (uint8_t i = 0; i < ov->param_count; ++i) {
        parser_add_local(&P, mem_str_ptr(mem, pnames[i]), pnames[i].len, ov->param_types[i]);
    }

    lex_next(&P.lex);
    uint16_t body_root = parser_parse_expr(&P, 0);
    (void)parser_check_labels(&P);
    ov->body_ast    = body_root;
    ov->local_count = P.local_count;
    ov->meta_tag    = 0;

    L->pos = (P.lex.cur.kind == TOK_EOF) ? P.lex.pos : P.lex.cur.off;

    if (body_root != MEM_NO_IDX) {
        const AstNode *root = mem_get_ast(mem, body_root);
        if (root->kind == AST_INTRINSIC &&
            delegate_is_meta_intrinsic((IntrinsicId)root->sub)) {
            ov->meta_tag = root->sub;
            return;
        }
        if (ov->param_count == 2 && root->kind == AST_BINARY &&
            root->left != MEM_NO_IDX && root->right != MEM_NO_IDX &&
            mem_get_ast(mem, root->left)->kind == AST_SLOT_LOAD && mem_get_ast(mem, root->left)->sub == 0 &&
            mem_get_ast(mem, root->right)->kind == AST_SLOT_LOAD && mem_get_ast(mem, root->right)->sub == 1) {
            uint16_t target_sym = root->ref;
            if (target_sym < tab->sym_count) {
                for (uint16_t i = tab->sym_first_ov[target_sym]; i != MEM_NO_IDX; i = tab->ov_next[i]) {
                    const OpOverload *target = &tab->overloads[i];
                    if (target != ov &&
                        target->param_count == 2 &&
                        target->param_types[0] == ov->param_types[0] &&
                        target->param_types[1] == ov->param_types[1] &&
                        target->param_units[0] == ov->param_units[0] &&
                        target->param_units[1] == ov->param_units[1] &&
                        target->ret_unit       == ov->ret_unit) {
                        ov->body_ast    = target->body_ast;
                        ov->local_count = target->local_count;
                        ov->meta_tag    = 1;
                        break;
                    }
                }
            }
        }
    }
}

static bool ext_body_starts_with_sym_intrinsic(ExtLoader *L) {
    ext_skip_ws(L);
    return (L->pos + 5 <= L->len && memcmp(L->src + L->pos, "@sym_", 5) == 0);
}

static void ext_parse_sym_decl(ExtLoader *L, uint16_t sym_id) {
    MemArena *mem = L->mem;
    SymTable *tab = L->tab;
    ext_skip_ws(L);

    /* 1. Syntax delimiter binding (`"(" => @lparen`, `"{" => @lbrace`, `"," => @comma`) */
    if (L->pos + 1 < L->len && L->src[L->pos] == '=' && L->src[L->pos + 1] == '>') {
        L->pos = (uint16_t)(L->pos + 2);
        if (!ext_match_char(L, '@')) {
            mem_err(mem, "Expected '@syntax_intrinsic' after '=>'");
            return;
        }
        StrSlice iname = ext_read_ident(L);
        IntrinsicId intr = sym_lookup_intrinsic(mem_str_ptr(mem, iname), iname.len);
        if (intr != INTR_LPAREN && intr != INTR_RPAREN &&
            intr != INTR_LBRACE && intr != INTR_RBRACE &&
            intr != INTR_LBRACK && intr != INTR_RBRACK &&
            intr != INTR_COMMA) {
            mem_err(mem, "Unknown syntax delimiter intrinsic '@%.*s'",
                     (int)iname.len, mem_str_ptr(mem, iname));
            return;
        }
        if (tab->overload_count >= SYM_MAX_OVERLOADS) {
            mem_err(mem, "Overload table overflow (max %d)", SYM_MAX_OVERLOADS);
            return;
        }
        uint16_t ov_idx = tab->overload_count++;
        OpOverload *ov  = &tab->overloads[ov_idx];
        memset(ov, 0, sizeof(*ov));
        ov->sym_id   = sym_id;
        ov->mod_id   = L->cur_mod;
        ov->fixity   = FIX_SYNTAX;
        ov->meta_tag = (uint8_t)intr;
        sym_link_overload(tab, ov_idx);
        return;
    }

    /* 2. Constant definition (`"g0" : real = 9.80665`, `g0 : real = 9.80665`, or `g0 = 9.80665`) */
    if (ext_match_char(L, ':') || ext_match_char(L, '=')) {
        bool already_matched_eq = (L->src[L->pos - 1] == '=');
        uint16_t rtid = mem->fast_real_type ? mem->fast_real_type : 1;
        uint8_t  ruid = SYM_UNIT_OMITTED;
        if (!already_matched_eq) {
            if (!ext_parse_return_spec(L, &rtid, &ruid)) return;
            if (rtid == SYM_TYPE_ANY) {
                rtid = mem->fast_real_type ? mem->fast_real_type : 1;
            }
            if (!ext_match_char(L, '=')) {
                mem_err(mem, "Expected '=' in constant definition");
                return;
            }
        }
        for (uint8_t i = 0; i < tab->const_count; ++i) {
            if (tab->consts[i].sym_id == sym_id && tab->consts[i].mod_id == L->cur_mod) {
                StrSlice s = tab->syms[sym_id];
                mem_err(mem, "Constant '%.*s' is already defined in module '[%.*s]'",
                        (int)s.len, mem_str_ptr(mem, s),
                        (int)sym_mod_name(tab, L->cur_mod).len,
                        mem_str_ptr(mem, sym_mod_name(tab, L->cur_mod)));
                return;
            }
        }
        if (tab->const_count >= SYM_MAX_CONSTS) {
            mem_err(mem, "Constant table overflow (max %d)", SYM_MAX_CONSTS);
            return;
        }
        ext_skip_ws(L);
        Parser P;
        parser_init(&P, mem, tab, L->src, L->len, L->pos, false, true);
        lex_next(&P.lex);
        uint16_t c_ast = parser_parse_expr(&P, 0);
        L->pos = (P.lex.cur.kind == TOK_EOF) ? P.lex.pos : P.lex.cur.off;
        if (mem->has_error || c_ast == MEM_NO_IDX) return;

        uint16_t c_pool_idx = MEM_NO_IDX;
        uint8_t  final_unit = (ruid != SYM_UNIT_OMITTED && ruid != SYM_UNIT_GENERIC && ruid != SYM_UNIT_LOCK_NONE)
                              ? ruid : MEM_UNIT_NONE;
        if (mem_get_ast(mem, c_ast)->kind == AST_LIT) {
            c_pool_idx = mem_get_ast(mem, c_ast)->ref;
        } else {
            Value ev = eval_ast(mem, tab, c_ast, 0, (uint8_t)rtid);
            if (mem->has_error) return;
            double d = val_get_f64(mem, ev);
            if (final_unit == MEM_UNIT_NONE && ruid == SYM_UNIT_OMITTED) {
                final_unit = val_get_unit(mem, ev);
            }
            c_pool_idx = mem_alloc_pool(mem, 1);
            if (c_pool_idx == MEM_NO_IDX || mem->has_error) return;
            mem_write_pool(mem, c_pool_idx, d);
        }
        if (c_pool_idx == MEM_NO_IDX) return;
        Value cval = val_make_f64_at_pool(mem, rtid, final_unit, c_pool_idx);
        uint8_t cid = tab->const_count++;
        tab->consts[cid].sym_id   = sym_id;
        tab->consts[cid].mod_id   = L->cur_mod;
        tab->consts[cid].type_id  = (uint8_t)rtid;
        tab->consts[cid].unit_id  = final_unit;
        tab->consts[cid].val_idx  = cid;
        tab->consts[cid].pool_idx = c_pool_idx;
        tab->const_vals[cid]      = cval;
        return;
    }

    /* 3. Function or Prefix Operator Declaration (`"fn"(...)` or `fn(...)`) */
    if (!ext_match_char(L, '(')) {
        mem_err(mem, "Expected '(', ':', '=', or '=>' after symbol in declaration");
        return;
    }
    StrSlice pnames[SYM_MAX_OV_PARAMS];
    uint16_t ptypes[SYM_MAX_OV_PARAMS];
    uint8_t  punits[SYM_MAX_OV_PARAMS];
    bool     has_def[SYM_MAX_OV_PARAMS];
    uint16_t def_start[SYM_MAX_OV_PARAMS];
    uint16_t def_end[SYM_MAX_OV_PARAMS];
    uint8_t  pcount    = 0;
    uint8_t  req_count = 0;
    bool     seen_def  = false;
    memset(has_def, 0, sizeof(has_def));

    while (L->pos < L->len && !mem->has_error) {
        if (ext_match_char(L, ')')) break;
        if (pcount >= SYM_MAX_OV_PARAMS) {
            mem_err(mem, "Max %d parameters supported", SYM_MAX_OV_PARAMS);
            break;
        }
        pnames[pcount] = ext_read_ident(L);
        if (sym_is_reserved_kw(tab, mem, mem_str_ptr(mem, pnames[pcount]), pnames[pcount].len)) {
            mem_err(mem, "Cannot use reserved keyword '%.*s' as a variable name",
                    (int)pnames[pcount].len, mem_str_ptr(mem, pnames[pcount]));
            break;
        }
        if (ext_match_char(L, ':')) {
            StrSlice tn = ext_read_ident(L);
            bool has_u  = false;
            StrSlice un = { 0, 0 };
            if (ext_match_char(L, ',')) {
                if (ext_peek_ident_is_unit_annot(L)) {
                    has_u = true;
                    un    = ext_read_ident(L);
                }
            }
            if (!ext_resolve_type_and_unit(L, tn, has_u, un, &ptypes[pcount], &punits[pcount])) {
                break;
            }
        } else {
            ptypes[pcount] = mem->fast_real_type ? mem->fast_real_type : 1;
            punits[pcount] = SYM_UNIT_OMITTED;
        }

        if (ext_match_char(L, '=')) {
            seen_def        = true;
            has_def[pcount] = true;
            ext_skip_default_expr(L, &def_start[pcount], &def_end[pcount]);
        } else {
            if (seen_def) {
                mem_err(mem, "Parameter without default value cannot follow a default parameter");
                break;
            }
            req_count++;
        }
        ext_match_char(L, ',');
        pcount++;
    }
    if (mem->has_error) return;

    ext_skip_ws(L);
    uint8_t fixity = FIX_FUNC;
    uint8_t prec   = 0;

    if (ext_match_char(L, ':')) {
        fixity = FIX_PREFIX;
        char *endp = NULL;
        prec = (uint8_t)strtol(L->src + L->pos, &endp, 10);
        L->pos = (uint16_t)(endp - L->src);
    }

    uint16_t rtid = 0;
    uint8_t  ruid = SYM_UNIT_OMITTED;
    if (!ext_parse_return_spec(L, &rtid, &ruid)) return;
    ext_match_char(L, '=');

    /* Detect lazy bound-variable parameter prefix (`var_x`, `var_t, var_y`, etc.) on `.math` functions */
    uint8_t lazy_mtag = 0;
    if (fixity == FIX_FUNC && pcount >= 2 && !ext_body_starts_with_sym_intrinsic(L)) {
        uint8_t v_cnt = 0;
        while (v_cnt < pcount && v_cnt < 4u &&
               pnames[v_cnt].len > 4u &&
               memcmp(mem_str_ptr(mem, pnames[v_cnt]), "var_", 4) == 0) {
            v_cnt++;
        }
        if (v_cnt >= 1u && v_cnt < pcount) {
            uint8_t e_cnt = (v_cnt <= 2u) ? 1u : (uint8_t)(v_cnt - 1u);
            if ((uint8_t)(v_cnt + e_cnt) > pcount) {
                e_cnt = (uint8_t)(pcount - v_cnt);
            }
            for (uint8_t e = 0; e < e_cnt; ++e) {
                if (mem->fn_type_id != 0) {
                    ptypes[v_cnt + e] = mem->fn_type_id;
                }
            }
            bool is_eq = (pnames[v_cnt].len == 2u &&
                          memcmp(mem_str_ptr(mem, pnames[v_cnt]), "eq", 2) == 0);
            lazy_mtag = (uint8_t)(SYM_META_LAZY_MASK |
                                  (is_eq ? SYM_META_LAZY_EQ_BIT : 0u) |
                                  ((v_cnt & 0x07u) << 3u) |
                                  (e_cnt & 0x07u));
        }
    }

    if (ext_has_duplicate_overload(tab, sym_id, L->cur_mod, fixity, pcount, ptypes, punits)) {
        StrSlice s = tab->syms[sym_id];
        mem_err(mem, "Duplicate declaration of '%.*s' in module '[%.*s]'",
                (int)s.len, mem_str_ptr(mem, s),
                (int)sym_mod_name(tab, L->cur_mod).len,
                mem_str_ptr(mem, sym_mod_name(tab, L->cur_mod)));
        return;
    }

    if (tab->overload_count >= SYM_MAX_OVERLOADS) {
        mem_err(mem, "Overload table overflow (max %d)", SYM_MAX_OVERLOADS);
        return;
    }
    uint16_t ov_idx = tab->overload_count++;
    OpOverload *ov  = &tab->overloads[ov_idx];
    memset(ov, 0, sizeof(*ov));
    ov->sym_id      = sym_id;
    ov->mod_id      = L->cur_mod;
    ov->fixity      = fixity;
    ov->prec        = prec;
    ov->param_count = pcount;
    ov->ret_type    = (uint8_t)rtid;
    ov->ret_unit    = ruid;
    for (uint8_t i = 0; i < pcount; ++i) {
        ov->param_types[i] = (uint8_t)ptypes[i];
        ov->param_units[i] = punits[i];
    }
    sym_link_overload(tab, ov_idx);

    ext_compile_body(L, ov, pnames);
    if (mem->has_error) return;
    if (lazy_mtag != 0 && ov->meta_tag == 0) {
        ov->meta_tag = lazy_mtag;
    }

    /* 4. Synthesize lower-arity overload wrappers for trailing default parameters */
    if (seen_def && fixity == FIX_FUNC && req_count < pcount) {
        hardcode_synthesize_default_overloads(mem, tab, L->src, sym_id, L->cur_mod,
                                              ov_idx, pcount, req_count,
                                              pnames, ptypes, punits,
                                              def_start, def_end, rtid, ruid);
    }
}

bool ext_compile_slice(MemArena *mem, SymTable *tab,
                       const char *slice_src, uint16_t slice_len) {
    ExtLoader L;
    memset(&L, 0, sizeof(L));
    L.mem = mem;
    L.tab = tab;
    L.src = slice_src;
    L.len = slice_len;
    L.pos = 0;

    /* Default any extension without an explicit `[module]` header to `[default]` */
    StrSlice def_mod = mem_intern_str(mem, "default", 7);
    if (mem->has_error) return false;
    L.cur_mod = sym_find_or_add_mod(tab, mem, def_mod);

    while (L.pos < L.len && !mem->has_error) {
        ext_skip_ws(&L);
        if (L.pos >= L.len) break;

        char c = L.src[L.pos];

        if (c == '[') {
            L.pos++;
            StrSlice mname = ext_read_ident(&L);
            if (!ext_match_char(&L, ']') || mname.len == 0) {
                mem_err(mem, "Expected closing ']' in module header");
                break;
            }
            L.cur_mod = sym_find_or_add_mod(tab, mem, mname);
            continue;
        }

        if (c == '<' && L.pos + 1 < L.len && L.src[L.pos + 1] == '[') {
            L.pos = (uint16_t)(L.pos + 2);
            StrSlice dep = ext_read_ident(&L);
            if (!ext_match_char(&L, ']') || dep.len == 0) {
                mem_err(mem, "Expected closing ']' in module dependency");
                break;
            }
            uint16_t dep_id = sym_find_or_add_mod(tab, mem, dep);
            if (mem->has_error) break;
            sym_mod_active_mut(tab)[dep_id] = 1u;
            continue;
        }

        if (c == '$') {
            ext_parse_type_decl(&L);
            continue;
        }

        if (c == '@') {
            L.pos++;
            StrSlice dkw = ext_read_ident(&L);
            if (!mem_slice_eq_cstr(mem, dkw, "dim") && !mem_slice_eq_cstr(mem, dkw, "unit_dim")) {
                mem_err(mem, "Unknown top-level directive '@%.*s' (expected '@dim')",
                        (int)dkw.len, mem_str_ptr(mem, dkw));
                break;
            }
            if (!ext_match_char(&L, '(')) {
                mem_err(mem, "Expected '(' after '@dim'");
                break;
            }
            StrSlice uname = ext_read_ident(&L);
            if (uname.len == 0) {
                mem_err(mem, "Expected unit group name in '@dim(...)'");
                break;
            }
            uint8_t uid = sym_find_or_add_unit(tab, mem, mem_str_ptr(mem, uname), uname.len);
            if (mem->has_error || uid == MEM_UNIT_NONE) break;
            UnitDimDef udim;
            memset(&udim, 0, sizeof(udim));
            udim.has_dim = 1;
            int idx = 0;
            while (L.pos < L.len && !mem->has_error && idx < 7) {
                if (!ext_match_char(&L, ',')) break;
                ext_skip_ws(&L);
                char *endp = NULL;
                long val = strtol(L.src + L.pos, &endp, 10);
                if (endp == L.src + L.pos) {
                    mem_err(mem, "Expected integer exponent in '@dim(...)'");
                    break;
                }
                L.pos = (uint16_t)(endp - L.src);
                udim.exp[idx++] = (int8_t)val;
            }
            if (mem->has_error) break;
            if (!ext_match_char(&L, ')')) {
                mem_err(mem, "Expected ')' at end of '@dim(...)'");
                break;
            }
            tab->unit_dims[uid] = udim;
            continue;
        }

        if (c == '"') {
            StrSlice sym = ext_read_quoted(&L);
            if (mem->has_error) break;
            uint16_t sym_id = sym_intern(tab, mem, sym);
            if (mem->has_error) break;
            ext_parse_sym_decl(&L, sym_id);
            continue;
        }

        if (lex_is_ident_start((unsigned char)c)) {
            StrSlice sym = ext_read_ident(&L);
            if (mem->has_error || sym.len == 0) break;
            uint16_t sym_id = sym_intern(tab, mem, sym);
            if (mem->has_error) break;
            ext_parse_sym_decl(&L, sym_id);
            continue;
        }

        if (c == '(') {
            StrSlice pnames[SYM_MAX_OV_PARAMS];
            uint16_t ptypes[SYM_MAX_OV_PARAMS];
            uint8_t  punits[SYM_MAX_OV_PARAMS];
            if (!ext_parse_one_param(&L, &pnames[0], &ptypes[0], &punits[0])) break;

            ext_skip_ws(&L);
            StrSlice sym = (L.pos < L.len && L.src[L.pos] == '"')
                           ? ext_read_quoted(&L)
                           : ext_read_ident(&L);
            if (mem->has_error || sym.len == 0) break;
            uint16_t sym_id = sym_intern(tab, mem, sym);
            if (mem->has_error) break;
            ext_skip_ws(&L);

            uint8_t pcount = 1;
            uint8_t fixity = FIX_POSTFIX;
            uint8_t prec   = 0;

            if (L.pos < L.len && L.src[L.pos] == '(') {
                if (!ext_parse_one_param(&L, &pnames[1], &ptypes[1], &punits[1])) break;
                pcount = 2;
            }

            if (!ext_match_char(&L, ':')) {
                mem_err(mem, "Expected ':prec', ':<prec', or ':>prec' in operator declaration");
                break;
            }
            if (pcount == 2) {
                if (ext_match_char(&L, '<')) fixity = FIX_INFIX_LEFT;
                else if (ext_match_char(&L, '>')) fixity = FIX_INFIX_RIGHT;
                else fixity = FIX_INFIX_LEFT;
            } else {
                fixity = FIX_POSTFIX;
            }

            char *endp = NULL;
            prec = (uint8_t)strtol(L.src + L.pos, &endp, 10);
            L.pos = (uint16_t)(endp - L.src);

            uint16_t rtid = 0;
            uint8_t  ruid = SYM_UNIT_OMITTED;
            if (!ext_parse_return_spec(&L, &rtid, &ruid)) break;
            ext_match_char(&L, '=');

            if (ext_has_duplicate_overload(tab, sym_id, L.cur_mod, fixity, pcount, ptypes, punits)) {
                StrSlice s = tab->syms[sym_id];
                mem_err(mem, "Duplicate declaration of '%.*s' in module '[%.*s]'",
                        (int)s.len, mem_str_ptr(mem, s),
                        (int)sym_mod_name(tab, L.cur_mod).len,
                        mem_str_ptr(mem, sym_mod_name(tab, L.cur_mod)));
                break;
            }

            if (tab->overload_count >= SYM_MAX_OVERLOADS) {
                mem_err(mem, "Overload table overflow (max %d)", SYM_MAX_OVERLOADS);
                break;
            }
            uint16_t ov_idx = tab->overload_count++;
            OpOverload *ov  = &tab->overloads[ov_idx];
            memset(ov, 0, sizeof(*ov));
            ov->sym_id         = sym_id;
            ov->mod_id         = L.cur_mod;
            ov->fixity         = fixity;
            ov->prec           = prec;
            ov->param_count    = pcount;
            ov->ret_type       = (uint8_t)rtid;
            ov->ret_unit       = ruid;
            ov->param_types[0] = (uint8_t)ptypes[0];
            ov->param_units[0] = punits[0];
            if (pcount == 2) {
                ov->param_types[1] = (uint8_t)ptypes[1];
                ov->param_units[1] = punits[1];
            }
            sym_link_overload(tab, ov_idx);

            ext_compile_body(&L, ov, pnames);
            continue;
        }

        mem_err(mem, "Unrecognized declaration in extension near '%.*s'",
                (int)(L.len - L.pos < 16 ? L.len - L.pos : 16), L.src + L.pos);
        break;
    }

    return !mem->has_error;
}

/* ============================================================================
 * MODULE UNIT STAGING (`ext_build_init`, `ext_build_create`, `ext_compile_unit`)
 * ============================================================================ */

void ext_build_free_heap(ExtBuildCtx *ctx) {
    if (!ctx) return;
    if (ctx->heap_magic == EXT_BUILD_HEAP_MAGIC) {
        ExtCompiledUnit *units = ext_ctx_units_mut(ctx);
        for (uint16_t i = 0; i < ctx->unit_count; ++i) {
            if (units[i].heap_deps != NULL) {
                free(units[i].heap_deps);
                units[i].heap_deps = NULL;
            }
        }
        if (ctx->heap_units != NULL) {
            free(ctx->heap_units);
            ctx->heap_units = NULL;
        }
    }
    ctx->heap_units = NULL;
    ctx->heap_magic = 0;
    ctx->unit_cap   = EXT_SBO_BUILD_UNITS;
}

void ext_build_init(ExtBuildCtx *ctx) {
    if (!ctx) return;
    ext_build_free_heap(ctx);
    ctx->heap_magic = EXT_BUILD_HEAP_MAGIC;
    ctx->unit_cap   = EXT_SBO_BUILD_UNITS;
    ctx->unit_count = 0;
    ctx->pool_used  = 0;
    ctx->has_error  = false;
    ctx->err_msg[0] = '\0';
}

ExtBuildCtx *ext_build_create(void) {
    ExtBuildCtx *ctx = (ExtBuildCtx *)malloc(sizeof(ExtBuildCtx));
    if (ctx) {
        memset(ctx, 0, sizeof(ExtBuildCtx));
        ext_build_init(ctx);
    }
    return ctx;
}

void ext_build_destroy(ExtBuildCtx *ctx) {
    if (!ctx) return;
    ext_build_free_heap(ctx);
    free(ctx);
}

static bool build_fail(MemArena *mem, ExtBuildCtx *ctx, const char *msg) {
    if (mem) mem_err(mem, "%s", msg);
    if (ctx && !ctx->has_error) {
        ctx->has_error = true;
        snprintf(ctx->err_msg, sizeof(ctx->err_msg), "%s", mem ? mem->err_msg : msg);
    }
    return false;
}

static int ext_alloc_unit(MemArena *mem, ExtBuildCtx *ctx) {
    if (ctx->heap_magic != EXT_BUILD_HEAP_MAGIC || ctx->unit_cap == 0) {
        ctx->heap_magic = EXT_BUILD_HEAP_MAGIC;
        ctx->heap_units = NULL;
        ctx->unit_cap   = EXT_SBO_BUILD_UNITS;
    }
    if (ctx->unit_count >= 0xFFFEu) {
        build_fail(mem, ctx, "Compilation unit index overflow");
        return -1;
    }
    if (ctx->unit_count >= ctx->unit_cap) {
        uint32_t new_cap = (uint32_t)ctx->unit_cap * 2u;
        if (new_cap > 0xFFFEu) new_cap = 0xFFFEu;
        ExtCompiledUnit *new_units = (ExtCompiledUnit *)calloc(new_cap, sizeof(ExtCompiledUnit));
        if (!new_units) {
            build_fail(mem, ctx, "Out of memory expanding compilation unit table");
            return -1;
        }
        memcpy(new_units, ext_ctx_units(ctx), (size_t)ctx->unit_count * sizeof(ExtCompiledUnit));
        if (ctx->heap_units != NULL) {
            free(ctx->heap_units);
        }
        ctx->heap_units = new_units;
        ctx->unit_cap   = (uint16_t)new_cap;
    }
    int idx = (int)ctx->unit_count++;
    ExtCompiledUnit *u = &ext_ctx_units_mut(ctx)[idx];
    memset(u, 0, sizeof(*u));
    u->dep_cap = EXT_SBO_UNIT_DEPS;
    return idx;
}

static bool ext_add_unit_dep(MemArena *mem, ExtBuildCtx *ctx, ExtCompiledUnit *u,
                             const char *dname, uint16_t id_len) {
    const char (*deps)[32] = ext_unit_deps(u);
    for (uint16_t d = 0; d < u->dep_count; ++d) {
        if (strcmp(deps[d], dname) == 0) {
            return true;
        }
    }
    if (u->dep_cap == 0) u->dep_cap = EXT_SBO_UNIT_DEPS;
    if (u->dep_count >= 0xFFFEu) {
        return build_fail(mem, ctx, "Module dependency index overflow");
    }
    if (u->dep_count >= u->dep_cap) {
        uint32_t new_cap = (uint32_t)u->dep_cap * 2u;
        if (new_cap > 0xFFFEu) new_cap = 0xFFFEu;
        char (*new_deps)[32] = (char (*)[32])calloc(new_cap, sizeof(*new_deps));
        if (!new_deps) {
            return build_fail(mem, ctx, "Out of memory expanding module dependencies");
        }
        memcpy(new_deps, ext_unit_deps(u), (size_t)u->dep_count * sizeof(*new_deps));
        if (u->heap_deps != NULL) {
            free(u->heap_deps);
        }
        u->heap_deps = new_deps;
        u->dep_cap   = (uint16_t)new_cap;
    }
    memcpy(ext_unit_deps_mut(u)[u->dep_count++], dname, (size_t)id_len + 1u);
    return true;
}

bool ext_compile_unit(MemArena *mem, const SymTable *tab,
                      ExtBuildCtx *ctx, const char *raw_math_text) {
    if (!mem || !tab || !ctx) return false;
    mem_clear_err(mem);
    if (ctx->has_error) {
        mem_err(mem, "%s", ctx->err_msg);
        return false;
    }
    if (!raw_math_text) {
        return build_fail(mem, ctx, "Null extension source string");
    }
    size_t raw_len = strlen(raw_math_text);
    if (raw_len > 65534u) {
        return build_fail(mem, ctx, "Extension source length exceeds maximum (65534 bytes)");
    }
    if (ctx->pool_used + (uint32_t)raw_len + 1u > EXT_STAGE_POOL_CAP) {
        return build_fail(mem, ctx, "Extension staging pool overflow");
    }

    uint32_t base_off = ctx->pool_used;
    memcpy(ctx->src_pool + base_off, raw_math_text, raw_len + 1u);
    ctx->pool_used += (uint32_t)raw_len + 1u;

    const char *src = ctx->src_pool + base_off;
    uint16_t    len = (uint16_t)raw_len;
    uint16_t    pos = 0;

    int  cur_u          = -1;
    int  depth          = 0;
    bool in_pattern     = false;
    bool at_stmt_start  = true;

    const StrSlice *loaded_mods = sym_mod_names(tab);

    while (pos < len) {
        /* 1. Skip horizontal whitespace */
        while (pos < len && (src[pos] == ' ' || src[pos] == '\t' || src[pos] == '\r')) {
            pos++;
        }
        if (pos >= len) break;

        /* 2. Newline resets top-level statement boundary */
        if (src[pos] == '\n') {
            pos++;
            if (depth == 0 && !in_pattern) {
                at_stmt_start = true;
            }
            continue;
        }

        /* 3. Line comment */
        if (src[pos] == '#') {
            while (pos < len && src[pos] != '\n') pos++;
            continue;
        }

        /* 4. Top-level `[module]` header or `<[dep]` dependency */
        if (at_stmt_start && depth == 0 && !in_pattern) {
            if (src[pos] == '[') {
                uint16_t hdr_start = pos;
                pos++;
                while (pos < len && (src[pos] == ' ' || src[pos] == '\t')) pos++;
                uint16_t id_start = pos;
                while (pos < len && lex_is_ident_cont((unsigned char)src[pos])) pos++;
                uint16_t id_len = (uint16_t)(pos - id_start);
                while (pos < len && (src[pos] == ' ' || src[pos] == '\t')) pos++;

                if (id_len == 0 || id_len >= 32u || pos >= len || src[pos] != ']') {
                    return build_fail(mem, ctx, "Expected closing ']' in module header");
                }
                pos++; /* consume ']' */

                char mname[32];
                memcpy(mname, src + id_start, id_len);
                mname[id_len] = '\0';
                bool is_def = (strcmp(mname, "default") == 0);

                if (!is_def) {
                    /* Reject if module is already loaded in SymTable */
                    for (uint16_t i = 0; i < tab->mod_count; ++i) {
                        if (mem_slice_eq_cstr(mem, loaded_mods[i], mname)) {
                            mem_err(mem, "Module '[%s]' is already loaded", mname);
                            ctx->has_error = true;
                            snprintf(ctx->err_msg, sizeof(ctx->err_msg), "%s", mem->err_msg);
                            return false;
                        }
                    }
                    /* Reject if module is already staged in ExtBuildCtx */
                    const ExtCompiledUnit *staged = ext_ctx_units(ctx);
                    for (uint16_t i = 0; i < ctx->unit_count; ++i) {
                        if (!staged[i].is_default_mod &&
                            strcmp(staged[i].mod_name, mname) == 0) {
                            mem_err(mem, "Module '[%s]' is already loaded", mname);
                            ctx->has_error = true;
                            snprintf(ctx->err_msg, sizeof(ctx->err_msg), "%s", mem->err_msg);
                            return false;
                        }
                    }
                }

                if (cur_u >= 0) {
                    ExtCompiledUnit *prev = &ext_ctx_units_mut(ctx)[cur_u];
                    prev->src_len = (uint16_t)((base_off + hdr_start) - prev->src_off);
                }
                cur_u = ext_alloc_unit(mem, ctx);
                if (cur_u < 0) return false;
                ExtCompiledUnit *u = &ext_ctx_units_mut(ctx)[cur_u];
                memcpy(u->mod_name, mname, (size_t)id_len + 1u);
                u->is_default_mod = is_def;
                u->src_off        = base_off + hdr_start;
                u->src_len        = 0;
                at_stmt_start     = true;
                continue;
            }

            if (src[pos] == '<' && pos + 1 < len && src[pos + 1] == '[') {
                uint16_t dep_stmt_start = pos;
                if (cur_u < 0) {
                    cur_u = ext_alloc_unit(mem, ctx);
                    if (cur_u < 0) return false;
                    ExtCompiledUnit *u = &ext_ctx_units_mut(ctx)[cur_u];
                    memcpy(u->mod_name, "default", 8);
                    u->is_default_mod = true;
                    u->src_off        = base_off + dep_stmt_start;
                    u->src_len        = 0;
                }
                pos = (uint16_t)(pos + 2);
                while (pos < len && (src[pos] == ' ' || src[pos] == '\t')) pos++;
                uint16_t id_start = pos;
                while (pos < len && lex_is_ident_cont((unsigned char)src[pos])) pos++;
                uint16_t id_len = (uint16_t)(pos - id_start);
                while (pos < len && (src[pos] == ' ' || src[pos] == '\t')) pos++;

                if (id_len == 0 || id_len >= 32u || pos >= len || src[pos] != ']') {
                    return build_fail(mem, ctx, "Expected closing ']' in module dependency");
                }
                pos++; /* consume ']' */

                char dname[32];
                memcpy(dname, src + id_start, id_len);
                dname[id_len] = '\0';

                ExtCompiledUnit *u = &ext_ctx_units_mut(ctx)[cur_u];
                if (!ext_add_unit_dep(mem, ctx, u, dname, id_len)) {
                    return false;
                }
                at_stmt_start = true;
                continue;
            }
        }

        /* 5. Any declaration before an explicit `[module]` header creates an implicit `[default]` unit */
        if (cur_u < 0) {
            cur_u = ext_alloc_unit(mem, ctx);
            if (cur_u < 0) return false;
            ExtCompiledUnit *u = &ext_ctx_units_mut(ctx)[cur_u];
            memcpy(u->mod_name, "default", 8);
            u->is_default_mod = true;
            u->src_off        = base_off + pos;
            u->src_len        = 0;
        }

        /* 6. Advance over string literals, consuming patterns, and bracketed scopes */
        if (src[pos] == '"') {
            pos++;
            while (pos < len && src[pos] != '"') {
                if (src[pos] == '\\' && pos + 1 < len) pos = (uint16_t)(pos + 2);
                else pos++;
            }
            if (pos >= len || src[pos] != '"') {
                return build_fail(mem, ctx, "Unterminated quoted string symbol in declaration");
            }
            pos++;
            at_stmt_start = false;
        } else if (in_pattern) {
            if (src[pos] == '[') {
                pos = (uint16_t)(pos + 1);
                while (pos < len && src[pos] != ']') {
                    if (src[pos] == '\\' && pos + 1 < len) pos = (uint16_t)(pos + 2);
                    else pos++;
                }
                if (pos < len) pos++;
            } else if (src[pos] == '=' && pos + 1 < len && src[pos + 1] == '>') {
                pos = (uint16_t)(pos + 2);
                in_pattern = false;
            } else {
                pos++;
            }
        } else {
            if (src[pos] == '~' && depth == 0) {
                in_pattern    = true;
                at_stmt_start = false;
                pos++;
            } else if (src[pos] == '(' || src[pos] == '{' || src[pos] == '[') {
                depth++;
                at_stmt_start = false;
                pos++;
            } else if (src[pos] == ')' || src[pos] == '}' || src[pos] == ']') {
                if (depth > 0) depth--;
                at_stmt_start = false;
                pos++;
            } else if (src[pos] == ';' && depth == 0) {
                at_stmt_start = true;
                pos++;
            } else {
                at_stmt_start = false;
                pos++;
            }
        }
    }

    if (cur_u >= 0) {
        ExtCompiledUnit *last = &ext_ctx_units_mut(ctx)[cur_u];
        last->src_len = (uint16_t)((base_off + len) - last->src_off);
    }
    return true;
}
