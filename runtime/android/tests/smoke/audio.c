/* Plays one second of a 440 Hz tone through WASAPI (what XAudio2, DirectSound
 * and most games sit on), shared mode, default render device. With Wine's
 * DirectAudio driver this goes Windows API -> mmdevapi -> winedirectaudio ->
 * AAudio. The runner checks on the AAudio side that the tone arrived. */
#define COBJMACROS
#define INITGUID
#include <windows.h>
#include <initguid.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <math.h>
#include "out.h"

#define FAIL(what, hr) do { out("FAIL %s: hr %08lx", what, (unsigned long)(hr)); out("RESULT FAIL 1"); return 1; } while (0)

int main(void)
{
    IMMDeviceEnumerator *enumerator = NULL;
    IMMDevice *device = NULL;
    IAudioClient *client = NULL;
    IAudioRenderClient *render = NULL;
    WAVEFORMATEX *format = NULL;
    UINT32 frames = 0, padding = 0, written = 0;
    HRESULT hr;
    double phase = 0;

    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr = CoCreateInstance(&CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL, &IID_IMMDeviceEnumerator, (void **)&enumerator)))
        FAIL("device enumerator", hr);
    if (FAILED(hr = IMMDeviceEnumerator_GetDefaultAudioEndpoint(enumerator, eRender, eConsole, &device)))
        FAIL("default render device (no audio driver?)", hr);
    out("PASS default render device");
    if (FAILED(hr = IMMDevice_Activate(device, &IID_IAudioClient, CLSCTX_ALL, NULL, (void **)&client)))
        FAIL("IAudioClient", hr);
    if (FAILED(hr = IAudioClient_GetMixFormat(client, &format))) FAIL("mix format", hr);
    out("INFO mix format: %lu Hz, %u channels, %u bits", format->nSamplesPerSec, format->nChannels, format->wBitsPerSample);
    if (FAILED(hr = IAudioClient_Initialize(client, AUDCLNT_SHAREMODE_SHARED, 0, 10000000 /* 1 s */, 0, format, NULL)))
        FAIL("initialize", hr);
    if (FAILED(hr = IAudioClient_GetBufferSize(client, &frames))) FAIL("buffer size", hr);
    if (FAILED(hr = IAudioClient_GetService(client, &IID_IAudioRenderClient, (void **)&render))) FAIL("render client", hr);
    out("PASS stream initialised (%u frames)", frames);

    /* Queue a little, start, then keep the buffer topped up for about a second. */
    for (DWORD start = GetTickCount(), started = 0; GetTickCount() - start < 1200; Sleep(10)) {
        BYTE *data;
        UINT32 avail;
        if (FAILED(hr = IAudioClient_GetCurrentPadding(client, &padding))) FAIL("padding", hr);
        avail = frames - padding;
        if (avail && SUCCEEDED(IAudioRenderClient_GetBuffer(render, avail, &data))) {
            for (UINT32 i = 0; i < avail; i++, phase += 2 * 3.14159265358979 * 440 / format->nSamplesPerSec) {
                float v = (float)(0.5 * sin(phase));
                for (UINT32 c = 0; c < format->nChannels; c++) {
                    if (format->wBitsPerSample == 32) ((float *)data)[i * format->nChannels + c] = v;
                    else ((short *)data)[i * format->nChannels + c] = (short)(v * 32767);
                }
            }
            IAudioRenderClient_ReleaseBuffer(render, avail, 0);
            written += avail;
        }
        if (!started) { if (FAILED(hr = IAudioClient_Start(client))) FAIL("start", hr); started = 1; }
    }
    IAudioClient_Stop(client);
    out("PASS played %u frames", written);
    IAudioRenderClient_Release(render);
    IAudioClient_Release(client);
    IMMDevice_Release(device);
    IMMDeviceEnumerator_Release(enumerator);
    CoTaskMemFree(format);
    out("RESULT PASS");
    return 0;
}
