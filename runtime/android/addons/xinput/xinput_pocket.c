/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright 2026 Pocket Arcade contributors
 *
 * An XInput DLL for Wine on Android. Android has no path for a game controller
 * into Wine's input stack, so the app writes each pad's state (physical
 * controllers mapped by Android, and the on-screen touch controls) into a
 * small shared file, and this DLL reads it: games that use XInput see up to
 * four ordinary Xbox controllers. Rumble requests go back the same way.
 *
 * The file is C:\windows\pocketarcade-pad.bin (4096 bytes):
 *   0   "PAPAD001"
 *   16  u32 reads: incremented on every XInputGetState, so the app can tell
 *       whether the game reads controllers at all
 *   64 + 64*i  pad i: u32 connected, u32 packet, XINPUT_GAMEPAD (12 bytes),
 *              u16 left motor, u16 right motor (written here)
 * Wine maps the file with mmap(MAP_SHARED), so the app's own mapping of the
 * same file sees every write without a copy.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <string.h>

#define ERROR_DEVICE_NOT_CONNECTED_ 1167L
#define XUSER_MAX_COUNT 4

typedef struct { WORD wButtons; BYTE bLeftTrigger, bRightTrigger; SHORT sThumbLX, sThumbLY, sThumbRX, sThumbRY; } XINPUT_GAMEPAD;
typedef struct { DWORD dwPacketNumber; XINPUT_GAMEPAD Gamepad; } XINPUT_STATE;
typedef struct { WORD wLeftMotorSpeed, wRightMotorSpeed; } XINPUT_VIBRATION;
typedef struct { BYTE Type, SubType; WORD Flags; XINPUT_GAMEPAD Gamepad; XINPUT_VIBRATION Vibration; } XINPUT_CAPABILITIES;
typedef struct { XINPUT_CAPABILITIES Capabilities; WORD VendorId, ProductId, VersionNumber, unk1; DWORD unk2; } XINPUT_CAPABILITIES_EX;
typedef struct { BYTE BatteryType, BatteryLevel; } XINPUT_BATTERY_INFORMATION;
typedef struct { WORD VirtualKey; WCHAR Unicode; WORD Flags; BYTE UserIndex, HidCode; } XINPUT_KEYSTROKE;

#pragma pack(push, 1)
typedef struct {
    volatile DWORD connected;
    volatile DWORD packet;
    XINPUT_GAMEPAD gamepad;
    volatile WORD left_motor, right_motor;
    BYTE reserved[64 - 4 - 4 - 12 - 4];
} shared_pad;
#pragma pack(pop)

static volatile LONG mapped_once;
static BYTE *shared;
static BOOL enabled = TRUE;
static volatile DWORD last_attempt;

static BYTE *map_shared(void)
{
    if (shared) return shared;
    /* No file yet (the app has not started its pad bridge): look again at most once a second. */
    DWORD now = GetTickCount();
    if (last_attempt && now - last_attempt < 1000) return NULL;
    if (InterlockedCompareExchange(&mapped_once, 1, 0) != 0)
    {
        /* Another thread maps it; wait briefly for it. */
        for (int i = 0; i < 100 && !shared; i++) Sleep(1);
        return shared;
    }
    last_attempt = now ? now : 1;
    HANDLE file = CreateFileW(L"C:\\windows\\pocketarcade-pad.bin", GENERIC_READ | GENERIC_WRITE,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) { mapped_once = 0; return NULL; }
    HANDLE mapping = CreateFileMappingW(file, NULL, PAGE_READWRITE, 0, 4096, NULL);
    CloseHandle(file);
    if (!mapping) { mapped_once = 0; return NULL; }
    BYTE *view = MapViewOfFile(mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 4096);
    CloseHandle(mapping);
    if (!view || memcmp(view, "PAPAD001", 8) != 0)
    {
        if (view) UnmapViewOfFile(view);
        mapped_once = 0;
        return NULL;
    }
    shared = view;
    return shared;
}

static shared_pad *pad(DWORD index)
{
    BYTE *base = map_shared();
    if (!base || index >= XUSER_MAX_COUNT) return NULL;
    shared_pad *p = (shared_pad *)(base + 64 + 64 * index);
    return p->connected ? p : NULL;
}

static DWORD get_state(DWORD index, XINPUT_STATE *state, BOOL guide)
{
    if (!state) return ERROR_BAD_ARGUMENTS;
    if (index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    BYTE *base = map_shared();
    if (base) InterlockedIncrement((volatile LONG *)(base + 16));
    shared_pad *p = pad(index);
    if (!p) return ERROR_DEVICE_NOT_CONNECTED_;
    state->dwPacketNumber = p->packet;
    if (enabled) state->Gamepad = p->gamepad;
    else memset(&state->Gamepad, 0, sizeof(state->Gamepad));
    if (!guide) state->Gamepad.wButtons &= ~0x0400;
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetState(DWORD index, XINPUT_STATE *state) { return get_state(index, state, FALSE); }
DWORD WINAPI XInputGetStateEx(DWORD index, XINPUT_STATE *state) { return get_state(index, state, TRUE); }

DWORD WINAPI XInputSetState(DWORD index, XINPUT_VIBRATION *vibration)
{
    if (index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    shared_pad *p = pad(index);
    if (!p) return ERROR_DEVICE_NOT_CONNECTED_;
    if (vibration && enabled)
    {
        p->left_motor = vibration->wLeftMotorSpeed;
        p->right_motor = vibration->wRightMotorSpeed;
    }
    return ERROR_SUCCESS;
}

static void fill_caps(XINPUT_CAPABILITIES *caps)
{
    memset(caps, 0, sizeof(*caps));
    caps->Type = 1;          /* XINPUT_DEVTYPE_GAMEPAD */
    caps->SubType = 1;       /* XINPUT_DEVSUBTYPE_GAMEPAD */
    caps->Flags = 0;
    caps->Gamepad.wButtons = 0xf3ff;
    caps->Gamepad.bLeftTrigger = caps->Gamepad.bRightTrigger = 0xff;
    caps->Gamepad.sThumbLX = caps->Gamepad.sThumbLY = caps->Gamepad.sThumbRX = caps->Gamepad.sThumbRY = (SHORT)0xffc0;
    caps->Vibration.wLeftMotorSpeed = caps->Vibration.wRightMotorSpeed = 0xff;
}

DWORD WINAPI XInputGetCapabilities(DWORD index, DWORD flags, XINPUT_CAPABILITIES *caps)
{
    if (!caps || index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    if (!pad(index)) return ERROR_DEVICE_NOT_CONNECTED_;
    fill_caps(caps);
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetCapabilitiesEx(DWORD unknown, DWORD index, DWORD flags, XINPUT_CAPABILITIES_EX *caps)
{
    if (!caps || index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    if (!pad(index)) return ERROR_DEVICE_NOT_CONNECTED_;
    memset(caps, 0, sizeof(*caps));
    fill_caps(&caps->Capabilities);
    caps->VendorId = 0x045e; caps->ProductId = 0x028e; caps->VersionNumber = 0x0114;
    return ERROR_SUCCESS;
}

void WINAPI XInputEnable(BOOL enable)
{
    enabled = enable;
    if (!enable)
        for (DWORD i = 0; i < XUSER_MAX_COUNT; i++)
        {
            shared_pad *p = pad(i);
            if (p) p->left_motor = p->right_motor = 0;
        }
}

DWORD WINAPI XInputGetBatteryInformation(DWORD index, BYTE type, XINPUT_BATTERY_INFORMATION *battery)
{
    if (!battery || index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    if (!pad(index)) return ERROR_DEVICE_NOT_CONNECTED_;
    battery->BatteryType = 1;    /* BATTERY_TYPE_WIRED */
    battery->BatteryLevel = 3;   /* BATTERY_LEVEL_FULL */
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetKeystroke(DWORD index, DWORD reserved, XINPUT_KEYSTROKE *keystroke)
{
    if (!keystroke) return ERROR_BAD_ARGUMENTS;
    if (index != 0xff && (index >= XUSER_MAX_COUNT || !pad(index))) return ERROR_DEVICE_NOT_CONNECTED_;
    return ERROR_EMPTY;
}

DWORD WINAPI XInputGetDSoundAudioDeviceGuids(DWORD index, GUID *render, GUID *capture)
{
    if (index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    if (!pad(index)) return ERROR_DEVICE_NOT_CONNECTED_;
    if (render) memset(render, 0, sizeof(*render));
    if (capture) memset(capture, 0, sizeof(*capture));
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetAudioDeviceIds(DWORD index, WCHAR *render, UINT *render_count, WCHAR *capture, UINT *capture_count)
{
    if (index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    if (!pad(index)) return ERROR_DEVICE_NOT_CONNECTED_;
    if (render_count) *render_count = 0;
    if (capture_count) *capture_count = 0;
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputWaitForGuideButton(DWORD index, DWORD flags, void *unknown) { return ERROR_SUCCESS; }
DWORD WINAPI XInputCancelGuideButtonWait(DWORD index) { return ERROR_SUCCESS; }
DWORD WINAPI XInputPowerOffController(DWORD index) { return ERROR_SUCCESS; }
DWORD WINAPI XInputGetBaseBusInformation(DWORD index, void *info) { return ERROR_DEVICE_NOT_CONNECTED_; }

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, void *reserved)
{
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(instance);
    return TRUE;
}
