/* Test output to stdout and to <program>.txt beside the program: the runner
 * reads the file, so results do not depend on how Wine passes stdout on. */
#include <windows.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static FILE *out_file;

static void out(const char *fmt, ...)
{
    va_list ap;
    if (!out_file) {
        char path[MAX_PATH];
        DWORD n = GetModuleFileNameA(NULL, path, MAX_PATH);
        if (n > 4 && n < MAX_PATH) { strcpy(path + n - 4, ".txt"); out_file = fopen(path, "w"); }
    }
    va_start(ap, fmt); vprintf(fmt, ap); va_end(ap);
    printf("\n"); fflush(stdout);
    if (out_file) { va_start(ap, fmt); vfprintf(out_file, fmt, ap); va_end(ap); fputc('\n', out_file); fflush(out_file); }
}
