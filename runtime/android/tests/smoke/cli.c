/* Headless CPU/OS smoke test for the Android Wine + FEX runtime.
 * Built for x86_64 (ARM64EC FEX) and i686 (WoW64 FEX). Prints one line per
 * check and exits non-zero when a required check fails. No windows, no input. */
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>

static int failures;
#define CHECK(name, cond, ...) do { if (cond) printf("PASS %s\n", name); \
    else { failures++; printf("FAIL %s: ", name); printf(__VA_ARGS__); printf("\n"); } fflush(stdout); } while (0)
#define INFO(...) do { printf("INFO "); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)

static DWORD WINAPI worker(void *arg) { for (int i = 0; i < 100000; i++) InterlockedIncrement((LONG *)arg); return 0; }

static LONG WINAPI handler(EXCEPTION_POINTERS *info)
{
    if (info->ExceptionRecord->ExceptionCode == 0xE0000001) return EXCEPTION_CONTINUE_EXECUTION;
    return EXCEPTION_CONTINUE_SEARCH;
}

static volatile int skipped;
static LONG WINAPI av_handler(EXCEPTION_POINTERS *info)
{
    if (info->ExceptionRecord->ExceptionCode != EXCEPTION_ACCESS_VIOLATION) return EXCEPTION_CONTINUE_SEARCH;
    skipped = 1;
#ifdef _WIN64
    info->ContextRecord->Rip += 7; /* skip the 7-byte faulting store below */
#else
    info->ContextRecord->Eip += 6;
#endif
    return EXCEPTION_CONTINUE_EXECUTION;
}

int main(void)
{
    SYSTEM_INFO si;
    GetNativeSystemInfo(&si);
    INFO("native arch %u, %lu processors, page %lu, granularity %lu", si.wProcessorArchitecture,
         si.dwNumberOfProcessors, si.dwPageSize, si.dwAllocationGranularity);
    CHECK("page size", si.dwPageSize == 4096 && si.dwAllocationGranularity == 65536,
          "page %lu granularity %lu", si.dwPageSize, si.dwAllocationGranularity);

    /* Threads and atomics across translated threads. */
    LONG count = 0;
    HANDLE threads[4];
    for (int i = 0; i < 4; i++) threads[i] = CreateThread(NULL, 0, worker, &count, 0, NULL);
    DWORD wait = WaitForMultipleObjects(4, threads, TRUE, 20000);
    CHECK("threads", wait == WAIT_OBJECT_0 && count == 400000, "wait %lu count %ld", wait, count);

    /* Memory: reserve, commit, protect, release. */
    SIZE_T size = 64 << 20;
    char *mem = VirtualAlloc(NULL, size, MEM_RESERVE, PAGE_NOACCESS);
    BOOL committed = mem && VirtualAlloc(mem + 4096, 3 * 4096, MEM_COMMIT, PAGE_READWRITE) != NULL;
    if (committed) memset(mem + 4096, 0x5a, 3 * 4096);
    DWORD old = 0;
    BOOL protect = committed && VirtualProtect(mem + 8192, 4096, PAGE_READONLY, &old);
    CHECK("virtual memory", committed && protect && ((ULONG_PTR)mem & 0xffff) == 0, "mem %p commit %d protect %d err %lu",
          mem, committed, protect, GetLastError());
    if (mem) VirtualFree(mem, 0, MEM_RELEASE);

    /* Structured exceptions: a software exception and a hardware fault. */
    PVOID veh = AddVectoredExceptionHandler(1, handler);
    RaiseException(0xE0000001, 0, 0, NULL);
    RemoveVectoredExceptionHandler(veh);
    CHECK("software exception", 1, "%s", "");
    veh = AddVectoredExceptionHandler(1, av_handler);
#ifdef _WIN64
    /* 48 c7 00 2a 00 00 00 : mov qword ptr [rax], 42  (7 bytes) */
    __asm__ volatile ("xor %%eax, %%eax\n\t.byte 0x48,0xc7,0x00,0x2a,0x00,0x00,0x00" ::: "rax", "memory");
#else
    /* c7 00 2a 00 00 00 : mov dword ptr [eax], 42  (6 bytes) */
    __asm__ volatile ("xor %%eax, %%eax\n\t.byte 0xc7,0x00,0x2a,0x00,0x00,0x00" ::: "eax", "memory");
#endif
    RemoveVectoredExceptionHandler(veh);
    CHECK("access violation", skipped, "handler not reached");

    /* Files in the working folder and in C:. */
    FILE *f = fopen("madeira-smoke.txt", "wb");
    int wrote = f && fputs("smoke\n", f) >= 0;
    if (f) fclose(f);
    char buf[16] = {0};
    f = fopen("madeira-smoke.txt", "rb");
    if (f) { fread(buf, 1, sizeof(buf) - 1, f); fclose(f); }
    CHECK("files", wrote && !strcmp(buf, "smoke\n"), "wrote %d read '%s'", wrote, buf);
    HANDLE h = CreateFileA("C:\\windows\\temp\\madeira-smoke.txt", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    CHECK("drive C", h != INVALID_HANDLE_VALUE, "error %lu", GetLastError());
    if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); DeleteFileA("C:\\windows\\temp\\madeira-smoke.txt"); }

    /* Registry written by wineboot, and the 64-bit prefix. */
    char product[128] = {0};
    DWORD len = sizeof(product);
    LONG rr = RegGetValueA(HKEY_LOCAL_MACHINE, "Software\\Microsoft\\Windows NT\\CurrentVersion", "ProductName",
                           RRF_RT_REG_SZ, NULL, product, &len);
    CHECK("registry", rr == ERROR_SUCCESS, "error %ld", rr);
    INFO("windows %s", product);
    char sysdir[MAX_PATH];
    GetSystemWow64DirectoryA(sysdir, MAX_PATH);
    CHECK("64-bit prefix", GetFileAttributesA("C:\\windows\\syswow64\\kernel32.dll") != INVALID_FILE_ATTRIBUTES,
          "no syswow64 kernel32");

    /* Child processes: Wine starts these by exec'ing its loader again. */
    STARTUPINFOA start = { sizeof(start) };
    PROCESS_INFORMATION proc;
    char cmd[] = "C:\\windows\\system32\\cmd.exe /c exit 7";
    DWORD code = 0;
    BOOL created = CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &start, &proc);
    if (created) {
        WaitForSingleObject(proc.hProcess, 60000);
        GetExitCodeProcess(proc.hProcess, &code);
        CloseHandle(proc.hProcess); CloseHandle(proc.hThread);
    }
    CHECK("child process", created && code == 7, "created %d exit %lu error %lu", created, code, GetLastError());

    /* Timing. */
    LARGE_INTEGER freq, t0, t1;
    QueryPerformanceFrequency(&freq); QueryPerformanceCounter(&t0);
    Sleep(50);
    QueryPerformanceCounter(&t1);
    double ms = (t1.QuadPart - t0.QuadPart) * 1000.0 / freq.QuadPart;
    CHECK("timers", ms >= 45 && ms < 2000, "slept %.1f ms", ms);

    /* Networking stack (resolver is optional: CI may have no DNS for Android's resolver). */
    WSADATA wsa;
    int ws = WSAStartup(MAKEWORD(2, 2), &wsa);
    CHECK("winsock", ws == 0, "WSAStartup %d", ws);
    struct addrinfo *ai = NULL;
    int gai = getaddrinfo("localhost", "80", NULL, &ai);
    INFO("getaddrinfo(localhost) = %d", gai);
    if (ai) freeaddrinfo(ai);
    SOCKET s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in local = { AF_INET };
    local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int bound = s != INVALID_SOCKET && bind(s, (struct sockaddr *)&local, sizeof(local)) == 0 && listen(s, 1) == 0;
    CHECK("sockets", bound, "error %d", WSAGetLastError());
    if (s != INVALID_SOCKET) closesocket(s);

    printf(failures ? "RESULT FAIL %d\n" : "RESULT PASS\n", failures);
    return failures ? 1 : 0;
}
