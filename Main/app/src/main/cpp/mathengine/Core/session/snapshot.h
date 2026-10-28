/*
 * snapshot.h — Order-Tagged Raw-Text Session Snapshots & Continuation (`Core/session/`)
 *
 * Purpose:
 *   Provides canonical MEP value expression formatting, order-tagged raw-text
 *   snapshot construction (`MSP1|seq:<n>|prev:<hex>|hash:<hex>|...`), iterative
 *   snapshot retrieval (`eng_session_snapshot_next`), and full-history session
 *   restoration (`eng_session_restore`).
 */

#ifndef SNAPSHOT_H
#define SNAPSHOT_H

#include "session.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ============================================================================
 * 1. PUBLIC ITERATIVE SNAPSHOT RETRIEVAL & SESSION RESTORATION API
 * ============================================================================ */

/* Return the number of order-tagged evaluation snapshots in `session_id` */
MATH_ENGINE_API uint16_t    eng_session_snapshot_count(const Engine *eng, int session_id);

/* Rewind the iterative snapshot cursor for `session_id` back to evaluation #1 */
MATH_ENGINE_API bool        eng_session_snapshot_rewind(Engine *eng, int session_id);

/*
 * Iteratively return each evaluation's order-tagged snapshot as raw text.
 * Advances the session's internal cursor on each call; when all snapshots have
 * been yielded, resets the cursor to 0 and returns NULL.
 */
MATH_ENGINE_API const char *eng_session_snapshot_next(Engine *eng, int session_id);

/* Random-access helper: return the raw-text snapshot at 0-based `idx` (`0 .. count - 1`) */
MATH_ENGINE_API const char *eng_session_snapshot_at(const Engine *eng, int session_id, uint16_t idx);

/*
 * Reconstruct and continue a session from all prior evaluation snapshots:
 *   - Uses the order tags (`seq:1 .. snapshot_count`) and 32-bit FNV-1a integrity
 *     hash chain (`prev`/`hash`) inside the raw-text snapshots (even if passed
 *     out of order) to verify that full, contiguous, uncorrupted session history
 *     is present.
 *   - Allocates a new in-memory session populated with the full history and variables
 *     so the host can continue evaluating expressions from there on.
 *   - Returns the new `session_id >= 0` on success, or `-1` if snapshots are missing,
 *     out of sequence, from mismatched sessions, or corrupted.
 */
MATH_ENGINE_API int         eng_session_restore(Engine *eng, const char *const *snapshots, size_t snapshot_count);

/* ============================================================================
 * 2. INTERNAL SESSION <-> SNAPSHOT HELPERS (USED BY `Core/session/session.c`)
 * ============================================================================ */

/* Format a runtime `Value` as a canonical, lossless MEP expression string */
bool eng_snapshot_format_canonical_val(const Engine *eng, Value val, char *buf, size_t cap);

/* Record a new order-tagged raw-text snapshot into `S` after a successful session eval */
bool eng_snapshot_record_eval(const Engine *eng, EngSession *S, Value res);

#ifdef __cplusplus
}
#endif

#endif /* SNAPSHOT_H */
