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

/* 17.02.0.2010 — see the map at the top of the file. */
#define VT_SLOT_PROCESS 0x1f40868UL   /* &BuildingStyleLayer_vtable[4] */
#define FN_PROCESS      0x18b17e0UL   /* what that slot must currently hold */
#define GADGET_ZERO_RET 0xe84148UL    /* mov x0, xzr ; ret */

/* First four instructions of FN_PROCESS: stp x29,x30,[sp,#-0x30]! / str x19,[sp,#0x10] /
 * stp x20,x19,[sp,#0x20] / mov x29,sp. Verified against the shipped .so. */
static const uint32_t FN_PROCESS_PROLOGUE[4] = {
    0xa9bd7bfdU, 0xf9000bf5U, 0xa9024ff4U, 0x910003fdU
};
/* mov x0, xzr ; ret */
static const uint32_t GADGET_WORDS[2] = { 0xaa1f03e0U, 0xd65f03c0U };

static int on_phdr(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    (void) data;
    if (info->dlpi_name && strstr(info->dlpi_name, TARGET_LIB)) {
        *(uintptr_t *) data = (uintptr_t) info->dlpi_addr;
        return 1;
    }
    return 0;
}

static uintptr_t find_lib_base(void) {
    uintptr_t base = 0;
    dl_iterate_phdr(on_phdr, &base);
    return base;
}

static int words_match(uintptr_t address, const uint32_t *expected, int count) {
    const volatile uint32_t *at = (const volatile uint32_t *) address;
    for (int i = 0; i < count; i++) {
        if (at[i] != expected[i]) return 0;
    }
    return 1;
}

static void *patch_thread(void *arg) {
    (void) arg;

    /* libamapr.so is loaded lazily by the map engine, well after our constructor runs. */
    uintptr_t base = 0;
    for (int i = 0; i < 1200 && !base; i++) {   /* ~60 s at 50 ms */
        base = find_lib_base();
        if (!base) usleep(50000);
    }
    if (!base) {
        LOGI("event=anchor_skip reason=lib_absent lib=%s", TARGET_LIB);
        return NULL;
    }

    uintptr_t slot = base + VT_SLOT_PROCESS;
    uintptr_t function = base + FN_PROCESS;
    uintptr_t gadget = base + GADGET_ZERO_RET;

    /* Check 1: the vtable slot still points at the function we re-derived. */
    uintptr_t current = *(volatile uintptr_t *) slot;
    if (current == gadget) {
        LOGI("event=already_patched slot=%p base=%p", (void *) slot, (void *) base);
        return NULL;
    }
    if (current != function) {
        LOGW("event=anchor_mismatch slot=%p want=%p got=%p base=%p",
             (void *) slot, (void *) function, (void *) current, (void *) base);
        return NULL;
    }

    /* Check 2: the code behind it is the function we think it is. Guards against a build that
     * kept the vtable layout but reordered the slot contents. */
    if (!words_match(function, FN_PROCESS_PROLOGUE, 4)
        || !words_match(gadget, GADGET_WORDS, 2)) {
        LOGW("event=anchor_mismatch reason=prologue slot=%p function=%p", (void *) slot,
             (void *) function);
        return NULL;
    }

    /* .data.rel.ro is read-only after relocation. Page size must come from the kernel: the
     * build targets 16 KB alignment, but that is a linker hint — mprotect only accepts real
     * pages, and a fixed 0x1000 mask would fail with EINVAL on a 16 KB-page device. */
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    uintptr_t page_start = slot & ~((uintptr_t) page - 1);
    size_t span = (size_t) (((slot + sizeof(uintptr_t)) - page_start + page - 1) / page * page);

    if (mprotect((void *) page_start, span, PROT_READ | PROT_WRITE) != 0) {
        LOGW("event=mprotect_failed slot=%p page=%ld", (void *) slot, page);
        return NULL;
    }
    *(volatile uintptr_t *) slot = gadget;
    __builtin___clear_cache((char *) slot, (char *) (slot + sizeof(uintptr_t)));
    mprotect((void *) page_start, span, PROT_READ);

    LOGI("event=building_patch slot=%p old=%p new=%p base=%p",
         (void *) slot, (void *) current, (void *) gadget, (void *) base);
    return NULL;
}

__attribute__((constructor))
static void amap_buildings_start(void) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, patch_thread, NULL) == 0) pthread_detach(thread);
}
