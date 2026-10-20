// SPDX-License-Identifier: MIT
// FEX stats use POSIX shm, absent from bionic. Keep the named backing files
// private to this app. This is diagnostic shared memory, not Wine synchronization.
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
static int madeira_shm_path(const char *name, char *out, size_t cap) {
    const char *tmp = getenv("TMPDIR");
    if (!tmp || tmp[0] != '/') { errno = EINVAL; return -1; }
    while (*name == '/') ++name;
    if (!*name || strchr(name, '/') || strstr(name, "..")) { errno = EINVAL; return -1; }
    int len = snprintf(out, cap, "%s/%s", tmp, name);
    if (len < 0 || (size_t)len >= cap) { errno = ENAMETOOLONG; return -1; }
    return 0;
}
static int madeira_shm_open(const char *name, int flags, mode_t mode) {
    char path[PATH_MAX];
    (void)mode;
    if (madeira_shm_path(name, path, sizeof(path))) return -1;
    return open(path, flags | O_CLOEXEC | O_NOFOLLOW, 0600);
}
static int madeira_shm_unlink(const char *name) {
    char path[PATH_MAX];
    if (madeira_shm_path(name, path, sizeof(path))) return -1;
    return unlink(path);
}
#define shm_open madeira_shm_open
#define shm_unlink madeira_shm_unlink
