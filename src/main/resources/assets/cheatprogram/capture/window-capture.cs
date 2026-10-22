// Built with the Windows .NET Framework compiler; no NuGet or separately installed runtime is required.
// Windows.Graphics.Capture is projected from the operating system WinMetadata contract.
using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Diagnostics;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;

public static class CheatProgramWindowCapture {
 const int MaxEdge=8192,MaxPixels=33554432;
 [StructLayout(LayoutKind.Sequential)] struct RECT { public int L,T,R,B; }
 [StructLayout(LayoutKind.Sequential)] struct POINT { public int X,Y; public POINT(int x,int y){X=x;Y=y;} }
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct MONITORINFOEX { public int Size;public RECT Monitor,Work;public uint Flags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string Device; }
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct DISPLAYDEVICE { public int Size;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string Name;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string Description;public uint Flags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string Id;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string Key; }
 [StructLayout(LayoutKind.Sequential)] struct GUIINFO {public int Size,Flags;public IntPtr Active,Focus,Capture,MenuOwner,MoveSize,Caret;public RECT CaretRect;}
 [StructLayout(LayoutKind.Sequential)] struct BI { public uint Size; public int Width,Height; public ushort Planes,Bits; public uint Compression,ImageSize; public int Xppm,Yppm; public uint Used,Important; }
 [StructLayout(LayoutKind.Sequential)] struct DESC { public uint Width,Height,MipLevels,ArraySize,Format,SampleCount,SampleQuality,Usage,BindFlags,CPUAccessFlags,MiscFlags; }
 [StructLayout(LayoutKind.Sequential)] struct MAPPED { public IntPtr Data; public uint RowPitch,DepthPitch; }
 [StructLayout(LayoutKind.Sequential)] struct BOX { public uint L,T,F,R,B,Bk; }
 delegate bool EnumProc(IntPtr h,IntPtr l);
 delegate bool MonitorProc(IntPtr monitor,IntPtr dc,ref RECT bounds,IntPtr data);
 [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p,IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr parent,EnumProc callback,IntPtr data);
 [DllImport("user32.dll")] static extern bool EnumDisplayMonitors(IntPtr dc,IntPtr clip,MonitorProc callback,IntPtr data);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern bool GetMonitorInfoW(IntPtr monitor,ref MONITORINFOEX info);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern bool EnumDisplayDevicesW(string device,uint index,ref DISPLAYDEVICE info,uint flags);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] static extern bool IsWindowEnabled(IntPtr h);
 [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
 [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
 [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
 [DllImport("user32.dll")] static extern bool IsChild(IntPtr parent,IntPtr child);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h,StringBuilder t,int n);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h,StringBuilder t,int n);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] static extern bool GetGUIThreadInfo(uint thread,ref GUIINFO info);
 [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h,uint command);
 [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr h,uint flags);
 [DllImport("user32.dll",EntryPoint="GetWindowLongPtrW")] static extern IntPtr GetWindowLongPtr(IntPtr h,int index);
 [DllImport("user32.dll",EntryPoint="SetWindowLongPtrW",SetLastError=true)] static extern IntPtr SetWindowLongPtr(IntPtr h,int index,IntPtr value);
 delegate void WinEventProc(IntPtr hook,uint kind,IntPtr window,int objectId,int childId,uint thread,uint time);
 [DllImport("user32.dll")] static extern IntPtr SetWinEventHook(uint first,uint last,IntPtr module,WinEventProc callback,uint process,uint thread,uint flags);
 [DllImport("user32.dll")] static extern bool UnhookWinEvent(IntPtr hook);
 [StructLayout(LayoutKind.Sequential)]struct MSG {public IntPtr Window;public uint Message;public UIntPtr W;public IntPtr L;public uint Time;public POINT Point;public uint Private;}
 [DllImport("user32.dll")]static extern bool PeekMessage(out MSG message,IntPtr window,uint first,uint last,uint remove);
 [DllImport("user32.dll")]static extern bool TranslateMessage(ref MSG message);
 [DllImport("user32.dll")]static extern IntPtr DispatchMessage(ref MSG message);
 [DllImport("user32.dll")] static extern uint GetDoubleClickTime();
 [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h,ref POINT p);
 [DllImport("user32.dll")] static extern bool ScreenToClient(IntPtr h,ref POINT p);
 [DllImport("user32.dll")] static extern IntPtr ChildWindowFromPointEx(IntPtr h,POINT p,uint flags);
 [DllImport("user32.dll",SetLastError=true,CharSet=CharSet.Unicode)] static extern bool PostMessageW(IntPtr h,uint message,UIntPtr w,IntPtr l);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern uint MapVirtualKeyW(uint code,uint map);
 [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h,IntPtr dc,uint flags);
 [DllImport("user32.dll")] static extern bool GetWindowDisplayAffinity(IntPtr h,out uint affinity);
 [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr h);
 [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr h,IntPtr dc);
 [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h,int a,out int value,int size);
 [DllImport("dwmapi.dll",EntryPoint="DwmGetWindowAttribute")] static extern int DwmBounds(IntPtr h,int a,out RECT value,int size);
 [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr dc);
 [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleBitmap(IntPtr dc,int w,int h);
 [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr dc,IntPtr o);
 [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);
 [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr dc);
 [DllImport("gdi32.dll")] static extern int SetStretchBltMode(IntPtr dc,int mode);
 [DllImport("gdi32.dll")] static extern bool StretchBlt(IntPtr d,int x,int y,int w,int h,IntPtr s,int sx,int sy,int sw,int sh,uint rop);
 [DllImport("gdi32.dll")] static extern int GetDIBits(IntPtr dc,IntPtr b,uint start,uint rows,byte[] data,ref BI info,uint use);
 [DllImport("d3d11.dll")] static extern int D3D11CreateDevice(IntPtr adapter,int driver,IntPtr software,uint flags,IntPtr levels,uint count,uint sdk,out IntPtr device,out int level,out IntPtr context);
 [DllImport("d3d11.dll")] static extern int CreateDirect3D11DeviceFromDXGIDevice(IntPtr device,out IntPtr inspectable);
 [ComImport,Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface ItemInterop {
  IntPtr CreateForWindow(IntPtr window,ref Guid iid); IntPtr CreateForMonitor(IntPtr monitor,ref Guid iid);
 }
 [ComImport,Guid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface DxgiAccess { void GetInterface(ref Guid iid,out IntPtr value); }
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int CreateTexture(IntPtr self,ref DESC desc,IntPtr initial,out IntPtr texture);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate void GetDesc(IntPtr self,out DESC desc);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate void CopyRegion(IntPtr self,IntPtr dest,uint sub,uint x,uint y,uint z,IntPtr source,uint sourceSub,ref BOX region);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Map(IntPtr self,IntPtr resource,uint sub,uint type,uint flags,out MAPPED mapped);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate void Unmap(IntPtr self,IntPtr resource,uint sub);
 static T Method<T>(IntPtr self,int index)where T:class{return Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(Marshal.ReadIntPtr(self),index*IntPtr.Size),typeof(T))as T;}
 static readonly object OutputLock=new object(),InputLock=new object();static BinaryWriter Writer;static volatile bool Quitting;static long FrameNumber,lastActivity;static int CurrentBackend,PendingInputStatus,PendingInputFailure;
 sealed class OutputRecord {public int Status,W,H,CW,CH,Backend,Hash;public byte[] Bytes;public long Number,Time;}
 static readonly AutoResetEvent OutputReady=new AutoResetEvent(false);
 static OutputRecord PendingFrame,PendingSource,PendingStatus;static Thread OutputThread;static volatile bool OutputStopping;
 static readonly Stack<byte[]> PixelBuffers=new Stack<byte[]>();
 static byte[] RentPixels(int length){lock(PixelBuffers){while(PixelBuffers.Count!=0){byte[] bytes=PixelBuffers.Pop();if(bytes.Length==length)return bytes;}}return new byte[length];}
 static void ReturnPixels(byte[] bytes){if(bytes==null)return;lock(PixelBuffers){if(PixelBuffers.Count<2)PixelBuffers.Push(bytes);}}
 static void ReleaseRecord(OutputRecord record){if(record!=null&&(record.Status==1||record.Status==8)){ReturnPixels(record.Bytes);record.Bytes=null;}}
 // The pipe may block. Only this background writer owns it; capture and input
 // never wait for a slow consumer or retain compositor buffers while writing.
 static void StartOutput(){OutputStopping=false;OutputThread=new Thread(OutputLoop);OutputThread.IsBackground=true;OutputThread.Start();}
 static void StopOutput(){OutputStopping=true;OutputReady.Set();if(OutputThread!=null)OutputThread.Join(250);}
 static void OutputLoop(){try{while(true){OutputReady.WaitOne();OutputRecord source,status,frame;
   lock(OutputLock){source=PendingSource;PendingSource=null;status=PendingStatus;PendingStatus=null;frame=PendingFrame;PendingFrame=null;}
   if(source!=null)WriteRecord(source);if(status!=null)WriteRecord(status);
   FlushInputStatus((DateTime.UtcNow.Ticks-621355968000000000L)/10000);
   if(frame!=null)try{WriteRecord(frame);}finally{ReleaseRecord(frame);}Writer.Flush();
   if(OutputStopping){lock(OutputLock){if(PendingSource==null&&PendingStatus==null&&PendingFrame==null)break;}OutputReady.Set();}
  }}catch{Quitting=true;}finally{lock(OutputLock){ReleaseRecord(PendingFrame);PendingSource=PendingStatus=PendingFrame=null;}}}
 static OutputRecord Record(int status,int w,int h,int cw,int ch,byte[] bytes,long number,int hash,long time){return new OutputRecord{Status=status,W=w,H=h,CW=cw,CH=ch,Bytes=bytes,Number=number,Hash=hash,Time=time,Backend=CurrentBackend};}
 static IntPtr Selected;static int SelectedPid,OwnerPid;static bool AllowInput,SelectedMonitor;static string SelectedSourceId="";static readonly HashSet<int> HeldKeys=new HashSet<int>();static readonly Dictionary<int,IntPtr> HeldButtons=new Dictionary<int,IntPtr>(),HeldKeyTargets=new Dictionary<int,IntPtr>();static IntPtr KeyTarget,HoverTarget,LogicalFocus;static int LastX,LastY,ClickX,ClickY,ClickButton=-1;static IntPtr ClickTarget;static long ClickAt;
 sealed class ViewIdentity {public IntPtr Window;public int Pid;public uint Thread;public long Start;}
 sealed class ViewChanged:Exception{}
 const int MaxViews=128;const long NoActivate=0x08000000,Topmost=8;
 sealed class ViewState {public ViewIdentity Identity;public bool Visible,Guarded,AddedNoActivate,WasTopmost,PlacementDemoted;public IntPtr Previous;public long Evidence,Visited;}
 static ViewIdentity RootView,ActiveView;static int RootPid;static readonly Dictionary<IntPtr,ViewState> Views=new Dictionary<IntPtr,ViewState>();static readonly Dictionary<int,Process> FollowProcesses=new Dictionary<int,Process>();
 static HashSet<IntPtr> IntentBaseline=new HashSet<IntPtr>();static readonly Dictionary<uint,IntPtr> IntentActive=new Dictionary<uint,IntPtr>();static long IntentUntil,NextFollowCheck,EvidenceSerial,VisitSerial,ConsumedEvidence;static IntPtr IntentGameWindow,RecoveryGameWindow,LastMoveTarget;static int LastMoveX,LastMoveY;static uint LastMoveFlags;static Thread EventThread;static WinEventProc EventCallback;
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct PROCESSENTRY {public uint Size,Usage,Pid;public UIntPtr Heap;public uint Module,Threads,Parent;public int Priority;public uint Flags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]public string Name;}
 [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr CreateToolhelp32Snapshot(uint flags,uint pid);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool Process32FirstW(IntPtr snapshot,ref PROCESSENTRY entry);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool Process32NextW(IntPtr snapshot,ref PROCESSENTRY entry);
 [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
 static string Enc(string s){return Convert.ToBase64String(Encoding.UTF8.GetBytes(s));}
 static bool Cloaked(IntPtr h){int value;return DwmGetWindowAttribute(h,14,out value,4)==0&&value!=0;}
 static bool OwnerAlive(long pid){try{if(pid<=0)return false;using(Process process=Process.GetProcessById((int)pid))return !process.HasExited;}catch{return false;}}
 static void Dpi(){try{SetThreadDpiAwarenessContext(new IntPtr(-4));}catch(EntryPointNotFoundException){}}
 static bool WallpaperWindow(IntPtr window){StringBuilder kind=new StringBuilder(128);GetClassName(window,kind,kind.Capacity);return kind.ToString()=="Progman"||kind.ToString()=="WorkerW";}
 static string MonitorIdentity(string device){DISPLAYDEVICE info=new DISPLAYDEVICE{Size=Marshal.SizeOf(typeof(DISPLAYDEVICE))};return EnumDisplayDevicesW(device,0,ref info,1)&&!String.IsNullOrEmpty(info.Id)?device+"|"+info.Id:"";}
 static bool MonitorInfo(out MONITORINFOEX info){info=new MONITORINFOEX{Size=Marshal.SizeOf(typeof(MONITORINFOEX))};return SelectedMonitor&&SelectedSourceId.Length>0&&GetMonitorInfoW(Selected,ref info)&&String.Equals(MonitorIdentity(info.Device),SelectedSourceId,StringComparison.OrdinalIgnoreCase);}
 public static void List(long owner){Dpi();EnumDisplayMonitors(IntPtr.Zero,IntPtr.Zero,delegate(IntPtr monitor,IntPtr dc,ref RECT bounds,IntPtr data){MONITORINFOEX info=new MONITORINFOEX{Size=Marshal.SizeOf(typeof(MONITORINFOEX))};if(!GetMonitorInfoW(monitor,ref info))return true;
  string identity=MonitorIdentity(info.Device);if(identity.Length==0)return true;
  Console.WriteLine(monitor.ToInt64()+"\t0\t"+Enc(info.Device)+"\t"+Enc("Desktop 路 "+info.Device+" 路 "+(info.Monitor.R-info.Monitor.L)+"脳"+(info.Monitor.B-info.Monitor.T))+"\t0\tmonitor\t"+Enc(identity));return true;},IntPtr.Zero);
  EnumWindows(delegate(IntPtr h,IntPtr l){if(!IsWindowVisible(h)||Cloaked(h)||WallpaperWindow(h))return true;uint pid;GetWindowThreadProcessId(h,out pid);if(pid==owner||pid==Process.GetCurrentProcess().Id)return true;
  StringBuilder title=new StringBuilder(2048);GetWindowText(h,title,title.Capacity);string process="";try{process=Process.GetProcessById((int)pid).ProcessName;}catch{return true;}
  // Player windows and app-owned popup views often have no title. Expose those
  // separately, but do not turn desktop/system utility surfaces into sources.
  if(title.Length==0){if(GetWindow(h,4)==IntPtr.Zero)return true;RECT r;if(!GetClientRect(h,out r)||r.R<64||r.B<48)return true;StringBuilder kind=new StringBuilder(128);GetClassName(h,kind,kind.Capacity);title.Append(process+" 路 "+kind);}
  Console.WriteLine(h.ToInt64()+"\t"+pid+"\t"+Enc(process)+"\t"+Enc(title.ToString())+"\t"+(IsIconic(h)?"1":"0")+"\twindow\t");return true;},IntPtr.Zero);}
 static ViewIdentity Identity(IntPtr window){uint pid;uint thread=GetWindowThreadProcessId(window,out pid);if(window==IntPtr.Zero||thread==0||pid<=4||pid==OwnerPid||pid==Process.GetCurrentProcess().Id)return null;
  try{Process process;if(!FollowProcesses.TryGetValue((int)pid,out process)){if(FollowProcesses.Count>=256)return null;process=Process.GetProcessById((int)pid);IntPtr retained=process.Handle;FollowProcesses.Add((int)pid,process);}if(process.HasExited)return null;return new ViewIdentity{Window=window,Pid=(int)pid,Thread=thread,Start=process.StartTime.ToUniversalTime().Ticks};}catch{return null;}}
 static bool ValidView(ViewIdentity view){try{if(view==null||!IsWindow(view.Window))return false;uint pid;uint thread=GetWindowThreadProcessId(view.Window,out pid);Process process;return pid==view.Pid&&thread==view.Thread&&FollowProcesses.TryGetValue(view.Pid,out process)&&!process.HasExited&&process.StartTime.ToUniversalTime().Ticks==view.Start;}catch{return false;}}
 // Process-tree snapshots are expensive. Never take one per HWND while holding
 // the input lock: that delayed every click and starved the capture loop.
 static readonly object FamilyLock=new object();static HashSet<int> CachedFamily;static long FamilyUntil;static int FamilyRoot;
 static HashSet<int> AppFamily(){lock(FamilyLock){long now=Stopwatch.GetTimestamp();if(CachedFamily!=null&&FamilyRoot==RootPid&&now<FamilyUntil)return CachedFamily;CachedFamily=ReadAppFamily();FamilyRoot=RootPid;FamilyUntil=now+Stopwatch.Frequency/2;return CachedFamily;}}
 static HashSet<int> ReadAppFamily(){HashSet<int> found=new HashSet<int>();if(RootPid>4)found.Add(RootPid);IntPtr snapshot=CreateToolhelp32Snapshot(2,0);if(snapshot==new IntPtr(-1))return found;
  try{Dictionary<int,int> parents=new Dictionary<int,int>();PROCESSENTRY entry=new PROCESSENTRY{Size=(uint)Marshal.SizeOf(typeof(PROCESSENTRY))};if(Process32FirstW(snapshot,ref entry))do{parents[(int)entry.Pid]=(int)entry.Parent;}while(Process32NextW(snapshot,ref entry));bool again=true;while(again){again=false;foreach(KeyValuePair<int,int> pair in parents)if(found.Contains(pair.Value)&&found.Add(pair.Key))again=true;}return found;}finally{CloseHandle(snapshot);}}
 static ViewState Remember(IntPtr window){ViewState state;if(Views.TryGetValue(window,out state)){if(ValidView(state.Identity))return state;Views.Remove(window);}if(Views.Count>=MaxViews)return null;ViewIdentity identity=Identity(window);if(identity==null)return null;state=new ViewState{Identity=identity,Visible=IsWindowVisible(window)};Views.Add(window,state);return state;}
 static void InitializeFollow(IntPtr root,int pid){if(SelectedMonitor)return;RootPid=pid>4&&pid!=OwnerPid?pid:SelectedPid;RootView=Identity(root);if(RootView!=null&&RootView.Pid!=RootPid)RootView=null;if(RootView==null)RootPid=SelectedPid;ActiveView=Identity(Selected);if(RootView!=null)Remember(root);ViewState active=Remember(Selected);if(active!=null)active.Visited=++VisitSerial;}
 static bool OwnedByView(IntPtr window){for(int depth=0;depth<16;depth++){window=GetWindow(window,4);if(window==IntPtr.Zero)return false;ViewState state;if(RootView!=null&&window==RootView.Window&&ValidView(RootView)||Views.TryGetValue(window,out state)&&ValidView(state.Identity))return true;}return false;}
 static bool RelatedView(IntPtr window,bool inputEvidence){ViewState state;if(Views.TryGetValue(window,out state)&&ValidView(state.Identity)||OwnedByView(window))return true;if(!inputEvidence)return false;uint pid;GetWindowThreadProcessId(window,out pid);
  // CEF/Electron players create independent top-level windows on a different UI
  // thread in the SAME process. Thread equality excluded precisely those views.
  if(RootView!=null&&ValidView(RootView)&&pid==RootView.Pid)return true;return pid!=RootPid&&AppFamily().Contains((int)pid);}
 static bool ViewRoot(IntPtr window){if(window==IntPtr.Zero||!IsWindow(window)||GetAncestor(window,2)!=window||WallpaperWindow(window))return false;
  uint pid;GetWindowThreadProcessId(window,out pid);if(pid<=4||pid==OwnerPid||pid==Process.GetCurrentProcess().Id)return false;StringBuilder kind=new StringBuilder(128);GetClassName(window,kind,kind.Capacity);string type=kind.ToString();return type!="#32768"&&type!="tooltips_class32"&&type!="IME"&&type!="MSCTFIME UI";}
 static bool ViewSurface(IntPtr window,bool visible){if(!ViewRoot(window)||visible&&(!IsWindowVisible(window)||IsIconic(window)||Cloaked(window)))return false;RECT rect;return GetClientRect(window,out rect)&&rect.R>=64&&rect.B>=48&&rect.R<=MaxEdge&&rect.B<=MaxEdge&&(long)rect.R*rect.B<=MaxPixels;}
 static bool IntentGameValid(){uint pid;return IntentGameWindow!=IntPtr.Zero&&IsWindow(IntentGameWindow)&&GetWindowThreadProcessId(IntentGameWindow,out pid)!=0&&pid==OwnerPid;}
 static void Guard(ViewState state){if(state==null||!ValidView(state.Identity)||!IntentGameValid())return;IntPtr window=state.Identity.Window;if(!GameForeground()&&GetForegroundWindow()!=window)return;long style=GetWindowLongPtr(window,-20).ToInt64();
  if(!state.Guarded){state.Previous=GetWindow(window,3);state.WasTopmost=(style&Topmost)!=0;state.AddedNoActivate=(style&NoActivate)==0;state.Guarded=true;if(state.AddedNoActivate)SetWindowLongPtr(window,-20,new IntPtr(style|NoActivate));}
  PlaceBehindGame(window,IntentGameWindow);
 }
 // Keep the source immediately below the game, not below every desktop window.
 // Chromium considers other opaque windows occluders even when the game is layered.
 static void PlaceBehindGame(IntPtr window,IntPtr game){
  if(window==IntPtr.Zero||game==IntPtr.Zero||window==game||!IsWindow(game))return;
  long gameStyle=GetWindowLongPtr(game,-20).ToInt64(),sourceStyle=GetWindowLongPtr(window,-20).ToInt64();
  if((sourceStyle&Topmost)!=0){ViewState state=Remember(window);if(state!=null)state.PlacementDemoted=true;}
  if((gameStyle&Topmost)!=0){
   // HWND_NOTOPMOST puts it at the top of the ordinary band without inheriting
   // the game's topmost bit. Never insert directly after a topmost HWND.
   IntPtr previous=GetWindow(window,3);
   if((sourceStyle&Topmost)!=0||previous!=IntPtr.Zero&&(GetWindowLongPtr(previous,-20).ToInt64()&Topmost)==0)
    SetWindowPos(window,new IntPtr(-2),0,0,0,0,0x213);
  }else if(GetWindow(window,3)!=game||(sourceStyle&Topmost)!=0){
   if((sourceStyle&Topmost)!=0)SetWindowPos(window,new IntPtr(-2),0,0,0,0,0x213);
   SetWindowPos(window,game,0,0,0,0,0x213);
  }
 }
 static long NextPlacement;
 static void KeepSourceBehindGame(){
  if(SelectedMonitor)return;long now=Stopwatch.GetTimestamp();if(now<NextPlacement)return;NextPlacement=now+Stopwatch.Frequency/4;
  IntPtr game=GetForegroundWindow();uint pid;if(game==IntPtr.Zero||GetWindowThreadProcessId(game,out pid)==0||pid!=OwnerPid||!ValidView(ActiveView)||ActiveView.Window!=Selected||!ViewSurface(Selected,true))return;
  // No activation, resizing, cursor warping, or changes while the user is in another app.
  lock(InputLock){PlaceBehindGame(Selected,game);}
 }
 static void RestorePlacement(){lock(InputLock){foreach(ViewState state in Views.Values){if(!state.PlacementDemoted||!ValidView(state.Identity))continue;state.PlacementDemoted=false;SetWindowPos(state.Identity.Window,new IntPtr(-1),0,0,0,0,0x213);}}}
 static void RestoreGuards(){lock(InputLock){foreach(ViewState state in Views.Values){if(!state.Guarded||!ValidView(state.Identity))continue;IntPtr window=state.Identity.Window;long style=GetWindowLongPtr(window,-20).ToInt64();if(state.AddedNoActivate&&(style&NoActivate)!=0)SetWindowLongPtr(window,-20,new IntPtr(style&~NoActivate));
    bool playing=GameForeground();IntPtr after=state.WasTopmost?new IntPtr(-1):new IntPtr(-2);if(!playing&&state.Previous!=IntPtr.Zero&&IsWindow(state.Previous)&&((GetWindowLongPtr(state.Previous,-20).ToInt64()&Topmost)!=0)==state.WasTopmost)after=state.Previous;
    // An expired input guard must not lift an ordinary source above the game
    // by restoring its obsolete position in the desktop's stacking order.
    SetWindowPos(window,after,0,0,0,0,playing&&!state.WasTopmost?0x237u:0x233u);state.Guarded=false;}}}
 static void SnapshotIntent(){IntentBaseline.Clear();IntentActive.Clear();EnumWindows(delegate(IntPtr window,IntPtr ignored){if(IsWindowVisible(window))IntentBaseline.Add(window);
   // Snapshot sibling UI threads too, without adopting independently projected
   // windows. A later activation can then be distinguished from a stale view.
   if(RelatedView(window,true)){uint pid;uint thread=GetWindowThreadProcessId(window,out pid);GUIINFO info=new GUIINFO{Size=Marshal.SizeOf(typeof(GUIINFO))};if(thread!=0&&!IntentActive.ContainsKey(thread)&&GetGUIThreadInfo(thread,ref info))IntentActive[thread]=info.Active;}return true;},IntPtr.Zero);foreach(ViewState state in Views.Values)state.Visible=IsWindowVisible(state.Identity.Window);}
 static void ArmFollow(){if(!AllowInput||SelectedMonitor)return;IntentGameWindow=GameForeground()?GetForegroundWindow():IntPtr.Zero;IntentUntil=Stopwatch.GetTimestamp()+Stopwatch.Frequency*5;ConsumedEvidence=EvidenceSerial;Guard(Remember(Selected));SnapshotIntent();NextFollowCheck=0;}
 static void WindowEvent(IntPtr hook,uint kind,IntPtr window,int objectId,int childId,uint thread,uint time){try{lock(InputLock){if(Quitting||Stopwatch.GetTimestamp()>=IntentUntil||!IntentGameValid()||childId!=0||objectId!=0&&(kind!=0x8005||objectId!=-4))return;if(kind==0x8005)window=GetAncestor(window,2);bool evidence=kind==0x8002||kind==0x8005||kind==3;
    if(!RelatedView(window,!IntentBaseline.Contains(window)||evidence))return;
    // CREATE can arrive before the client rectangle has its final size. Protect
    // its stacking/activation before SHOW, then require full geometry to capture.
    if(kind==0x8000&&ViewRoot(window))Guard(Remember(window));
    if(!ViewSurface(window,false))return;
    // Show/focus/foreground are evidence even for an already existing view.
    // Keep the intent alive across loading windows and subsequent replacements.
    ViewState state=Remember(window);if(state==null)return;if(kind==0x8002||kind==0x8005||kind==3)state.Evidence=++EvidenceSerial;if(kind==0x8000||kind==0x8002||kind==3)Guard(state);NextFollowCheck=0;}}
   catch{} }
 static void StartEvents(){if(!AllowInput||SelectedMonitor)return;EventCallback=WindowEvent;ManualResetEvent ready=new ManualResetEvent(false);EventThread=new Thread(delegate(){Dpi();IntPtr objects=IntPtr.Zero,foreground=IntPtr.Zero;try{objects=SetWinEventHook(0x8000,0x8005,IntPtr.Zero,EventCallback,0,0,2);foreground=SetWinEventHook(3,3,IntPtr.Zero,EventCallback,0,0,2);ready.Set();while(!Quitting){MSG message;while(PeekMessage(out message,IntPtr.Zero,0,0,1)){TranslateMessage(ref message);DispatchMessage(ref message);}Thread.Sleep(5);}}finally{if(objects!=IntPtr.Zero)UnhookWinEvent(objects);if(foreground!=IntPtr.Zero)UnhookWinEvent(foreground);ready.Set();}});EventThread.IsBackground=true;EventThread.Start();ready.WaitOne(2000);}
 static bool FollowWindow(){if(SelectedMonitor||!AllowInput)return false;long now=Stopwatch.GetTimestamp();if(now<NextFollowCheck&&ValidView(ActiveView)&&IsWindowVisible(Selected))return false;NextFollowCheck=now+Stopwatch.Frequency/50;bool changed=false;
  lock(InputLock){RecoveryGameWindow=IntPtr.Zero;List<IntPtr> dead=new List<IntPtr>();foreach(KeyValuePair<IntPtr,ViewState> pair in Views)if(!ValidView(pair.Value.Identity))dead.Add(pair.Key);foreach(IntPtr window in dead)Views.Remove(window);
   bool intent=now<IntentUntil,live=ValidView(ActiveView)&&ViewSurface(Selected,true);if(!intent&&IntentUntil!=0){IntentUntil=0;RestoreGuards();}ViewState candidate=null;long best=ConsumedEvidence;
   if(intent&&HeldButtons.Count==0&&HeldKeys.Count==0){EnumWindows(delegate(IntPtr window,IntPtr ignored){if(!ViewSurface(window,true)||!RelatedView(window,true))return true;ViewState state=Remember(window);if(state==null)return true;
     if(!IntentBaseline.Contains(window)||!state.Visible){if(state.Evidence<=ConsumedEvidence)state.Evidence=++EvidenceSerial;IntentBaseline.Add(window);}state.Visible=true;
     GUIINFO info=new GUIINFO{Size=Marshal.SizeOf(typeof(GUIINFO))};IntPtr was;if(GetGUIThreadInfo(state.Identity.Thread,ref info)&&IntentActive.TryGetValue(state.Identity.Thread,out was)&&info.Active==window&&info.Active!=was){state.Evidence=++EvidenceSerial;IntentActive[state.Identity.Thread]=info.Active;}
     if(window!=Selected&&state.Evidence>best){candidate=state;best=state.Evidence;}return true;},IntPtr.Zero);}
   if(candidate==null&&!live){long recent=-1;foreach(ViewState state in Views.Values)if(ViewSurface(state.Identity.Window,true)&&state.Visited>recent){candidate=state;recent=state.Visited;}}
   if(candidate!=null&&candidate.Identity.Window!=Selected){Release();ActiveView=candidate.Identity;Selected=ActiveView.Window;SelectedPid=ActiveView.Pid;candidate.Visited=++VisitSerial;ConsumedEvidence=EvidenceSerial;Guard(candidate);if(GetForegroundWindow()==Selected&&IntentGameValid())RecoveryGameWindow=IntentGameWindow;changed=true;}
   foreach(ViewState state in Views.Values)state.Visible=IsWindowVisible(state.Identity.Window);
  }if(changed)SourcePacket();return changed;
 }
 static void WriteRecord(OutputRecord r){Writer.Write(0x33534341);Writer.Write(r.Status);Writer.Write(r.W);Writer.Write(r.H);Writer.Write(r.CW);Writer.Write(r.CH);Writer.Write(r.Bytes==null?0:r.Bytes.Length);Writer.Write(r.Backend);Writer.Write(r.Hash);Writer.Write(r.Number);Writer.Write(r.Time);if(r.Bytes!=null)Writer.Write(r.Bytes);}
 static void FlushInputStatus(long time){int failure=Interlocked.Exchange(ref PendingInputFailure,0),status=Interlocked.Exchange(ref PendingInputStatus,0);if(failure!=0)status=failure;if(status!=0)WriteRecord(Record(status,0,0,0,0,null,0,0,time));}
 static void SourcePacket(){StringBuilder title=new StringBuilder(2048);GetWindowText(Selected,title,title.Capacity);string process="";try{process=FollowProcesses[SelectedPid].ProcessName;}catch{}if(title.Length==0)title.Append(process);
  byte[] bytes=Encoding.UTF8.GetBytes(SelectedPid+"\t"+Enc(process)+"\t"+Enc(title.ToString())+"\t"+RecoveryGameWindow.ToInt64());long time=(DateTime.UtcNow.Ticks-621355968000000000L)/10000;
  lock(OutputLock){ReleaseRecord(PendingFrame);PendingFrame=PendingStatus=null;PendingSource=Record(20,0,0,0,0,bytes,Selected.ToInt64(),0,time);}OutputReady.Set();}
 static int Hash(byte[] bytes){unchecked{uint hash=2166136261;for(int i=0;i<bytes.Length;i+=Math.Max(4,(bytes.Length/32768/4)*4)){hash=(hash^bytes[i])*16777619;hash=(hash^bytes[Math.Min(i+1,bytes.Length-1)])*16777619;hash=(hash^bytes[Math.Min(i+2,bytes.Length-1)])*16777619;}return(int)hash;}}
 static void Packet(int status,int w,int h,int cw,int ch,byte[] bytes){
  if(status>=100){if(status>100&&status!=103)Interlocked.Exchange(ref PendingInputFailure,status);else Interlocked.Exchange(ref PendingInputStatus,status);OutputReady.Set();return;}
  long number=bytes==null?0:Interlocked.Increment(ref FrameNumber);int hash=bytes==null?0:Hash(bytes);long time=(DateTime.UtcNow.Ticks-621355968000000000L)/10000;
  OutputRecord record=Record(status,w,h,cw,ch,bytes,number,hash,time);
  lock(OutputLock){if(bytes!=null){ReleaseRecord(PendingFrame);PendingFrame=record;PendingStatus=null;}else PendingStatus=record;}OutputReady.Set();
 }
 static int State(){uint pid;uint affinity;RECT rect;if(SelectedMonitor){MONITORINFOEX info;if(!MonitorInfo(out info))return 3;rect=info.Monitor;int width=rect.R-rect.L,height=rect.B-rect.T;return width<2||height<2||width>MaxEdge||height>MaxEdge||(long)width*height>MaxPixels?6:0;}
  if(!IsWindow(Selected)||WallpaperWindow(Selected))return 3;GetWindowThreadProcessId(Selected,out pid);if(pid!=SelectedPid)return 3;
  if(IsIconic(Selected))return 2;if(Cloaked(Selected))return 4;if(GetWindowDisplayAffinity(Selected,out affinity)&&affinity!=0)return 5;
  if(!GetClientRect(Selected,out rect)||rect.R-rect.L<2||rect.B-rect.T<2||rect.R-rect.L>MaxEdge||rect.B-rect.T>MaxEdge||(long)(rect.R-rect.L)*(rect.B-rect.T)>MaxPixels)return 6;return 0;}
 static void Dimensions(int sourceW,int sourceH,int edge,int pixels,bool native,out int w,out int h){if(native){w=sourceW;h=sourceH;return;}double scale=Math.Min(1,Math.Min(edge/(double)Math.Max(sourceW,sourceH),Math.Sqrt(pixels/(double)((long)sourceW*sourceH))));w=Math.Max(2,(int)Math.Floor(sourceW*scale));h=Math.Max(2,(int)Math.Floor(sourceH*scale));}
 static int Delay(int w,int h,int fps){return fps==0?1:Math.Max(1,1000/Math.Max(1,fps));}
 static bool ValidTarget(IntPtr target){uint pid;if(SelectedMonitor||target==IntPtr.Zero||!IsWindow(target)||(target!=Selected&&!IsChild(Selected,target)))return false;GetWindowThreadProcessId(target,out pid);return pid!=0&&pid!=OwnerPid&&pid!=Process.GetCurrentProcess().Id;}
 static bool GameForeground(){uint pid;IntPtr foreground=GetForegroundWindow();return foreground!=IntPtr.Zero&&GetWindowThreadProcessId(foreground,out pid)!=0&&pid==OwnerPid;}
 static string WindowClass(IntPtr window){StringBuilder name=new StringBuilder(128);GetClassName(window,name,name.Capacity);return name.ToString();}
 static bool ChromiumInputHost(IntPtr window){return WindowClass(window)=="Chrome_RenderWidgetHostHWND";}
 static IntPtr RendererAtPoint(IntPtr branch,POINT screen){
  // Chromium deliberately marks its legacy input HWND WS_EX_TRANSPARENT. It
  // still handles mouse and wheel messages. Generic CWP_SKIPTRANSPARENT hit
  // testing skips that real receiver and can hit a video-only D3D sibling.
  // Permit only the known input class, inside the selected app and point.
  IntPtr found=IntPtr.Zero;int visited=0;
  EnumChildWindows(branch,delegate(IntPtr child,IntPtr ignored){
   if(++visited>256)return false;
   if(!ValidTarget(child)||!IsWindowVisible(child)||!IsWindowEnabled(child)||!ChromiumInputHost(child))return true;
   POINT local=screen;RECT rect;if(!ScreenToClient(child,ref local)||!GetClientRect(child,out rect)||local.X<rect.L||local.Y<rect.T||local.X>=rect.R||local.Y>=rect.B)return true;
   found=child;return false;
  },IntPtr.Zero);return found;
 }
 static IntPtr Target(ref POINT point){
  POINT screen=point;if(!ClientToScreen(Selected,ref screen))return Selected;
  IntPtr current=Selected;int budget=384;
  // Walk siblings in Z order. D3D presenters are visual-only; do not stop at
  // one or search an entire subtree ahead of an overlapping real control.
  for(int depth=0;depth<16&&budget>0;depth++){
   IntPtr chosen=IntPtr.Zero;
   for(IntPtr child=GetWindow(current,5);child!=IntPtr.Zero&&budget-->0;child=GetWindow(child,2)){
    if(!ValidTarget(child)||!IsWindowVisible(child)||!IsWindowEnabled(child))continue;
    POINT local=screen;RECT rect;if(!ScreenToClient(child,ref local)||!GetClientRect(child,out rect)||local.X<rect.L||local.Y<rect.T||local.X>=rect.R||local.Y>=rect.B)continue;
    string kind=WindowClass(child);if(kind=="Intermediate D3D Window")continue;
    bool transparent=(GetWindowLongPtr(child,-20).ToInt64()&0x20)!=0;
    if(transparent&&kind!="Chrome_RenderWidgetHostHWND"&&!kind.StartsWith("CefBrowser",StringComparison.Ordinal))continue;
    chosen=child;break;
   }
   if(chosen==IntPtr.Zero)break;current=chosen;
  }
  point=screen;ScreenToClient(current,ref point);return current;
 }
 static IntPtr Coords(int x,int y){return new IntPtr(unchecked((int)(((uint)(ushort)y<<16)|(ushort)x)));}
 static uint Flags(){uint flags=0;foreach(int button in HeldButtons.Keys)flags|=button==0?1u:button==1?2u:button==2?16u:button==3?32u:64u;if(HeldKeys.Contains(0xA0)||HeldKeys.Contains(0xA1)||HeldKeys.Contains(0x10))flags|=4;if(HeldKeys.Contains(0xA2)||HeldKeys.Contains(0xA3)||HeldKeys.Contains(0x11))flags|=8;return flags;}
 static bool Post(IntPtr target,uint message,uint w,IntPtr l){return ValidTarget(target)&&PostMessageW(target,message,new UIntPtr(w),l);}
 static uint Scan(int vk){return MapVirtualKeyW((uint)vk,4);}
 static IntPtr KeyBits(int vk,bool up,bool repeat){uint scan=Scan(vk);uint bits=1|((scan&255)<<16);if((scan&0xFF00)!=0)bits|=1u<<24;if(HeldKeys.Contains(0xA4)||HeldKeys.Contains(0xA5))bits|=1u<<29;if(repeat||up)bits|=1u<<30;if(up)bits|=1u<<31;return new IntPtr(unchecked((int)bits));}
 static bool IsAlt(int vk){return vk==0xA4||vk==0xA5||HeldKeys.Contains(0xA4)||HeldKeys.Contains(0xA5);}
 static void FocusView(IntPtr target){
  if(!ValidTarget(target)||LogicalFocus==target)return;
  // These are view notifications only: no SetFocus, AttachThreadInput,
  // SetForegroundWindow or system input is used by background mode.
  if(ValidTarget(LogicalFocus))Post(LogicalFocus,0x8,0,IntPtr.Zero);
  LogicalFocus=target;Post(target,0x7,0,IntPtr.Zero);
 }
 static IntPtr KeyboardTarget(){
  uint pid;uint thread=GetWindowThreadProcessId(Selected,out pid);GUIINFO info=new GUIINFO{Size=Marshal.SizeOf(typeof(GUIINFO))};
  if(thread!=0&&GetGUIThreadInfo(thread,ref info)&&ValidTarget(info.Focus))return info.Focus;
  if(ValidTarget(KeyTarget))return KeyTarget;
  if(ValidTarget(LogicalFocus))return LogicalFocus;return Selected;
 }
 static void Release(){
  LastMoveTarget=IntPtr.Zero;
  CheatProgramWindowScroll.Clear();
  if(HoverTarget!=IntPtr.Zero)Post(HoverTarget,0x2A3,0,IntPtr.Zero);HoverTarget=IntPtr.Zero;
  foreach(KeyValuePair<int,IntPtr> button in new List<KeyValuePair<int,IntPtr>>(HeldButtons)){int b=button.Key;uint msg=b==0?0x202u:b==1?0x205u:b==2?0x208u:0x20Cu;Post(button.Value,msg,b>=3?(uint)((b-2)<<16):0,Coords(LastX,LastY));}HeldButtons.Clear();
  foreach(int key in new List<int>(HeldKeys)){IntPtr target;Post(HeldKeyTargets.TryGetValue(key,out target)?target:KeyTarget,IsAlt(key)?0x105u:0x101u,(uint)key,KeyBits(key,true,true));}HeldKeys.Clear();HeldKeyTargets.Clear();KeyTarget=IntPtr.Zero;
  if(ValidTarget(LogicalFocus))Post(LogicalFocus,0x8,0,IntPtr.Zero);LogicalFocus=IntPtr.Zero;ClickButton=-1;
 }
 static int Number(string text,int min,int max){int value;if(!Int32.TryParse(text,out value)||value<min||value>max)throw new ArgumentException("Input limit");return value;}
 static string Line(){StringBuilder line=new StringBuilder(96);int ch;while((ch=Console.In.Read())!=-1){if(ch=='\n')return line.ToString().TrimEnd('\r');if(line.Length>=48000)throw new ArgumentException("Input command too long");line.Append((char)ch);}return line.Length==0?null:line.ToString();}
 static void InputLoop(){Dpi();try{while(!Quitting){string line=Line();if(line==null)break;lock(InputLock){if(line=="Q"){Release();Quitting=true;RestoreGuards();break;}if(line=="F"){IntentUntil=0;IntentBaseline.Clear();RestoreGuards();continue;}if(line=="R"){
     // Releasing the interaction key must release input immediately, but a page
     // opened by its preceding click may still be loading. F/Q explicitly cancel
     // that bounded follow intent; no further input is sent while unfocused.
     Release();Packet(103,0,0,0,0,null);continue;}if(!AllowInput||State()!=0){Release();Packet(102,0,0,0,0,null);continue;}if(!GameForeground()){Release();Packet(106,0,0,0,0,null);continue;}
   try{string[] p=line.Split('\t');bool sent=false;
    if(p[0]=="M"&&p.Length==3||p[0]=="B"&&p.Length==5||p[0]=="W"&&p.Length==5){int offset=p[0]=="M"?1:3;RECT rect;GetClientRect(Selected,out rect);int x=Number(p[offset],0,MaxEdge-1),y=Number(p[offset+1],0,MaxEdge-1);if(x>=rect.R||y>=rect.B)throw new ArgumentException("Outside client");POINT point=new POINT(x,y);IntPtr target=HeldButtons.Count>0?FirstButton():Target(ref point);if(HeldButtons.Count>0){POINT screen=new POINT(x,y);ClientToScreen(Selected,ref screen);ScreenToClient(target,ref screen);point=screen;}LastX=point.X;LastY=point.Y;
     if(p[0]=="M"){uint flags=Flags();if(LastMoveTarget==target&&LastMoveX==point.X&&LastMoveY==point.Y&&LastMoveFlags==flags)sent=true;else{if(HoverTarget!=target){if(HoverTarget!=IntPtr.Zero)Post(HoverTarget,0x2A3,0,IntPtr.Zero);HoverTarget=target;}sent=Post(target,0x200,flags,Coords(point.X,point.Y));LastMoveTarget=target;LastMoveX=point.X;LastMoveY=point.Y;LastMoveFlags=flags;}}
     else if(p[0]=="B"){int b=Number(p[1],0,4);bool down=Number(p[2],0,1)==1;uint message=b==0?(down?0x201u:0x202u):b==1?(down?0x204u:0x205u):b==2?(down?0x207u:0x208u):(down?0x20Bu:0x20Cu);
      // Deliver the exact final move while the previous button state is still
      // held. Some drag handlers need it before the subsequent button-up.
      sent=Post(target,0x200,Flags(),Coords(point.X,point.Y));
      LastMoveTarget=IntPtr.Zero;
      if(down){ArmFollow();CheatProgramWindowScroll.Clear();FocusView(target);HeldButtons[b]=target;KeyTarget=target;long now=DateTime.UtcNow.Ticks;
       if(b==ClickButton&&target==ClickTarget&&Math.Abs(point.X-ClickX)<=4&&Math.Abs(point.Y-ClickY)<=4&&(now-ClickAt)/TimeSpan.TicksPerMillisecond<=GetDoubleClickTime()){message=b==0?0x203u:b==1?0x206u:b==2?0x209u:0x20Du;ClickButton=-1;}
       else{ClickButton=b;ClickTarget=target;ClickX=point.X;ClickY=point.Y;ClickAt=now;}
      }else{IntPtr held;if(HeldButtons.TryGetValue(b,out held))target=held;HeldButtons.Remove(b);}uint flags=Flags();if(b>=3)flags|=(uint)((b-2)<<16);sent&=Post(target,message,flags,Coords(point.X,point.Y));}
     else{LastMoveTarget=IntPtr.Zero;int sx=Number(p[1],-12000,12000),sy=Number(p[2],-12000,12000);POINT screen=new POINT(x,y);ClientToScreen(Selected,ref screen);
       // Scroll providers smooth fractional offsets; native wheel fallback is
       // handled by the same queue for ordinary controls without a scroll pattern.
       // Neither path moves the real cursor or changes foreground focus.
       bool queued=CheatProgramWindowScroll.Queue(Selected,SelectedPid,target,sx,sy,screen.X,screen.Y,Flags(),
        delegate{return !Quitting&&AllowInput&&State()==0&&GameForeground();},delegate(bool ok){if(!Quitting)Packet(ok?100:GameForeground()?105:106,0,0,0,0,null);});
       if(!queued)Packet(105,0,0,0,0,null);continue;
     }
    }else if(p[0]=="K"&&p.Length==4){int vk=Number(p[1],1,255);bool down=Number(p[2],0,1)==1,repeat=Number(p[3],0,1)==1;if(down){if(!repeat&&(vk==13||vk==32||vk==0x73||(HeldKeys.Contains(0x11)||HeldKeys.Contains(0xA2)||HeldKeys.Contains(0xA3))&&(vk==78||vk==79)))ArmFollow();CheatProgramWindowScroll.Clear();}IntPtr heldTarget;KeyTarget=HeldKeyTargets.TryGetValue(vk,out heldTarget)?heldTarget:KeyboardTarget();FocusView(KeyTarget);bool alt=IsAlt(vk);IntPtr bits=KeyBits(vk,!down,repeat||HeldKeys.Contains(vk));if(down){HeldKeys.Add(vk);HeldKeyTargets[vk]=KeyTarget;}else{HeldKeys.Remove(vk);HeldKeyTargets.Remove(vk);}sent=Post(KeyTarget,alt?(down?0x104u:0x105u):(down?0x100u:0x101u),(uint)vk,bits);
    }else if(p[0]=="C"&&p.Length==2){int ch=Number(p[1],32,65535);KeyTarget=KeyboardTarget();FocusView(KeyTarget);sent=Post(KeyTarget,0x102,(uint)ch,new IntPtr(1));}
    else if(p[0]=="T"&&p.Length==2){byte[] utf8=Convert.FromBase64String(p[1]);string text=new UTF8Encoding(false,true).GetString(utf8);if(text.Length>8192)throw new ArgumentException("Text limit");KeyTarget=KeyboardTarget();FocusView(KeyTarget);sent=true;foreach(char ch in text){if(ch=='\r'||ch=='\n'||ch=='\t'||ch>=32&&ch!=127)sent&=Post(KeyTarget,0x102,(uint)(ch=='\n'?'\r':ch),new IntPtr(1));}}
    else throw new ArgumentException("Unknown input");Packet(sent?100:101,0,0,0,0,null);
   }catch(ArgumentException){Release();Packet(102,0,0,0,0,null);}
   }}}catch{}finally{lock(InputLock){Release();Quitting=true;RestoreGuards();}}}
 static IntPtr FirstButton(){foreach(IntPtr value in HeldButtons.Values)return value;return Selected;}
 static void Gdi(int edge,int maxPixels,int fps,bool native,long owner,int retryAfterMillis){CurrentBackend=1;Stopwatch fallback=Stopwatch.StartNew();while(!Quitting&&OwnerAlive(owner)&&(retryAfterMillis<=0||fallback.ElapsedMilliseconds<retryAfterMillis)){
  Interlocked.Exchange(ref lastActivity,Environment.TickCount);KeepSourceBehindGame();FollowWindow();Stopwatch tick=Stopwatch.StartNew();int state=State();if(state!=0){Packet(state,0,0,0,0,null);if(state==3)break;Thread.Sleep(400);continue;}RECT rect;GetClientRect(Selected,out rect);int sw=rect.R,sh=rect.B,w,h;Dimensions(sw,sh,edge,maxPixels,native,out w,out h);
  IntPtr screen=IntPtr.Zero,source=IntPtr.Zero,target=IntPtr.Zero,srcBmp=IntPtr.Zero,dstBmp=IntPtr.Zero,oldSrc=IntPtr.Zero,oldDst=IntPtr.Zero;
  try{screen=GetDC(IntPtr.Zero);source=CreateCompatibleDC(screen);srcBmp=CreateCompatibleBitmap(screen,sw,sh);if(screen==IntPtr.Zero||source==IntPtr.Zero||srcBmp==IntPtr.Zero)throw new IOException("GDI allocation");oldSrc=SelectObject(source,srcBmp);
   if(!PrintWindow(Selected,source,3)){Packet(7,0,0,0,0,null);Thread.Sleep(250);continue;}
   IntPtr bitmap=srcBmp,dc=source;if(w!=sw||h!=sh){target=CreateCompatibleDC(screen);dstBmp=CreateCompatibleBitmap(screen,w,h);if(target==IntPtr.Zero||dstBmp==IntPtr.Zero)throw new IOException("GDI resize allocation");oldDst=SelectObject(target,dstBmp);SetStretchBltMode(target,4);if(!StretchBlt(target,0,0,w,h,source,0,0,sw,sh,0x00CC0020))throw new IOException("GDI resize");SelectObject(target,oldDst);oldDst=IntPtr.Zero;bitmap=dstBmp;dc=target;}else{SelectObject(source,oldSrc);oldSrc=IntPtr.Zero;}
   byte[] pixels=new byte[checked(w*h*4)];BI info=new BI();info.Size=(uint)Marshal.SizeOf(typeof(BI));info.Width=w;info.Height=-h;info.Planes=1;info.Bits=32;
   if(GetDIBits(dc,bitmap,0,(uint)h,pixels,ref info,0)==0)throw new IOException("GDI pixels");bool black=true;for(int i=0;i<pixels.Length;i+=4){pixels[i+3]=255;if(pixels[i]!=0||pixels[i+1]!=0||pixels[i+2]!=0)black=false;}Packet(black?8:1,w,h,sw,sh,pixels);
  }finally{if(oldSrc!=IntPtr.Zero)SelectObject(source,oldSrc);if(oldDst!=IntPtr.Zero)SelectObject(target,oldDst);if(srcBmp!=IntPtr.Zero)DeleteObject(srcBmp);if(dstBmp!=IntPtr.Zero)DeleteObject(dstBmp);if(source!=IntPtr.Zero)DeleteDC(source);if(target!=IntPtr.Zero)DeleteDC(target);if(screen!=IntPtr.Zero)ReleaseDC(IntPtr.Zero,screen);}
  Thread.Sleep(Math.Max(1,Delay(w,h,fps)-(int)tick.ElapsedMilliseconds));
 }}
 sealed class Wgc:IDisposable {
  public string Stage="initialize";IntPtr device,context,staging;IDirect3DDevice projected;Direct3D11CaptureFramePool pool;GraphicsCaptureSession session;GraphicsCaptureItem item;AutoResetEvent arrived=new AutoResetEvent(false);int stagingW,stagingH;SizeInt32 poolSize;
  public Wgc(){try{Stage="is-supported";if(!GraphicsCaptureSession.IsSupported())throw new NotSupportedException("WGC unavailable");int level;Stage="create-device";Marshal.ThrowExceptionForHR(D3D11CreateDevice(IntPtr.Zero,1,IntPtr.Zero,0x20,IntPtr.Zero,0,7,out device,out level,out context));
   Stage="project-device";IntPtr dxgi=IntPtr.Zero,inspectable=IntPtr.Zero;Guid dxgiId=new Guid("54EC77FA-1377-44E6-8C32-88FD5F44C84C");try{Marshal.ThrowExceptionForHR(Marshal.QueryInterface(device,ref dxgiId,out dxgi));Marshal.ThrowExceptionForHR(CreateDirect3D11DeviceFromDXGIDevice(dxgi,out inspectable));projected=(IDirect3DDevice)Marshal.GetObjectForIUnknown(inspectable);}finally{if(dxgi!=IntPtr.Zero)Marshal.Release(dxgi);if(inspectable!=IntPtr.Zero)Marshal.Release(inspectable);}
   Stage="capture-item-factory";ItemInterop factory=(ItemInterop)WindowsRuntimeMarshal.GetActivationFactory(typeof(GraphicsCaptureItem));Guid itemId=new Guid("79C3F95B-31F7-4EC2-A464-632EF5D30760");Stage=SelectedMonitor?"create-monitor-item":"create-window-item";IntPtr pointer=SelectedMonitor?factory.CreateForMonitor(Selected,ref itemId):factory.CreateForWindow(Selected,ref itemId);try{item=(GraphicsCaptureItem)Marshal.GetObjectForIUnknown(pointer);}finally{Marshal.Release(pointer);}
   Stage="allocate-framepool";poolSize=item.Size;if(poolSize.Width<2||poolSize.Height<2||poolSize.Width>MaxEdge+128||poolSize.Height>MaxEdge+128)throw new IOException("WGC dimensions");pool=Direct3D11CaptureFramePool.CreateFreeThreaded(projected,DirectXPixelFormat.B8G8R8A8UIntNormalized,2,poolSize);
   Stage="framepool-event";pool.FrameArrived+=delegate(Direct3D11CaptureFramePool sender,object args){try{arrived.Set();}catch(ObjectDisposedException){}};Stage="create-session";session=pool.CreateCaptureSession(item);ConfigureSession();Stage="start-session";session.StartCapture();}catch(Exception failure){try{failure.Data["CaptureStage"]=Stage;}catch{}try{Dispose();}catch{}throw;}
  }
  void ConfigureSession(){if(Windows.Foundation.Metadata.ApiInformation.IsPropertyPresent("Windows.Graphics.Capture.GraphicsCaptureSession","IsCursorCaptureEnabled"))session.IsCursorCaptureEnabled=false;}
  public void Run(int edge,int maxPixels,int fps,bool native,long owner){CurrentBackend=2;long lastGood=Environment.TickCount;bool gotFrame=false;bool suspended=false;while(!Quitting&&OwnerAlive(owner)){
   Interlocked.Exchange(ref lastActivity,Environment.TickCount);KeepSourceBehindGame();if(FollowWindow())throw new ViewChanged();int state=State();if(state!=0){Packet(state,0,0,0,0,null);if(state==3)return;suspended=true;Thread.Sleep(400);continue;}
   // Rebuild only after an actual lifecycle suspension. A paused/static image
   // need not emit FrameArrived; recreating it periodically caused black flashes.
   if(suspended){SizeInt32 resumed=item.Size;if(resumed.Width>=2&&resumed.Height>=2&&resumed.Width<=MaxEdge+128&&resumed.Height<=MaxEdge+128){Stage="resume-framepool";pool.Recreate(projected,DirectXPixelFormat.B8G8R8A8UIntNormalized,2,resumed);poolSize=resumed;}suspended=false;}
   Stopwatch tick=Stopwatch.StartNew();arrived.WaitOne(100);if(Quitting||!OwnerAlive(owner))return;Direct3D11CaptureFrame latest=null,next;SizeInt32 size=default(SizeInt32);int w=2,h=2,outCW=0,outCH=0;byte[] outgoing=null;
   try{Stage="next-frame";for(int queued=0;queued<2&&(next=pool.TryGetNextFrame())!=null;queued++){Direct3D11CaptureFrame previous=latest;latest=next;if(previous!=null)previous.Dispose();}
   if(latest==null){Packet(12,0,0,0,0,null);long quiet=(long)(uint)(Environment.TickCount-(int)lastGood);if(!gotFrame&&quiet>15000)throw new IOException("WGC no first frame");continue;}
   size=latest.ContentSize;if(size.Width<2||size.Height<2)continue;
    int left=0,top=0,sw,sh,cw,ch;
    if(SelectedMonitor){MONITORINFOEX info;if(!MonitorInfo(out info)){Packet(3,0,0,0,0,null);return;}cw=info.Monitor.R-info.Monitor.L;ch=info.Monitor.B-info.Monitor.T;sw=Math.Min(cw,size.Width);sh=Math.Min(ch,size.Height);}
    else{RECT client,bounds;GetClientRect(Selected,out client);if(DwmBounds(Selected,9,out bounds,Marshal.SizeOf(typeof(RECT)))!=0)GetWindowRect(Selected,out bounds);RECT outer;GetWindowRect(Selected,out outer);
     if(Math.Abs(outer.R-outer.L-size.Width)+Math.Abs(outer.B-outer.T-size.Height)<Math.Abs(bounds.R-bounds.L-size.Width)+Math.Abs(bounds.B-bounds.T-size.Height))bounds=outer;
     POINT origin=new POINT(0,0);ClientToScreen(Selected,ref origin);left=Math.Max(0,origin.X-bounds.L);top=Math.Max(0,origin.Y-bounds.T);cw=client.R;ch=client.B;sw=Math.Min(cw,size.Width-left);sh=Math.Min(ch,size.Height-top);}
    if(sw<2||sh<2||sw>MaxEdge||sh>MaxEdge||(long)sw*sh>MaxPixels){Packet(6,0,0,0,0,null);continue;}
    Dimensions(sw,sh,edge,maxPixels,native,out w,out h);IntPtr surface=IntPtr.Zero;Guid textureId=new Guid("6F15AAF2-D208-4E89-9AB4-489535D34F9C");Stage="surface-interface";((DxgiAccess)latest.Surface).GetInterface(ref textureId,out surface);
    try{Stage="surface-copy";DESC desc;Method<GetDesc>(surface,10)(surface,out desc);if(left+sw>desc.Width||top+sh>desc.Height){Packet(6,0,0,0,0,null);continue;}
     if(staging==IntPtr.Zero||stagingW!=sw||stagingH!=sh){if(staging!=IntPtr.Zero){Marshal.Release(staging);staging=IntPtr.Zero;}DESC target=new DESC();target.Width=(uint)sw;target.Height=(uint)sh;target.MipLevels=1;target.ArraySize=1;target.Format=87;target.SampleCount=1;target.Usage=3;target.CPUAccessFlags=0x20000;Marshal.ThrowExceptionForHR(Method<CreateTexture>(device,5)(device,ref target,IntPtr.Zero,out staging));stagingW=sw;stagingH=sh;}
     BOX region=new BOX();region.L=(uint)left;region.T=(uint)top;region.R=(uint)(left+sw);region.B=(uint)(top+sh);region.Bk=1;Method<CopyRegion>(context,46)(context,staging,0,0,0,0,surface,0,ref region);MAPPED mapped;Marshal.ThrowExceptionForHR(Method<Map>(context,14)(context,staging,0,1,0,out mapped));
     byte[] nativePixels=RentPixels(checked(sw*sh*4));try{for(int row=0;row<sh;row++)Marshal.Copy(new IntPtr(mapped.Data.ToInt64()+row*(long)mapped.RowPitch),nativePixels,row*sw*4,sw*4);}finally{Method<Unmap>(context,15)(context,staging,0);}
     outgoing=w==sw&&h==sh?nativePixels:Resize(nativePixels,sw,sh,w,h);if(outgoing!=nativePixels)ReturnPixels(nativePixels);outCW=cw;outCH=ch;gotFrame=true;lastGood=Environment.TickCount;
    }finally{if(surface!=IntPtr.Zero)Marshal.Release(surface);}
    
   }finally{if(latest!=null)latest.Dispose();if(size.Width>=2&&size.Height>=2&&(size.Width!=poolSize.Width||size.Height!=poolSize.Height)){Stage="resize-framepool";pool.Recreate(projected,DirectXPixelFormat.B8G8R8A8UIntNormalized,2,size);poolSize=size;}}
   // Native surfaces and the frame are released before any transport work.
   if(outgoing!=null)Packet(1,w,h,outCW,outCH,outgoing);
   Thread.Sleep(Math.Max(1,Delay(w,h,fps)-(int)tick.ElapsedMilliseconds));
  }}
  public void Dispose(){if(session!=null)session.Dispose();if(pool!=null)pool.Dispose();if(projected!=null)projected.Dispose();if(staging!=IntPtr.Zero)Marshal.Release(staging);if(context!=IntPtr.Zero)Marshal.Release(context);if(device!=IntPtr.Zero)Marshal.Release(device);arrived.Dispose();}
 }
 static byte[] Resize(byte[] source,int sw,int sh,int w,int h){byte[] target=new byte[checked(w*h*4)];for(int y=0;y<h;y++){double sy=(y+.5)*sh/h-.5;int y0=Math.Max(0,Math.Min(sh-1,(int)Math.Floor(sy))),y1=Math.Min(sh-1,y0+1);double fy=Math.Max(0,sy-y0);for(int x=0;x<w;x++){double sx=(x+.5)*sw/w-.5;int x0=Math.Max(0,Math.Min(sw-1,(int)Math.Floor(sx))),x1=Math.Min(sw-1,x0+1);double fx=Math.Max(0,sx-x0);for(int c=0;c<3;c++){double a=source[(y0*sw+x0)*4+c]*(1-fx)+source[(y0*sw+x1)*4+c]*fx,b=source[(y1*sw+x0)*4+c]*(1-fx)+source[(y1*sw+x1)*4+c]*fx;target[(y*w+x)*4+c]=(byte)Math.Round(a*(1-fy)+b*fy);}target[(y*w+x)*4+3]=255;}}return target;}
 public static int Main(string[] args){try{Dpi();if(args.Length==2&&args[0]=="list"){List(Int64.Parse(args[1]));return 0;}if((args.Length!=10&&args.Length!=12&&args.Length!=14)||args[0]!="capture")return 2;
  Selected=new IntPtr(Int64.Parse(args[1]));SelectedPid=Int32.Parse(args[2]);int edge=Math.Max(320,Math.Min(MaxEdge,Int32.Parse(args[3]))),fps=Math.Max(0,Math.Min(120,Int32.Parse(args[4])));long owner=Int64.Parse(args[5]);OwnerPid=checked((int)owner);bool native=args[6]=="1";int pixels=Math.Max(1048576,Math.Min(MaxPixels,Int32.Parse(args[7])));SelectedMonitor=args.Length>=12&&args[10]=="monitor";if(args.Length>=12){if(args[10]!="monitor"&&args[10]!="window")return 3;SelectedSourceId=new UTF8Encoding(false,true).GetString(Convert.FromBase64String(args[11]));}AllowInput=args[8]=="1"&&!SelectedMonitor;string backend=args[9];if(SelectedMonitor?(Selected==IntPtr.Zero||SelectedPid!=0||SelectedSourceId.Length==0):(Selected==IntPtr.Zero||SelectedPid<=0||SelectedPid==owner||SelectedPid==Process.GetCurrentProcess().Id))return 3;
  InitializeFollow(args.Length==14?new IntPtr(Int64.Parse(args[12])):Selected,args.Length==14?Int32.Parse(args[13]):SelectedPid);
   Writer=new BinaryWriter(Console.OpenStandardOutput());StartOutput();Interlocked.Exchange(ref lastActivity,Environment.TickCount);Timer guardian=new Timer(delegate(object ignored){long age=(long)(uint)(Environment.TickCount-(int)Interlocked.Read(ref lastActivity));if(!OwnerAlive(owner)||age>15000){Quitting=true;RestoreGuards();RestorePlacement();Environment.Exit(0);}},null,1000,1000);StartEvents();
  Thread input=new Thread(InputLoop);input.IsBackground=true;input.Start();try{
   FollowWindow();if(State()==3){Packet(3,0,0,0,0,null);return 0;}
   if(backend=="gdi"&&!SelectedMonitor){Gdi(edge,pixels,fps,native,owner,0);return 0;}
   int failures=0;while(!Quitting&&OwnerAlive(owner)&&State()!=3){
    Wgc capture=null;try{capture=new Wgc();capture.Run(edge,pixels,fps,native,owner);return 0;}
    catch(ViewChanged){failures=0;continue;}
    catch(Exception failure){if(Quitting||!OwnerAlive(owner))break;Console.Error.WriteLine("WGC: "+failure.GetType().Name+" (0x"+failure.HResult.ToString("X8")+") stage="+(failure.Data.Contains("CaptureStage")?failure.Data["CaptureStage"]:capture==null?"initialize":capture.Stage));Packet(13,0,0,0,0,null);}
    finally{if(capture!=null)try{capture.Dispose();}catch{}}
    if(Quitting||!OwnerAlive(owner)||State()==3)break;
    // PrintWindow cannot keep accelerated video live. A transient WGC failure must not
    // replace a good compositor frame with a cached GDI screenshot and switch back again.
    failures=Math.Min(5,failures+1);Thread.Sleep(Math.Min(1500,100*(1<<(failures-1))));
    Interlocked.Exchange(ref lastActivity,Environment.TickCount);
   }return 0;
   }finally{Quitting=true;guardian.Dispose();StopOutput();RestoreGuards();RestorePlacement();if(EventThread!=null)EventThread.Join(250);foreach(Process process in FollowProcesses.Values)process.Dispose();}
 }catch(Exception error){try{Console.Error.WriteLine(error.GetType().Name+": "+error.Message);}catch{}return 1;}}
}

