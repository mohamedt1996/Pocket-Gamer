/* Headless graphics device test: creates a Direct3D 11 device (DXVK) and a
 * Direct3D 12 device (VKD3D-Proton) on the first adapter, without a window.
 * This checks that Wine finds the Vulkan loader and a Vulkan driver; it does
 * not render or present anything. */
#define COBJMACROS
#define INITGUID
#include <windows.h>
#include <d3d11.h>
#include <d3d12.h>
#include <dxgi.h>
#include "out.h"

int main(int argc, char **argv)
{
    int failures = 0;
    int want12 = !(argc > 1 && !strcmp(argv[1], "d3d11"));

    ID3D11Device *dev11 = NULL;
    D3D_FEATURE_LEVEL level = 0;
    HRESULT hr = D3D11CreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, NULL, 0, D3D11_SDK_VERSION, &dev11, &level, NULL);
    if (SUCCEEDED(hr)) {
        IDXGIDevice *dxgi = NULL;
        IDXGIAdapter *adapter = NULL;
        DXGI_ADAPTER_DESC desc = {0};
        if (SUCCEEDED(ID3D11Device_QueryInterface(dev11, &IID_IDXGIDevice, (void **)&dxgi)) &&
            SUCCEEDED(IDXGIDevice_GetAdapter(dxgi, &adapter)) && SUCCEEDED(IDXGIAdapter_GetDesc(adapter, &desc)))
            out("INFO adapter %ls vendor %04x device %04x", desc.Description, desc.VendorId, desc.DeviceId);
        if (adapter) IDXGIAdapter_Release(adapter);
        if (dxgi) IDXGIDevice_Release(dxgi);
        out("PASS d3d11 device (feature level %x)", level);
        ID3D11Device_Release(dev11);
    } else {
        out("FAIL d3d11 device: hr %08lx", hr);
        failures++;
    }

    if (want12) {
        ID3D12Device *dev12 = NULL;
        hr = D3D12CreateDevice(NULL, D3D_FEATURE_LEVEL_11_0, &IID_ID3D12Device, (void **)&dev12);
        if (SUCCEEDED(hr)) { out("PASS d3d12 device"); ID3D12Device_Release(dev12); }
        else { out("FAIL d3d12 device: hr %08lx", hr); failures++; }
    }
    if (failures) out("RESULT FAIL %d", failures); else out("RESULT PASS");
    return failures ? 1 : 0;
}
