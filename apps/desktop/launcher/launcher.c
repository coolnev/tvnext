/* TVNext portable Windows x64 launcher. Only Win32 APIs; no external CRT. */
typedef unsigned long DWORD;
typedef unsigned short WORD;
typedef unsigned short WCHAR;
typedef int BOOL;
typedef void *HANDLE;
typedef struct {
    DWORD cb; WCHAR *reserved,*desktop,*title;
    DWORD x,y,xSize,ySize,xCount,yCount,fill,flags;
    WORD show,reservedSize; unsigned char *reservedData;
    HANDLE in,out,err;
} STARTUPINFO;
typedef struct {HANDLE process,thread;DWORD processId,threadId;} PROCESSINFO;
__declspec(dllimport) DWORD GetModuleFileNameW(HANDLE,WCHAR*,DWORD);
__declspec(dllimport) BOOL SetCurrentDirectoryW(const WCHAR*);
__declspec(dllimport) BOOL CreateDirectoryW(const WCHAR*,void*);
__declspec(dllimport) DWORD GetFileAttributesW(const WCHAR*);
__declspec(dllimport) BOOL CreateProcessW(const WCHAR*,WCHAR*,void*,void*,BOOL,DWORD,void*,const WCHAR*,STARTUPINFO*,PROCESSINFO*);
__declspec(dllimport) BOOL CloseHandle(HANDLE);
__declspec(dllimport) int MessageBoxW(HANDLE,const WCHAR*,const WCHAR*,unsigned int);
__declspec(dllimport) void ExitProcess(unsigned int);
#define MAX_PATH_CHARS 32768
static WCHAR root[MAX_PATH_CHARS],java[MAX_PATH_CHARS],data[MAX_PATH_CHARS],command[MAX_PATH_CHARS];
static STARTUPINFO startup;
static PROCESSINFO process;
static unsigned int length(const WCHAR*s){unsigned int n=0;while(s[n])n++;return n;}
static BOOL append(WCHAR*out,const WCHAR*text){unsigned int n=length(out),m=length(text);if(n+m>=MAX_PATH_CHARS)return 0;while((*out++)){}out--;while((*out++=*text++)){}return 1;}
static void fail(const WCHAR*text){MessageBoxW(0,text,L"TVNext 1.0.24",0x10);ExitProcess(1);}
void mainCRTStartup(void) {
    DWORD count=GetModuleFileNameW(0,root,MAX_PATH_CHARS);
    if(!count || count>=MAX_PATH_CHARS)fail(L"Unable to locate TVNext. Extract the entire portable ZIP first.");
    WCHAR *slash=0;
    for(DWORD i=0;root[i];i++)if(root[i]=='\\' || root[i]=='/')slash=root+i;
    if(!slash)fail(L"Unable to locate the application folder.");
    if(slash==root+2)slash[1]=0;else *slash=0;
    SetCurrentDirectoryW(root);
    append(java,root);append(java,L"\\runtime\\bin\\javaw.exe");
    append(data,root);append(data,L"\\data");
    if(GetFileAttributesW(java)==0xffffffff)fail(L"The Java runtime is missing. Extract the entire TVNext ZIP.");
    CreateDirectoryW(data,0);
    if(GetFileAttributesW(data)==0xffffffff)fail(L"TVNext needs a writable data folder. Extract it to a folder you can write to.");
    append(command,L"\"");append(command,java);append(command,L"\" -Dfile.encoding=UTF-8 \"-Dtvnext.app.dir=");
    append(command,root);append(command,L"\" \"-Dtvnext.data.dir=");append(command,data);
    append(command,L"\" \"-Djna.library.path=");append(command,root);
    if(!append(command,L"\" -XX:ErrorFile=data/hs_err_pid%p.log -cp \"lib/*\" com.tvnext.app.MainKt"))fail(L"The application path is too long.");
    startup.cb=sizeof(startup);
    if(!CreateProcessW(java,command,0,0,0,0x00000400,0,root,&startup,&process))fail(L"Unable to start TVNext. Try TVNext-debug.cmd for a diagnostic log.");
    CloseHandle(process.thread);CloseHandle(process.process);ExitProcess(0);
}
