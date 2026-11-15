#!/usr/bin/env python3
"""Build the small, reproducible Windows launcher with LLVM (Linux or Windows)."""
from pathlib import Path
import argparse,re,subprocess,shutil
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--llvm-bin',type=Path)
parser.add_argument('--output',type=Path,default=root/'.local/windows-launcher')
a=parser.parse_args()
llvm=a.llvm_bin or Path(shutil.which('clang') or 'clang').parent
suffix='.exe' if (llvm/'clang.exe').exists() else ''
def tool(name):return str(llvm/(name+suffix))
a.output.mkdir(parents=True,exist_ok=True)
version=re.search(r'NAME = "([^"]+)"',(root/'core/common/src/commonMain/kotlin/com/tvnext/app/core/common/AppVersion.kt').read_text())[1]
parts=(version.split('.')+['0']*4)[:4];v=','.join(parts)
icon=(root/'apps/desktop/icon.ico').as_posix()
resource=f'''1 ICON "{icon}"
1 VERSIONINFO
FILEVERSION {v}
PRODUCTVERSION {v}
FILEFLAGSMASK 0x3fL
FILEFLAGS 0
FILEOS 0x40004L
FILETYPE 0x1L
FILESUBTYPE 0
BEGIN
 BLOCK "StringFileInfo"
 BEGIN
  BLOCK "040904B0"
  BEGIN
   VALUE "CompanyName", "TVNext\\0"
   VALUE "FileDescription", "TVNext portable launcher\\0"
   VALUE "FileVersion", "{version}\\0"
   VALUE "InternalName", "TVNext\\0"
   VALUE "OriginalFilename", "TVNext.exe\\0"
   VALUE "ProductName", "TVNext\\0"
   VALUE "ProductVersion", "{version}\\0"
  END
 END
 BLOCK "VarFileInfo"
 BEGIN
  VALUE "Translation", 0x0409, 1200
 END
END
'''
rc=a.output/'launcher.rc';rc.write_text(resource)
for dll,names in [('KERNEL32',['GetModuleFileNameW','SetCurrentDirectoryW','CreateDirectoryW','GetFileAttributesW','CreateProcessW','CloseHandle','ExitProcess']),('USER32',['MessageBoxW'])]:
 d=a.output/(dll+'.def');d.write_text('LIBRARY '+dll+'.dll\nEXPORTS\n'+'\n'.join(names)+'\n')
 subprocess.run([tool('llvm-dlltool'),'-m','i386:x86-64','-d',str(d),'-l',str(a.output/(dll+'.lib'))],check=True)
obj=a.output/'launcher.obj';res=a.output/'launcher.res';exe=a.output/'TVNext.exe'
subprocess.run([tool('clang'),'--target=x86_64-pc-windows-msvc','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-c',str(root/'apps/desktop/launcher/launcher.c'),'-o',str(obj)],check=True)
subprocess.run([tool('llvm-rc'),'/fo',str(res),str(rc)],check=True)
subprocess.run([tool('lld-link'),'/entry:mainCRTStartup','/subsystem:windows','/machine:x64','/nodefaultlib','/timestamp:0','/out:'+str(exe),str(obj),str(res),str(a.output/'KERNEL32.lib'),str(a.output/'USER32.lib')],check=True)
print('Windows x64 launcher '+version+': '+str(exe))
