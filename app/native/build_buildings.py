"""Build libamapbldpatch.so - the AMap building-geometry patch.

    python app/native/build_buildings.py

Writes app/libs/arm64-v8a/libamapbldpatch.so.

!! After editing amap_buildings.c you MUST re-run this and commit the new .so.
The build does not run on its own: this project has no gradle, and app/build.ps1 /
tools/build_variant.py only *copy* app/libs/<abi>/*.so into the APK. If the source and the
binary drift apart, the module keeps shipping the old patch and the only symptom is
"my fix did nothing", which is indistinguishable from a wrong hook. Nothing inside the APK
can reveal that, so the .so is committed next to its source on purpose.

Toolchain - recorded so this is reproducible in six months:

    NDK        26.3.11579264
    clang      Android (11349228, based on r487747e) clang version 17.0.2
    triple     aarch64-linux-android26
    sysroot    <NDK>/toolchains/llvm/prebuilt/windows-x86_64/sysroot
    flags      -O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -shared
               -Wl,--gc-sections -Wl,-z,max-page-size=16384 -llog -pthread
    strip      llvm-strip --strip-unneeded

arm64-v8a ONLY, on purpose: every constant in amap_buildings.c is an address inside the
arm64 build of AMap's libamapr.so. A 32-bit or x86 build of the library would contain the
wrong addresses - the runtime self-check would refuse to patch, so shipping more ABIs would
only add bytes that can never do anything. Devices without an arm64 library entry simply
never load this one (see AmapBuildingPatch).

Kept separate from build.py (the zstd/label-assets build) because that one compiles ~150 C
files through a thread pool; this is a single translation unit.
"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "amap_buildings.c"
NDK = Path(r"C:\Users\Administrator\AppData\Local\Android\Sdk\ndk\26.3.11579264")
BIN = NDK / "toolchains/llvm/prebuilt/windows-x86_64/bin"
OUTPUT = ROOT.parent / "libs" / "arm64-v8a" / "libamapbldpatch.so"

# 16 KB page alignment: Android 15+ devices with 16 KB pages refuse to load a .so whose
# PT_LOAD segments are not 16 KB aligned. Same flag as build.py uses for the label library.
FLAGS = [
    "--target=aarch64-linux-android26",
    "--sysroot=" + str(BIN.parent / "sysroot"),
    "-O2", "-fPIC", "-fvisibility=hidden",
    "-ffunction-sections", "-fdata-sections",
    "-shared",
    "-Wl,--gc-sections",
    "-Wl,-z,max-page-size=16384",
    "-llog", "-pthread",
]


def run(args, what):
    result = subprocess.run([str(a) for a in args], capture_output=True)
    if result.returncode:
        # Fail loudly and leave nothing behind. A stale .so that survived a broken build is
        # the worst outcome: the APK would look fine and silently ship the previous patch.
        if OUTPUT.exists():
            OUTPUT.unlink()
        raise SystemExit(what + " FAILED (exit " + str(result.returncode) + ")\n"
                         + result.stderr.decode("utf8", "replace"))


def main():
    if not SOURCE.is_file():
        raise SystemExit("source missing: " + str(SOURCE))
    if not BIN.is_dir():
        raise SystemExit("NDK toolchain missing: " + str(BIN))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    if OUTPUT.exists():
        OUTPUT.unlink()
    run([BIN / "clang.exe", *FLAGS, SOURCE, "-o", OUTPUT], "clang")
    if not OUTPUT.is_file():
        raise SystemExit("clang reported success but produced no .so")
    run([BIN / "llvm-strip.exe", "--strip-unneeded", OUTPUT], "llvm-strip")
    if not OUTPUT.is_file() or OUTPUT.stat().st_size == 0:
        raise SystemExit("strip reported success but the .so is gone or empty")
    size = OUTPUT.stat().st_size
    # ELF64 AArch64, or the module would be shipping something the platform cannot load.
    blob = OUTPUT.read_bytes()
    if blob[:4] != b"\x7fELF" or blob[4] != 2 or int.from_bytes(blob[0x12:0x14], "little") != 183:
        raise SystemExit("not an ELF64 AArch64 object")
    print("NATIVE OK arm64-v8a " + str(size) + " bytes -> " + str(OUTPUT))


if __name__ == "__main__":
    sys.exit(main())