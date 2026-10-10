from pathlib import Path
import subprocess, tempfile, concurrent.futures, sys

ROOT=Path(__file__).resolve().parent
NDK=Path(r'C:\Users\Administrator\AppData\Local\Android\Sdk\ndk\26.3.11579264')
BIN=NDK/'toolchains/llvm/prebuilt/windows-x86_64/bin'
work=Path(tempfile.mkdtemp(prefix='mapclean-native-'))
vendor=ROOT/'vendor/zstd/lib'
abis={'arm64-v8a':'aarch64-linux-android26','armeabi-v7a':'armv7a-linux-androideabi26','x86':'i686-linux-android26','x86_64':'x86_64-linux-android26'}
def run(args):
    r=subprocess.run(list(map(str,args)),capture_output=True)
    if r.returncode: raise RuntimeError(r.stderr.decode('utf8','replace'))
for abi,target in abis.items():
    if len(sys.argv)>1 and abi not in sys.argv[1:]: continue
    output=ROOT.parent/'libs'/abi
    output.mkdir(exist_ok=True)
    common=['--target='+target,'--sysroot='+str(BIN.parent/'sysroot'),'-O2','-fPIC','-fvisibility=hidden','-ffunction-sections','-fdata-sections','-DZSTD_DISABLE_ASM','-DZSTD_MULTITHREAD=0','-I'+str(vendor),'-I'+str(vendor/'common')]
    sources=list((vendor/'common').glob('*.c'))+list((vendor/'compress').glob('*.c'))+list((vendor/'decompress').glob('*.c'))
    objects=[]
    def compile(source):
        obj=work/(abi+'-'+source.stem+'.o')
        run([BIN/'clang.exe',*common,'-c',source,'-o',obj]); return obj
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        objects=list(pool.map(compile,sources))
    run([BIN/'clang++.exe',*common,'-std=c++17','-fno-exceptions','-fno-rtti','-shared','-static-libstdc++',ROOT/'label_assets.cpp',*objects,'-Wl,--gc-sections','-Wl,-z,max-page-size=16384','-landroid','-llog','-ldl','-o',output/'libmapclean_labels.so'])
    run([BIN/'llvm-strip.exe','--strip-unneeded',output/'libmapclean_labels.so'])
    print('NATIVE OK',abi,(output/'libmapclean_labels.so').stat().st_size,flush=True)
