#include <windows.h>
#include <stdio.h>
static DWORD WINAPI worker(void *arg) { InterlockedIncrement((LONG*)arg);return 0; }
int main(void) {
 LONG count=0;HANDLE thread=CreateThread(NULL,0,worker,&count,0,NULL);
 if(!thread){printf("FAIL CreateThread %lu\n",GetLastError());return 11;}
 if(WaitForSingleObject(thread,5000)!=WAIT_OBJECT_0)return 12;
 CloseHandle(thread);
 if(count!=1)return 13;
 FILE *f=fopen("madeira-smoke.txt","wb");if(!f)return 14;
 fputs("Wine/FEX filesystem and thread smoke passed\n",f);fclose(f);
 MessageBoxA(NULL,"CPU translation, Windows threading and file write passed.","Madeira CPU smoke PASS",MB_OK);
 return 0;
}
