#!/usr/bin/env python3
"""Rebuild LGPL AC3/EAC3-only Media3 JNI libraries with an official Android NDK.
Usage: python build_native.py --ndk /path/android-ndk-r28b --work /tmp/tvnext-dolby [--host]
The pristine FFmpeg source is shipped in source-archives. No downloads are required.
"""
from pathlib import Path
import argparse,tarfile,subprocess,os,hashlib,shutil
p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--host',action='store_true');p.add_argument('--jobs',type=int,default=4);a=p.parse_args()
module=Path(__file__).resolve().parents[1];repo=module.parents[1];work=a.work.resolve();work.mkdir(parents=True,exist_ok=True)
archive=repo/'source-archives/ffmpeg-n6.0.1.tar.gz';source=work/'FFmpeg-n6.0.1'
if not source.exists():
 with tarfile.open(archive) as tar:tar.extractall(work,filter='data')
ndk=a.ndk.resolve();bin=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';api='24'
common=['--enable-static','--disable-shared','--enable-pic','--disable-debug','--disable-doc','--disable-programs','--disable-everything','--disable-autodetect','--disable-network','--disable-avdevice','--disable-avformat','--disable-avfilter','--disable-swscale','--disable-postproc','--disable-symver','--disable-vulkan','--enable-swresample','--enable-decoder=ac3,eac3','--enable-parser=ac3','--extra-cflags=-O2 -fPIC -ffunction-sections -fdata-sections']
abis=[('armeabi-v7a','armv7a-linux-androideabi','arm','armv7-a'),('arm64-v8a','aarch64-linux-android','aarch64','armv8-a'),('x86','i686-linux-android','x86','i686'),('x86_64','x86_64-linux-android','x86_64','x86-64')]
if a.host:abis.append(('host',None,'x86_64','x86-64'))
for abi,triple,arch,cpu in abis:
 build=work/abi;build.mkdir(exist_ok=True);prefix=build/'install';out=(work/'host-jni') if abi=='host' else module/'src/main/jniLibs'/abi
 out.mkdir(parents=True,exist_ok=True)
 config=[str(source/'configure'),*common,f'--prefix={prefix}',f'--arch={arch}',f'--cpu={cpu}']
 if triple:
  cc=bin/f'{triple}{api}-clang';cxx=bin/f'{triple}{api}-clang++'
  config += ['--target-os=android','--enable-cross-compile',f'--cc={cc}',f'--cxx={cxx}',f'--ar={bin}/llvm-ar',f'--nm={bin}/llvm-nm',f'--ranlib={bin}/llvm-ranlib',f'--strip={bin}/llvm-strip']
 else: cxx=Path('/usr/bin/g++')
 if arch.startswith('x86'):config+=['--disable-asm']
 print('Building',abi,flush=True)
 if not (prefix/'lib/libavcodec.a').exists():
  subprocess.run(config,cwd=build,check=True)
  subprocess.run(['make',f'-j{min(max(a.jobs,1),8)}'],cwd=build,check=True)
  subprocess.run(['make','install'],cwd=build,check=True)
 command=[str(cxx),'-std=c++11','-O2','-shared','-fPIC','-fno-exceptions','-fno-rtti','-I'+str(prefix/'include'),str(module/'src/main/native/ffmpeg_jni.cc')]
 if triple:command+=['-nostdlib++','-llog','-Wl,-z,max-page-size=16384','-Wl,-Bsymbolic']
 else:
  jdk=Path(os.environ['JAVA_HOME']);stub=work/'host-include/android';stub.mkdir(parents=True,exist_ok=True)
  (stub/'log.h').write_text('#pragma once\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int,const char*,const char*,...){return 0;}\n')
  command+=['-I'+str(jdk/'include'),'-I'+str(jdk/'include/linux'),'-I'+str(work/'host-include')]
 command += ['-Wl,--no-undefined','-Wl,-soname,libffmpegJNI.so','-Wl,--gc-sections','-Wl,--start-group',str(prefix/'lib/libswresample.a'),str(prefix/'lib/libavcodec.a'),str(prefix/'lib/libavutil.a'),'-Wl,--end-group','-lm','-o',str(out/'libffmpegJNI.so')]
 subprocess.run(command,check=True)
 if triple:subprocess.run([str(bin/'llvm-strip'),'--strip-unneeded',str(out/'libffmpegJNI.so')],check=True)
 print('Built',abi,(out/'libffmpegJNI.so').stat().st_size,'bytes',flush=True)
