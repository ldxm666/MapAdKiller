// libamapbldpatch.so — remove AMap's building geometry by neutralising the layer's
// per-frame processing slot. 高德地图 17.02.0.2010 (versionCode 170200), libamapr.so, arm64.
//
// ── why this is a native patch and not a Java hook ────────────────────────────────
// The handover that this was ported from (AmapTune, 17.00.0.2005) spent four rounds
// proving the Java layer has no say here:
//   * every `setProcessBuilding` / `setBuildTextureVisible` setter on VMapSceneWrapper and
//     AMapController is dead code during navigation (spy over 437 setters: zero calls, and a
//     native hook on the Libamapdsl setter core also recorded zero);
//   * forcing `setBuildTextureVisible(false)` only flat-shades the blocks — the geometry
//     stays, because the scene's layer set is fixed by its BundleToken at construction.
// So the only lever is the layer object itself, inside libamapr.so.
//
// ── the object graph, re-derived for 17.02.0.2010 ─────────────────────────────────
// (elf_scan.py xref "BuildingStyleLayer" → factory → constructor → vtable → slot)
//
//   factory      0x18a7c6c   strcmp "BuildingStyleLayer"      → new, type 0x4B
//                            strcmp "BuildingStyleLayerDepth" → new, type 0x4C
//   ctor         0x18b1684   base ctor 0x17286d4, then `str x8,[x19]` with vtable 0x1f40848
//   vtable       0x1f40848   .data.rel.ro, 14 slots
//   slot [4]     0x18b17e0   the per-frame data → geometry step   ← patched
//   patch VA     0x1f40868   = vtable + 4*8
//   gadget       0xe84148    `mov x0,xzr; ret`
//
// Slot [4] in 17.00 was replaced with a BARE `ret`. That happened to work, but the function
// is not void — it ends in `and w0,w8,#1`, i.e. it returns a bool. A bare `ret` hands the
// caller whatever w0 happened to hold. Here the slot points at `mov x0,xzr; ret` instead, so
// the answer is a deterministic "this layer produced nothing".
//
// ── the roads NOT taken (all measured, do not retry) ─────────────────────────────
//   1. make the factory return null for a building type  → host dies "fg TOP", no tombstone
//   2. vtable [8] (0x1728580, `mov w0,#1; ret`, looks like isVisible) → picture unchanged
//   3. BuildingTileLayoutNode [4]                        → picture unchanged
//   4. VectorStyleLayer [8]                              → picture unchanged
//
// ── safety ───────────────────────────────────────────────────────────────────────
// Nothing is written unless BOTH checks pass: the slot must currently hold
// base+0x18b17e0, and the four instructions at that address must be the expected prologue.
// On any mismatch the thread logs the actual values and exits without touching memory, so a
// future 高德 build can never be crashed by this file — it just stops working.
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <link.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "MapAmapBld"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define TARGET_LIB      "libamapr.so"

/* ── one table, two builds ────────────────────────────────────────────────────────
 * 国内版 and Play 版 ship *different* libamapr.so files (32,983,424 vs 32,984,120 bytes,
 * different sha256), so every address differs. Both builds share the same object graph —
 * 14-slot vtable, patch slot [4], type constants 0x4B/0x4C, identical prologue and gadget
 * words — so only three numbers move per build. The prologue/gadget arrays below are shared
 * on purpose: they were verified byte-identical in both.
 *
 * Selection is by self-check, never by a version string: libamapr.so carries no version we
 * can read, and a wrong guess must fail closed. For each candidate we require
 *   [A] the vtable slot currently holds base + fn_process   (relocation applied)
 *   [B] the four instructions there are the known prologue
 *   [C] the gadget site holds `mov x0, xzr ; ret`
 * Only then do we write. A build that matches nothing is left completely untouched, which
 * is what a future AMap release will look like until its numbers are re-derived.
 *
 * Adding a build = adding one row. Do not reorder: the first fully-matching row wins, and
 * putting a looser row first could shadow a stricter one.
 */
typedef struct {
    const char *tag;        /* log only */
    uintptr_t   vt_slot;    /* &BuildingStyleLayer_vtable[4] */
    uintptr_t   fn_process; /* what that slot must currently hold */
    uintptr_t   gadget;     /* mov x0, xzr ; ret */
} patch_variant;

static const patch_variant VARIANTS[] = {
    /* 国内版 17.02.0.2010 (versionCode 170200), libamapr.so ef40b30a370ce3d6…
     *   factory 0x18a7c6c  ctor 0x18b1684  vtable 0x1f40848  slot[4] 0x18b17e0
     *   gadget 0xe84148 */
    { "17.02.0", 0x1f40868UL, 0x18b17e0UL, 0xe84148UL },

    /* Play 版 17.02.2.2014 (versionCode 170105), libamapr.so 70ffc7c0d75ba087…
     *   factory 0x18a7f00  ctor 0x18b1918  vtable 0x1f40ae8  slot[4] 0x18b1a74
     *   gadget 0xe842c8
     * Note the old gadget 0xe84148 is NOT valid here — in this build it decodes as
     * `ret; stp …`, i.e. the middle of some other function. Reusing it would jump into
     * nowhere. That is exactly why selection is by self-check and not by version. */
    { "17.02.2", 0x1f40b08UL, 0x18b1a74UL, 0xe842c8UL },
};

#define VARIANT_COUNT ((int) (sizeof(VARIANTS) / sizeof(VARIANTS[0])))

/* First four instructions of FN_PROCESS: stp x29,x30,[sp,#-0x30]! / str x21,[sp,#0x10] /
 * stp x20,x19,[sp,#0x20] / mov x29,sp. Verified identical in both builds. */
static const uint32_t FN_PROCESS_PROLOGUE[4] = {
    0xa9bd7bfdU, 0xf9000bf5U, 0xa9024ff4U, 0x910003fdU
};
/* mov x0, xzr ; ret */
static const uint32_t GADGET_WORDS[2] = { 0xaa1f03e0U, 0xd65f03c0U };

/* Module address range, so a candidate row from a *different* build cannot make us read
 * outside the mapping. Both tables live inside the same .so, but a future row could be
 * mis-typed, and dereferencing a stray address here would SIGSEGV the host on startup —
 * far worse than not patching. */
typedef struct {
    uintptr_t base;
    uintptr_t vaddr_lo;   /* min PT_LOAD p_vaddr */
    uintptr_t vaddr_hi;   /* max PT_LOAD p_vaddr + p_memsz */
    int       found;
} lib_info;

static int on_phdr(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    lib_info *out = (lib_info *) data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, TARGET_LIB)) return 0;

    uintptr_t lo = UINTPTR_MAX, hi = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t s = (uintptr_t) ph->p_vaddr;
        uintptr_t e = s + (uintptr_t) ph->p_memsz;
        if (s < lo) lo = s;
        if (e > hi) hi = e;
    }
    if (hi <= lo) return 0;   /* no PT_LOAD: refuse rather than guess */

    out->base     = (uintptr_t) info->dlpi_addr;
    out->vaddr_lo = lo;
    out->vaddr_hi = hi;
    out->found    = 1;
    return 1;
}

static int find_lib(lib_info *out) {
    memset(out, 0, sizeof(*out));
    dl_iterate_phdr(on_phdr, out);
    return out->found;
}

static int words_match(uintptr_t address, const uint32_t *expected, int count) {
    const volatile uint32_t *at = (const volatile uint32_t *) address;
    for (int i = 0; i < count; i++) {
        if (at[i] != expected[i]) return 0;
    }
    return 1;
}

/* True when [va, va+len) lies inside the module's loaded range. */
static int in_range(const lib_info *li, uintptr_t va, size_t len) {
    return va >= li->vaddr_lo && va + len <= li->vaddr_hi;
}

static void *patch_thread(void *arg) {
    (void) arg;

    /* libamapr.so is loaded lazily by the map engine, well after our constructor runs. */
    lib_info li;
    for (int i = 0; i < 1200; i++) {   /* ~60 s at 50 ms */
        if (find_lib(&li)) break;
        usleep(50000);
    }
    if (!li.found) {
        LOGI("event=anchor_skip reason=lib_absent lib=%s", TARGET_LIB);
        return NULL;
    }
    uintptr_t base = li.base;

    /* Already patched? Detect before selection, because a patched slot no longer holds
     * fn_process and would therefore fail every row's check [A]. */
    for (int i = 0; i < VARIANT_COUNT; i++) {
        const patch_variant *v = &VARIANTS[i];
        if (!in_range(&li, v->vt_slot, sizeof(uintptr_t))) continue;
        uintptr_t slot = base + v->vt_slot;
        uintptr_t gadget = base + v->gadget;
        if (*(volatile uintptr_t *) slot != gadget) continue;
        if (!in_range(&li, v->gadget, sizeof(GADGET_WORDS))) continue;
        if (!words_match(gadget, GADGET_WORDS, 2)) continue;
        LOGI("event=already_patched variant=%s slot=%p base=%p",
             v->tag, (void *) slot, (void *) base);
        return NULL;
    }

    /* Select the row whose three self-checks all pass. */
    const patch_variant *pick = NULL;
    for (int i = 0; i < VARIANT_COUNT; i++) {
        const patch_variant *v = &VARIANTS[i];
        if (!in_range(&li, v->vt_slot, sizeof(uintptr_t))
            || !in_range(&li, v->fn_process, sizeof(FN_PROCESS_PROLOGUE))
            || !in_range(&li, v->gadget, sizeof(GADGET_WORDS))) {
            continue;
        }
        uintptr_t slot = base + v->vt_slot;
        uintptr_t fn   = base + v->fn_process;
        uintptr_t gd   = base + v->gadget;

        /* [A] the slot still points at the function we re-derived for this build */
        if (*(volatile uintptr_t *) slot != fn) continue;
        /* [B] the code behind it is that function */
        if (!words_match(fn, FN_PROCESS_PROLOGUE, 4)) continue;
        /* [C] the gadget site really is `mov x0, xzr ; ret` */
        if (!words_match(gd, GADGET_WORDS, 2)) continue;

        pick = v;
        break;
    }

    if (!pick) {
        /* Report what the *first* row saw, so a log from an unknown build still says
         * something actionable. Never write. */
        const patch_variant *v = &VARIANTS[0];
        uintptr_t slot = base + v->vt_slot;
        uintptr_t current = in_range(&li, v->vt_slot, sizeof(uintptr_t))
                            ? *(volatile uintptr_t *) slot : 0;
        LOGW("event=anchor_mismatch reason=no_variant_matched variants=%d "
             "first=%s slot=%p want=%p got=%p base=%p",
             VARIANT_COUNT, v->tag, (void *) slot, (void *) (base + v->fn_process),
             (void *) current, (void *) base);
        return NULL;
    }

    uintptr_t slot     = base + pick->vt_slot;
    uintptr_t function = base + pick->fn_process;
    uintptr_t gadget   = base + pick->gadget;
    uintptr_t current  = *(volatile uintptr_t *) slot;

    /* .data.rel.ro is read-only after relocation. Page size must come from the kernel: the
     * build targets 16 KB alignment, but that is a linker hint — mprotect only accepts real
     * pages, and a fixed 0x1000 mask would fail with EINVAL on a 16 KB-page device. */
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    uintptr_t page_start = slot & ~((uintptr_t) page - 1);
    size_t span = (size_t) (((slot + sizeof(uintptr_t)) - page_start + page - 1) / page * page);

    if (mprotect((void *) page_start, span, PROT_READ | PROT_WRITE) != 0) {
        LOGW("event=mprotect_failed variant=%s slot=%p page=%ld",
             pick->tag, (void *) slot, page);
        return NULL;
    }
    *(volatile uintptr_t *) slot = gadget;
    __builtin___clear_cache((char *) slot, (char *) (slot + sizeof(uintptr_t)));
    mprotect((void *) page_start, span, PROT_READ);

    LOGI("event=building_patch variant=%s slot=%p old=%p new=%p base=%p",
         pick->tag, (void *) slot, (void *) current, (void *) gadget, (void *) base);
    return NULL;
}

__attribute__((constructor))
static void amap_buildings_start(void) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, patch_thread, NULL) == 0) pthread_detach(thread);
}
