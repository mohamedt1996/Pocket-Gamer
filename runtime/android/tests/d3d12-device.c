#define COBJMACROS
#include <windows.h>
#include <d3d12.h>
int main(void) {
 ID3D12Device *device=NULL;
 HRESULT hr=D3D12CreateDevice(NULL,D3D_FEATURE_LEVEL_11_0,&IID_ID3D12Device,(void**)&device);
 if(FAILED(hr)){MessageBoxA(NULL,"D3D12 device creation failed","FAIL",MB_OK);return 1;}
 ID3D12Device_Release(device);
 MessageBoxA(NULL,"D3D12 device creation passed. This is NOT a rendering test.","D3D12 device PASS",MB_OK);
 return 0;
}
