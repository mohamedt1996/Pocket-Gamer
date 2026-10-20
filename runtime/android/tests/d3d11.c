#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
static LRESULT CALLBACK proc(HWND h,UINT m,WPARAM w,LPARAM l){if(m==WM_DESTROY){PostQuitMessage(0);return 0;}return DefWindowProcA(h,m,w,l);}
int main(void){
 WNDCLASSA wc={0};wc.lpfnWndProc=proc;wc.hInstance=GetModuleHandleA(NULL);wc.lpszClassName="MadeiraD3D11";
 if(!RegisterClassA(&wc))return 1;
 HWND window=CreateWindowA(wc.lpszClassName,"Madeira D3D11 blue frame",WS_OVERLAPPEDWINDOW|WS_VISIBLE,0,0,640,480,NULL,NULL,wc.hInstance,NULL);
 if(!window)return 2;
 DXGI_SWAP_CHAIN_DESC desc={0};desc.BufferDesc.Width=640;desc.BufferDesc.Height=480;desc.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;
 desc.SampleDesc.Count=1;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.OutputWindow=window;desc.Windowed=TRUE;
 desc.SwapEffect=DXGI_SWAP_EFFECT_DISCARD;
 IDXGISwapChain *swap=NULL;ID3D11Device *dev=NULL;ID3D11DeviceContext *ctx=NULL;ID3D11RenderTargetView *rt=NULL;ID3D11Texture2D *back=NULL;
 HRESULT hr=D3D11CreateDeviceAndSwapChain(NULL,D3D_DRIVER_TYPE_HARDWARE,NULL,0,NULL,0,D3D11_SDK_VERSION,&desc,&swap,&dev,NULL,&ctx);
 if(FAILED(hr)){MessageBoxA(window,"D3D11 device or swapchain failed","FAIL",MB_OK);return 3;}
 hr=IDXGISwapChain_GetBuffer(swap,0,&IID_ID3D11Texture2D,(void**)&back);if(FAILED(hr))return 4;
 hr=ID3D11Device_CreateRenderTargetView(dev,(ID3D11Resource*)back,NULL,&rt);ID3D11Texture2D_Release(back);if(FAILED(hr))return 5;
 FLOAT blue[]={0.03f,0.2f,0.85f,1.0f};MSG msg;int result=0;
 for(int frame=0;frame<300;frame++){
  while(PeekMessageA(&msg,NULL,0,0,PM_REMOVE)){if(msg.message==WM_QUIT)goto done;TranslateMessage(&msg);DispatchMessageA(&msg);}
  ID3D11DeviceContext_ClearRenderTargetView(ctx,rt,blue);hr=IDXGISwapChain_Present(swap,1,0);
  if(FAILED(hr)){result=6;break;}
 }
 done: ID3D11RenderTargetView_Release(rt);ID3D11DeviceContext_Release(ctx);ID3D11Device_Release(dev);IDXGISwapChain_Release(swap);DestroyWindow(window);return result;
}
