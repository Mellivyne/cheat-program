// Background scrolling through a private UI Automation 8 COM client, with
// bounded window-message delivery for controls without a ScrollPattern.
// AutoSetFocus MUST be disabled on this client: the legacy managed ScrollPattern
// wrapper otherwise focuses the source window before executing a scroll action.
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class CheatProgramWindowScroll {
 const int Capacity=64,NodeBudget=384,DepthBudget=32,SearchBudgetMilliseconds=150;
 const long RequestLifetimeTicks=1500*TimeSpan.TicksPerMillisecond;
 const double NoScroll=-1;
 static readonly BlockingCollection<Request> Requests=new BlockingCollection<Request>(Capacity);
 static readonly AxisState Horizontal=new AxisState(),Vertical=new AxisState();
 static int Generation,WorkerGeneration=-1;
 public static long Searches,CacheHits,CoalescedRequests,NativeWheelMessages;
 public static long NativeFastPaths,SearchBudgetStops,CompletedRequests,LastDispatchMilliseconds,MaxDispatchMilliseconds;

 [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
 [DllImport("user32.dll")] static extern bool IsChild(IntPtr parent,IntPtr child);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint process);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window,StringBuilder name,int capacity);
 delegate bool EnumProc(IntPtr window,IntPtr parameter);
 [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr parent,EnumProc callback,IntPtr parameter);
 [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr SendMessageTimeoutW(IntPtr window,uint message,UIntPtr wParam,IntPtr lParam,uint flags,uint timeout,out UIntPtr result);
 [DllImport("ole32.dll")] static extern int CoInitializeEx(IntPtr reserved,uint mode);
 [DllImport("ole32.dll")] static extern void CoUninitialize();
 [DllImport("ole32.dll")] static extern int CoCreateInstance(ref Guid clsid,IntPtr outer,uint context,ref Guid iid,out IntPtr instance);
 [DllImport("oleaut32.dll")] static extern uint SafeArrayGetDim(IntPtr array);
 [DllImport("oleaut32.dll")] static extern int SafeArrayGetLBound(IntPtr array,uint dimension,out int bound);
 [DllImport("oleaut32.dll")] static extern int SafeArrayGetUBound(IntPtr array,uint dimension,out int bound);
 [DllImport("oleaut32.dll")] static extern int SafeArrayAccessData(IntPtr array,out IntPtr data);
 [DllImport("oleaut32.dll")] static extern int SafeArrayUnaccessData(IntPtr array);
 [DllImport("oleaut32.dll")] static extern int SafeArrayDestroy(IntPtr array);
 [StructLayout(LayoutKind.Sequential)] struct Bounds {public int Left,Top,Right,Bottom;public double Width{get{return (double)Right-Left;}}public double Height{get{return (double)Bottom-Top;}}}
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetPointer(IntPtr self,out IntPtr value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetElement(IntPtr self,IntPtr from,out IntPtr value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int SetInt(IntPtr self,int value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetInt(IntPtr self,out int value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetDouble(IntPtr self,out double value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetBounds(IntPtr self,out Bounds value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetPattern(IntPtr self,int pattern,out IntPtr value);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int SetPercent(IntPtr self,double horizontal,double vertical);
 static T Method<T>(IntPtr self,int index)where T:class{return Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(Marshal.ReadIntPtr(self),index*IntPtr.Size),typeof(T))as T;}
 static void Checked(int hr){Marshal.ThrowExceptionForHR(hr);}
 static void Free(ref IntPtr pointer){IntPtr previous=pointer;pointer=IntPtr.Zero;if(previous!=IntPtr.Zero)Marshal.Release(previous);}

 sealed class Request {
  public IntPtr Root,Target;public int Pid,TargetPid,X,Y,Sx,Sy,Generation,Finished;public uint Modifiers;
  public long Created;public Func<bool> Allowed;public Action<bool> Completed;
 }
 sealed class Candidate {
  public IntPtr Pattern;public int[] RuntimeId;public int Depth;public double Area,Width,Height;public Bounds Bounds;
 }
 sealed class Search:IDisposable {
  public Request Request;public int Visited;public bool Truncated;public Stopwatch Clock=Stopwatch.StartNew();
  public Candidate Horizontal,Vertical;public readonly List<Candidate> Resources=new List<Candidate>();
  public void Dispose(){foreach(Candidate candidate in Resources)Free(ref candidate.Pattern);Resources.Clear();}
 }
 sealed class AxisState {
  IntPtr root;int pid;int[] id;double predicted,observed;long updated;
  public void Reset(){root=IntPtr.Zero;pid=0;id=null;predicted=observed=0;updated=0;}
  public double Offset(Request request,Candidate candidate,double current,double delta){
   long now=DateTime.UtcNow.Ticks;
   bool changed=root!=request.Root||pid!=request.Pid||!Same(id,candidate.RuntimeId);
   if(changed||now-updated>500*TimeSpan.TicksPerMillisecond||Math.Abs(current-observed)>.001){
    root=request.Root;pid=request.Pid;id=(int[])candidate.RuntimeId.Clone();predicted=current;
   }
   observed=current;predicted=Math.Max(0,Math.Min(100,predicted+delta));updated=now;return predicted;
  }
 }

 sealed class BackgroundAutomation:IDisposable {
  IntPtr client,walker;
  IntPtr cachedRoot,cachedTarget;int cachedPid,cachedX,cachedY,cachedAxes;long cacheUntil;Candidate cachedHorizontal,cachedVertical;
  static readonly Guid ScrollInterface=new Guid("88f4d42a-e881-459d-a77c-73bbbb7e02dc");
  public BackgroundAutomation(){
   try{
    Guid clsid=new Guid("e22ad333-b25f-460c-83d0-0581107395c9"),iid=new Guid("34723aff-0c9d-49d0-9896-7ab52df8cd8a");
    Checked(CoCreateInstance(ref clsid,IntPtr.Zero,1,ref iid,out client));
    // IUIAutomation2 extends the 58-entry IUIAutomation interface.
    Checked(Method<SetInt>(client,59)(client,0));int autoFocus;
    Checked(Method<GetInt>(client,58)(client,out autoFocus));if(autoFocus!=0)throw new InvalidOperationException("UIA focus guard unavailable");
    Checked(Method<SetInt>(client,61)(client,150));
    Checked(Method<SetInt>(client,63)(client,120));
    Checked(Method<GetPointer>(client,16)(client,out walker));
   }catch{Dispose();throw;}
  }
  void DropCache(){if(cachedHorizontal!=null)Free(ref cachedHorizontal.Pattern);if(cachedVertical!=null)Free(ref cachedVertical.Pattern);cachedHorizontal=cachedVertical=null;cachedRoot=IntPtr.Zero;cachedAxes=0;cacheUntil=0;}
  static Candidate Retain(Candidate source){if(source==null)return null;Marshal.AddRef(source.Pattern);return new Candidate{Pattern=source.Pattern,RuntimeId=source.RuntimeId,Width=source.Width,Height=source.Height,Bounds=source.Bounds};}
  public void Dispose(){DropCache();Free(ref walker);Free(ref client);}
  public int Scroll(Request request){
   int axes=(request.Sx!=0?1:0)|(request.Sy!=0?2:0);
   if(cachedRoot==request.Root&&cachedTarget==request.Target&&cachedPid==request.Pid&&DateTime.UtcNow.Ticks<cacheUntil&&Math.Abs(cachedX-request.X)<=24&&Math.Abs(cachedY-request.Y)<=24&&
    (axes&~cachedAxes)==0&&(cachedHorizontal==null||Contains(cachedHorizontal.Bounds,request.X,request.Y))&&(cachedVertical==null||Contains(cachedVertical.Bounds,request.X,request.Y))){
    if(cachedHorizontal==null&&cachedVertical==null){Interlocked.Increment(ref CacheHits);return 0;}
    int applied=Apply(request,cachedHorizontal,cachedVertical);if(applied!=0){Interlocked.Increment(ref CacheHits);return applied;}DropCache();
   }
   IntPtr root=IntPtr.Zero;
   try{
    Checked(Method<GetElement>(client,6)(client,request.Root,out root));
    if(root==IntPtr.Zero||!Allowed(request))return 0;
    Bounds bounds;Checked(Method<GetBounds>(root,43)(root,out bounds));if(!Contains(bounds,request.X,request.Y))return 0;
    using(Search search=new Search{Request=request}){
     Interlocked.Increment(ref Searches);
     // Native applications often expose many unrelated controls before the
     // pointed control in their accessibility tree. Start at the child HWND
     // already hit-tested inside the selected window, then try its container.
     if(request.Target!=request.Root){IntPtr target=IntPtr.Zero;try{if(Method<GetElement>(client,6)(client,request.Target,out target)>=0&&target!=IntPtr.Zero)Inspect(target,0,search);}finally{Free(ref target);}}
     if((request.Sx!=0&&search.Horizontal==null)||(request.Sy!=0&&search.Vertical==null))Inspect(root,0,search);
     if(!Allowed(request))return 0;
     Candidate x=search.Horizontal,y=search.Vertical;
     int applied=Apply(request,x,y);
     // Briefly cache the absence of a pattern as well. A media/canvas view
     // must not trigger another full accessibility walk for every tiny delta.
     if(applied!=0||x==null&&y==null&&!search.Truncated){DropCache();cachedHorizontal=Retain(x);cachedVertical=Retain(y);cachedRoot=request.Root;cachedTarget=request.Target;cachedPid=request.Pid;cachedX=request.X;cachedY=request.Y;cachedAxes=axes;cacheUntil=DateTime.UtcNow.Ticks+(applied!=0?750:250)*TimeSpan.TicksPerMillisecond;}return applied;
    }
   }finally{Free(ref root);}
  }
  int Apply(Request request,Candidate x,Candidate y){
     double sx=NoScroll,sy=NoScroll;int applied=0;
     try{if(request.Sx!=0&&x!=null){double step=PercentDelta(Value(x.Pattern,7),x.Width,request.Sx),current=Value(x.Pattern,5);if(Finite(step)&&current>=0)sx=Horizontal.Offset(request,x,current,step);}}catch(COMException){Horizontal.Reset();}
     if(request.Sx!=0&&sx==NoScroll)Horizontal.Reset();
     try{if(request.Sy!=0&&y!=null){double step=PercentDelta(Value(y.Pattern,8),y.Height,-request.Sy),current=Value(y.Pattern,6);if(Finite(step)&&current>=0)sy=Vertical.Offset(request,y,current,step);}}catch(COMException){Vertical.Reset();}
     if(request.Sy!=0&&sy==NoScroll)Vertical.Reset();
     if(!Allowed(request))return 0;
     bool together=sx!=NoScroll&&sy!=NoScroll&&Same(x.RuntimeId,y.RuntimeId);
     // Only change offsets. There is no SetFocus, ScrollIntoView, click or
     // legacy managed-pattern fallback if this private client is unavailable.
     if(together){try{Checked(Method<SetPercent>(x.Pattern,4)(x.Pattern,sx,sy));applied=3;}catch(COMException){Horizontal.Reset();Vertical.Reset();}}
     else{
      if(sx!=NoScroll){try{Checked(Method<SetPercent>(x.Pattern,4)(x.Pattern,sx,NoScroll));applied|=1;}catch(COMException){Horizontal.Reset();}}
      if(sy!=NoScroll&&Allowed(request)){try{Checked(Method<SetPercent>(y.Pattern,4)(y.Pattern,NoScroll,sy));applied|=2;}catch(COMException){Vertical.Reset();}}
     }
     return applied;
  }
  void Inspect(IntPtr element,int depth,Search search){
   if(depth>DepthBudget||search.Visited++>=NodeBudget||search.Clock.ElapsedMilliseconds>SearchBudgetMilliseconds){search.Truncated=true;Interlocked.Increment(ref SearchBudgetStops);return;}
   if(!Allowed(search.Request))return;
   try{
    Bounds bounds;Checked(Method<GetBounds>(element,43)(element,out bounds));
    bool inside=Contains(bounds,search.Request.X,search.Request.Y);
    if(!inside&&bounds.Width>0&&bounds.Height>0)return;
    if(inside){
     IntPtr pattern=IntPtr.Zero;Guid iid=ScrollInterface;
     IntPtr raw=IntPtr.Zero;int hr;
     try{hr=Method<GetPattern>(element,16)(element,10004,out raw);if(hr>=0&&raw!=IntPtr.Zero)hr=Marshal.QueryInterface(raw,ref iid,out pattern);}finally{Free(ref raw);}
     if(hr>=0&&pattern!=IntPtr.Zero){
      Candidate candidate=new Candidate{Pattern=pattern,Depth=depth,Area=bounds.Width*bounds.Height,Width=bounds.Width,Height=bounds.Height,Bounds=bounds};
      search.Resources.Add(candidate);candidate.RuntimeId=RuntimeId(element);
      if(candidate.RuntimeId!=null){int value;
       if(search.Request.Sx!=0&&Method<GetInt>(pattern,9)(pattern,out value)>=0&&value!=0&&Better(candidate,search.Horizontal))search.Horizontal=candidate;
       if(search.Request.Sy!=0&&Method<GetInt>(pattern,10)(pattern,out value)>=0&&value!=0&&Better(candidate,search.Vertical))search.Vertical=candidate;
      }
     }else Free(ref pattern);
    }
    if(depth==DepthBudget){search.Truncated=true;return;}
    IntPtr child=IntPtr.Zero;
    try{
     Checked(Method<GetElement>(walker,4)(walker,element,out child));
     while(child!=IntPtr.Zero&&search.Visited<NodeBudget&&search.Clock.ElapsedMilliseconds<=SearchBudgetMilliseconds&&Allowed(search.Request)){
      Inspect(child,depth+1,search);IntPtr next=IntPtr.Zero;
      int hr=Method<GetElement>(walker,6)(walker,child,out next);Free(ref child);child=next;Checked(hr);
     }
     if(child!=IntPtr.Zero){search.Truncated=true;Interlocked.Increment(ref SearchBudgetStops);}
    }finally{Free(ref child);}
   }catch(COMException){search.Truncated=true;}
   catch(InvalidOperationException){search.Truncated=true;}
  }
  static double Value(IntPtr pattern,int index){double value;Checked(Method<GetDouble>(pattern,index)(pattern,out value));return value;}
  static int[] RuntimeId(IntPtr element){
   IntPtr array=IntPtr.Zero,data=IntPtr.Zero;
   try{
    Checked(Method<GetPointer>(element,4)(element,out array));if(array==IntPtr.Zero||SafeArrayGetDim(array)!=1)return null;
    int first,last;Checked(SafeArrayGetLBound(array,1,out first));Checked(SafeArrayGetUBound(array,1,out last));long length=(long)last-first+1;
    if(length<1||length>128)return null;int[] id=new int[(int)length];Checked(SafeArrayAccessData(array,out data));Marshal.Copy(data,id,0,id.Length);return id;
   }finally{if(data!=IntPtr.Zero)SafeArrayUnaccessData(array);if(array!=IntPtr.Zero)SafeArrayDestroy(array);}
  }
 }

 static CheatProgramWindowScroll(){Thread worker=new Thread(Work);worker.IsBackground=true;worker.Name="CheatProgram-window-scroll";worker.SetApartmentState(ApartmentState.MTA);worker.Start();}
 static IntPtr classifiedRoot;static bool classifiedChromium;static long classifiedUntil;
 static bool ChromiumClass(IntPtr window){StringBuilder name=new StringBuilder(128);if(GetClassName(window,name,name.Capacity)<=0)return false;string text=name.ToString();return text.StartsWith("Chrome_",StringComparison.Ordinal)||text.StartsWith("CefBrowser",StringComparison.Ordinal);}
 public static bool IsChromium(IntPtr root){try{
  if(root==IntPtr.Zero)return false;if(root==classifiedRoot&&DateTime.UtcNow.Ticks<classifiedUntil)return classifiedChromium;
  bool found=ChromiumClass(root);int visited=0;if(!found)EnumChildWindows(root,delegate(IntPtr child,IntPtr unused){if(ChromiumClass(child)){found=true;return false;}return ++visited<128;},IntPtr.Zero);
  classifiedRoot=root;classifiedChromium=found;classifiedUntil=DateTime.UtcNow.Ticks+2*TimeSpan.TicksPerSecond;return found;
 }catch{return false;}}
 public static bool Queue(IntPtr root,int pid,int sx,int sy,int screenX,int screenY,Func<bool> stillAllowed,Action<bool> completed){
  return Queue(root,pid,root,sx,sy,screenX,screenY,0,stillAllowed,completed);
 }
 public static bool Queue(IntPtr root,int pid,IntPtr target,int sx,int sy,int screenX,int screenY,uint modifiers,Func<bool> stillAllowed,Action<bool> completed){
  if(root==IntPtr.Zero||pid<=0||target==IntPtr.Zero||stillAllowed==null||completed==null||Math.Abs((long)sx)>12000||Math.Abs((long)sy)>12000||(modifiers&~127u)!=0)return false;
  uint targetPid;if(!IsWindow(target)||(target!=root&&!IsChild(root,target))||GetWindowThreadProcessId(target,out targetPid)==0)return false;
  Request request=new Request{Root=root,Pid=pid,Target=target,TargetPid=(int)targetPid,Modifiers=modifiers,Sx=sx,Sy=sy,X=screenX,Y=screenY,Allowed=stillAllowed,Completed=completed,Generation=Volatile.Read(ref Generation),Created=DateTime.UtcNow.Ticks};return Requests.TryAdd(request);
 }
 public static void Clear(){Interlocked.Increment(ref Generation);Request request;while(Requests.TryTake(out request))Finish(request,false);}
 static void Finish(Request request,bool success){if(Interlocked.Exchange(ref request.Finished,1)!=0)return;try{request.Completed(success);}catch{}}
 static bool Allowed(Request request){
  if(request.Generation!=Volatile.Read(ref Generation)||DateTime.UtcNow.Ticks-request.Created>RequestLifetimeTicks)return false;
  try{uint process,targetProcess;return IsWindow(request.Root)&&GetWindowThreadProcessId(request.Root,out process)!=0&&process==request.Pid&&
   IsWindow(request.Target)&&(request.Target==request.Root||IsChild(request.Root,request.Target))&&GetWindowThreadProcessId(request.Target,out targetProcess)!=0&&targetProcess==request.TargetPid&&request.Allowed();}catch{return false;}
 }
 static int NativeScroll(Request request,int axes){
  int applied=0;
  // A posted message only confirms queue insertion. A bounded send detects a
  // destroyed, blocked or hung target, without bringing it to the foreground.
  // Never prepend hover, focus, click, cursor movement or physical input here.
  if((axes&1)!=0&&Wheel(request,0x20E,request.Sx))applied|=1;
  if((axes&2)!=0&&Wheel(request,0x20A,request.Sy))applied|=2;
  return applied;
 }
 static bool Wheel(Request request,uint message,int amount){
  while(amount!=0){
   if(!Allowed(request))return false;
   // Coalesced bursts can exceed a signed wheel word. Split without losing
   // high-resolution deltas, including values smaller than WHEEL_DELTA.
   int part=Math.Max(-32760,Math.Min(32760,amount));UIntPtr result;
   uint data=request.Modifiers|((uint)(ushort)part<<16);
   IntPtr point=new IntPtr(unchecked((int)(((uint)(ushort)request.Y<<16)|(ushort)request.X)));
   if(SendMessageTimeoutW(request.Target,message,new UIntPtr(data),point,0x23,100,out result)==IntPtr.Zero)return false;
   Interlocked.Increment(ref NativeWheelMessages);amount-=part;
  }
  return true;
 }
 static bool NativeWheelControl(IntPtr target){
  // Standard Win32 text/list controls implement wheel messages themselves.
  // Avoid an accessibility process startup/tree walk before their first wheel.
  // Canvas, browser and framework containers retain the focus-disabled UIA path.
  StringBuilder name=new StringBuilder(192);if(GetClassName(target,name,name.Capacity)<=0)return false;
  string kind=name.ToString();return kind.Equals("Edit",StringComparison.OrdinalIgnoreCase)
   ||kind.StartsWith("RichEdit",StringComparison.OrdinalIgnoreCase)
   ||kind.Equals("ListBox",StringComparison.OrdinalIgnoreCase)||kind.Equals("SysListView32",StringComparison.OrdinalIgnoreCase)
   ||kind.Equals("SysTreeView32",StringComparison.OrdinalIgnoreCase)
   ||kind.StartsWith("WindowsForms10.RichEdit",StringComparison.OrdinalIgnoreCase)
   ||kind.StartsWith("WindowsForms10.EDIT.",StringComparison.OrdinalIgnoreCase)
   ||kind.StartsWith("WindowsForms10.LISTBOX.",StringComparison.OrdinalIgnoreCase)
   ||kind.StartsWith("WindowsForms10.SysListView32.",StringComparison.OrdinalIgnoreCase)
   ||kind.StartsWith("WindowsForms10.SysTreeView32.",StringComparison.OrdinalIgnoreCase);
 }
 static void Work(){
  try{SetThreadDpiAwarenessContext(new IntPtr(-4));}catch(EntryPointNotFoundException){}
  BackgroundAutomation automation=null;bool initialized=CoInitializeEx(IntPtr.Zero,0)>=0;
  try{
   Request pending=null;
   while(true){
    Request request=pending??Requests.Take();pending=null;var batch=new List<Request>{request};Request next;
    while(batch.Count<32&&Requests.TryTake(out next)){
     if(next.Root==request.Root&&next.Target==request.Target&&next.TargetPid==request.TargetPid&&next.Modifiers==request.Modifiers&&next.Pid==request.Pid&&next.Generation==request.Generation&&Math.Abs(next.X-request.X)<=24&&Math.Abs(next.Y-request.Y)<=24&&
      Math.Sign(next.Sx)==Math.Sign(request.Sx)&&Math.Sign(next.Sy)==Math.Sign(request.Sy)){
      batch.Add(next);request.Sx+=next.Sx;request.Sy+=next.Sy;Interlocked.Increment(ref CoalescedRequests);
     }else{pending=next;break;}
    }
    Stopwatch dispatch=Stopwatch.StartNew();bool success=false;int axes=(request.Sx!=0?1:0)|(request.Sy!=0?2:0),applied=0,nativeAttempted=0;
    try{
     if(WorkerGeneration!=request.Generation){Horizontal.Reset();Vertical.Reset();if(automation!=null){automation.Dispose();automation=null;}WorkerGeneration=request.Generation;}
     // Modified wheels may mean zoom or an application-specific gesture. They
     // must reach the app unchanged instead of becoming plain UIA scrolling.
     // CEF/Chromium owns its wheel gestures in the renderer HWND. Its UIA tree
     // can expose a different page scroller, or report success while a video
     // surface consumes the gesture. Never replace those native events with a
     // percentage jump or wait for an accessibility walk before delivering it.
     bool nativePreferred=NativeWheelControl(request.Target)||IsChromium(request.Root);
     if(Allowed(request)&&nativePreferred){nativeAttempted=axes;applied=NativeScroll(request,axes);if(applied!=0)Interlocked.Increment(ref NativeFastPaths);}
     // A timed-out send may already be executing. Retrying it through another path
     // could apply one wheel twice; report that attempt rather than duplicating it.
     if(initialized&&request.Modifiers==0&&(axes&~(applied|nativeAttempted))!=0&&Allowed(request)){
      if(automation==null)automation=new BackgroundAutomation();
      int sx=request.Sx,sy=request.Sy;try{if((applied&1)!=0)request.Sx=0;if((applied&2)!=0)request.Sy=0;applied|=automation.Scroll(request);}finally{request.Sx=sx;request.Sy=sy;}
     }
    }catch{Horizontal.Reset();Vertical.Reset();if(automation!=null){automation.Dispose();automation=null;}}
    try{if(Allowed(request)){applied|=NativeScroll(request,axes&~(applied|nativeAttempted));success=(applied&axes)==axes;}}catch{}
    long elapsed=dispatch.ElapsedMilliseconds;Interlocked.Exchange(ref LastDispatchMilliseconds,elapsed);if(elapsed>Interlocked.Read(ref MaxDispatchMilliseconds))Interlocked.Exchange(ref MaxDispatchMilliseconds,elapsed);
    Interlocked.Add(ref CompletedRequests,batch.Count);foreach(Request item in batch)Finish(item,success);
   }
  }finally{if(automation!=null)automation.Dispose();if(initialized)CoUninitialize();}
 }
 static bool Finite(double value){return !Double.IsNaN(value)&&!Double.IsInfinity(value);}
 static double PercentDelta(double view,double extent,int wheel){if(!Finite(view)||!Finite(extent)||view<=0||view>=100||extent<=0)return Double.NaN;double movablePixels=extent*(100-view)/view;return wheel*100.0/Math.Max(1,movablePixels);}
 static bool Better(Candidate candidate,Candidate current){return current==null||candidate.Depth>current.Depth||(candidate.Depth==current.Depth&&candidate.Area<current.Area);}
 static bool Contains(Bounds bounds,int x,int y){return bounds.Width>0&&bounds.Height>0&&x>=bounds.Left&&x<bounds.Right&&y>=bounds.Top&&y<bounds.Bottom;}
 static bool Same(int[] a,int[] b){if(a==null||b==null||a.Length!=b.Length)return false;for(int i=0;i<a.Length;i++)if(a[i]!=b[i])return false;return true;}
}
