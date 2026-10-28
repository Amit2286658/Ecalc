/*
 * ext_loader.h — Umbrella Header for Compilation, Linking & `.mathc` Serialization
 *
 * Includes the three modular components in `Core/compile/`:
 *   - `compiler.h`  : `.math` unit staging (`ext_compile_unit`) & slice compiler
 *   - `linker.h`    : Topological `<[dep]` dependency resolution (`ext_link_units`)
 *   - `mathc_bin.h` : `.mathc` binary image header, serializer & O(1) loader
 */

#ifndef EXT_LOADER_H
#define EXT_LOADER_H

#include "compiler.h"
#include "linker.h"
#include "mathc_bin.h"

#endif /* EXT_LOADER_H */
