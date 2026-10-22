Window capture helper (Windows 10/11 x64)

window-capture.cs is original project code. window-capture.exe is its .NET Framework x64 build.
No application is enumerated, captured, activated or given input during compilation.
Rebuild: powershell -NoProfile -File window-capture.ps1 -CompileOnly

Capture backends: Windows.Graphics.Capture (default/automatic), PrintWindow fallback.
WGC uses Direct3D11CaptureFramePool.CreateFreeThreaded, FrameArrived and TryGetNextFrame continuously,
keeps the latest source frame, crops the selected window client area, copies a D3D11 staging surface,
and writes lossless BGRA frames with real native size, frame number, UTC timestamp and sampled hash.
Native resolution is never enlarged artificially. Limits: 8192 pixels per edge and 33554432 pixels.
Source-update mode follows available WGC frames without a fixed FPS or byte-rate cap and uploads the newest complete frame in one render. Optional capped mode has an upload budget. Actual capture and display FPS are reported independently.
Frame arrival does not ensure a background browser tab keeps playing. Minimized, protected/DRM,
cloaked or suspended windows may not produce usable frames. The helper never alters playback or browser settings.

Input is opt-in and sent only by PostMessage to the chosen HWND or its verified same-PID child.
Successful PostMessage means queued, not confirmed processed. GPU browsers, RawInput games,
elevated applications or custom controls may ignore these messages. No SendInput, window activation,
foreground change or desktop-screen capture fallback is used.
Mouse/keyboard releases are requested on exit, source switch, module disable, disconnect and shutdown.

Protocol v3 (little endian): 9 int32 fields
magic=0x33534341, status, image width, image height, native client width, native client height,
payload byte count, backend(1 GDI/2 WGC), sampled content hash;
then int64 frame number and UTC milliseconds, then exactly width*height*4 BGRA bytes for a frame.
Input uses bounded ASCII tab-delimited lines M/B/W/K/C/R/Q over the helper's own stdin.

Primary API documentation:
https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframepool.createfreethreaded
https://learn.microsoft.com/en-us/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-postmessagew
https://learn.microsoft.com/en-us/windows/win32/inputdev/wm-mousewheel