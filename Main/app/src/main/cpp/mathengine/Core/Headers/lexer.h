/*
 * lexer.h — Standalone UTF-8 & Dynamic Pattern-Driven Lexer Module
 *
 * Purpose:
 *   Tokenizes input UTF-8 streams using ONLY active consuming `$type` patterns,
 *   active `[boot_syntax]` delimiters (`@lparen`, `@rparen`, `@lbrace`, `@rbrace`,
 *   `@lbrack`, `@rbrack`, `@comma`), and active operator symbols registered in
 *   `SymTable`. Tracks whether a token is preceded by a line break (`had_newline`)
 *   so `\n` acts as a default `;` statement separator while keeping `;`
 *   compulsory for multiple statements on a single line.
 */

#ifndef LEXER_H
#define LEXER_H

#include "mem_arena.h"
#include "sym_table.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    TOK_EOF = 0,
    TOK_VAL,        /* Matched by an active consuming `$type`                        */
    TOK_OP,         /* Matched by an active operator (`.`, `;`, `:=`, `=`, `??`, `+`) */
    TOK_IDENT,      /* Identifier (function, constant, local variable, struct field) */
    TOK_LPAREN,     /* Matched by an active `@lparen` syntax symbol in SymTable      */
    TOK_RPAREN,     /* Matched by an active `@rparen` syntax symbol in SymTable      */
    TOK_LBRACE,     /* Matched by an active `@lbrace` syntax symbol in SymTable      */
    TOK_RBRACE,     /* Matched by an active `@rbrace` syntax symbol in SymTable      */
    TOK_LBRACK,     /* Matched by an active `@lbrack` syntax symbol `[` in SymTable  */
    TOK_RBRACK,     /* Matched by an active `@rbrack` syntax symbol `]` in SymTable  */
    TOK_COMMA,      /* Matched by an active `@comma`  syntax symbol in SymTable      */
    TOK_AT          /* @ (private C intrinsic/extern hook inside extension bodies)   */
} TokKind;

typedef struct {
    TokKind  kind;
    bool     had_newline;
    uint16_t off;
    uint16_t len;
    uint16_t sym_id;
    Value    val;
} Token;

typedef struct {
    MemArena   *mem;
    SymTable   *tab;
    const char *src;
    uint16_t    len;
    uint16_t    pos;
    uint8_t     paren_depth;
    bool        stop_at_newline;
    bool        last_was_expr;
    Token       cur;
} Lexer;

void lex_init(Lexer *lex, MemArena *mem, SymTable *tab,
              const char *src, uint16_t len, uint16_t start_pos, bool stop_at_newline);

void lex_next(Lexer *lex);

bool lex_is_ident_start(unsigned char c);
bool lex_is_ident_cont(unsigned char c);

#ifdef __cplusplus
}
#endif

#endif /* LEXER_H */