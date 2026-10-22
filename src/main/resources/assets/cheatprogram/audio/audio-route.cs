// Audio endpoint snapshots and bounded selected-application routes.
// All system-default writes require the explicit restore-defaults command.
using System;
using System.IO;
using System.Text;
using System.Diagnostics;
using System.Threading;
using System.Collections.Generic;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Runtime.InteropServices;

public static partial class AudioRoute {
    [DataContract] public sealed class Endpoint {
        [DataMember]public string id;
        [DataMember]public string name;
        [DataMember]public int flow;
        [DataMember]public uint state;
        [DataMember]public bool cable;
    }
    [DataContract] public sealed class DefaultEndpoint {
        [DataMember]public int flow;
        [DataMember]public int role;
        [DataMember]public string id;
    }
    [DataContract] public sealed class Snapshot {
        [DataMember]public int schema=1;
        [DataMember]public long createdUtc=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        [DataMember]public List<DefaultEndpoint> defaults=new List<DefaultEndpoint>();
        [DataMember]public List<Endpoint> endpoints=new List<Endpoint>();
    }
    [StructLayout(LayoutKind.Sequential)] public struct PropertyKey {public Guid format;public uint id;public PropertyKey(string guid,uint property){format=new Guid(guid);id=property;}}
    [StructLayout(LayoutKind.Explicit,Size=24)] public struct Variant {[FieldOffset(0)]public ushort type;[FieldOffset(8)]public IntPtr pointer;}
    [DllImport("ole32.dll")]static extern int PropVariantClear(ref Variant value);
    static void Check(int result){if(result<0)Marshal.ThrowExceptionForHR(result);}
    static void Release(object value){if(value!=null&&Marshal.IsComObject(value))try{Marshal.ReleaseComObject(value);}catch{}}
    static byte[] Json(object value,Type type){using(MemoryStream stream=new MemoryStream()){new DataContractJsonSerializer(type).WriteObject(stream,value);return stream.ToArray();}}
    static T Read<T>(string path){using(FileStream stream=File.OpenRead(path))return (T)new DataContractJsonSerializer(typeof(T)).ReadObject(stream);}
    static void Print(object value){byte[] bytes=Json(value,value.GetType());Stream output=Console.OpenStandardOutput();output.Write(bytes,0,bytes.Length);output.WriteByte(10);output.Flush();}
    static void Save(string path,object value){string full=Path.GetFullPath(path);Directory.CreateDirectory(Path.GetDirectoryName(full));string temporary=full+".pending";File.WriteAllBytes(temporary,Json(value,value.GetType()));if(File.Exists(full))File.Replace(temporary,full,null);else File.Move(temporary,full);}
    [MTAThread]static int Main(string[] args){try{
        if(args.Length==0)return 2;
        if(args[0]=="snapshot"&&(args.Length==1||args.Length==2)){Snapshot snapshot=TakeSnapshot();if(args.Length==2)Save(args[1],snapshot);Print(snapshot);return 0;}
        if(args[0]=="restore-defaults"&&args.Length==2){RestoreDefaults(Read<Snapshot>(args[1]));Print(TakeSnapshot());return 0;}
        return AdditionalCommand(args);
    }catch(Exception error){Console.Error.WriteLine("ROUTE: 0x"+Marshal.GetHRForException(error).ToString("X8"));return 1;}}
    static Snapshot TakeSnapshot(){Snapshot snapshot=new Snapshot();IMMDeviceEnumerator enumerator=null;
        try{enumerator=(IMMDeviceEnumerator)new MMDeviceEnumerator();for(int flow=0;flow<2;flow++){
            for(int role=0;role<3;role++){IMMDevice device=null;try{int result=enumerator.GetDefaultAudioEndpoint(flow,role,out device);string id="";if(result>=0&&device!=null)Check(device.GetId(out id));snapshot.defaults.Add(new DefaultEndpoint{flow=flow,role=role,id=id});}finally{Release(device);}}
            IMMDeviceCollection collection=null;try{Check(enumerator.EnumAudioEndpoints(flow,15,out collection));uint count;Check(collection.GetCount(out count));for(uint i=0;i<count;i++){
                IMMDevice device=null;try{Check(collection.Item(i,out device));string id;uint state;Check(device.GetId(out id));Check(device.GetState(out state));string name=DeviceName(device);snapshot.endpoints.Add(new Endpoint{id=id,name=name,flow=flow,state=state,cable=IsCable(name)});}finally{Release(device);}
            }}finally{Release(collection);}
        }return snapshot;}finally{Release(enumerator);}}
    static bool IsCable(string name){return name!=null&&(name.IndexOf("VB-Audio",StringComparison.OrdinalIgnoreCase)>=0||name.IndexOf("CABLE Input",StringComparison.OrdinalIgnoreCase)>=0||name.IndexOf("CABLE Output",StringComparison.OrdinalIgnoreCase)>=0);}
    static string DeviceName(IMMDevice device){IPropertyStore store=null;Variant value=new Variant();try{Check(device.OpenPropertyStore(0,out store));PropertyKey key=new PropertyKey("a45c254e-df1c-4efd-8020-67d146a850e0",14);Check(store.GetValue(ref key,out value));return value.type==31&&value.pointer!=IntPtr.Zero?Marshal.PtrToStringUni(value.pointer):"";}finally{PropVariantClear(ref value);Release(store);}}
    static void RestoreDefaults(Snapshot snapshot){if(snapshot==null||snapshot.schema!=1||snapshot.defaults==null||snapshot.defaults.Count>6)throw new InvalidDataException("Invalid endpoint snapshot");IMMDeviceEnumerator enumerator=null;IPolicyConfig policy=null;
        try{enumerator=(IMMDeviceEnumerator)new MMDeviceEnumerator();policy=(IPolicyConfig)new PolicyConfigClient();foreach(DefaultEndpoint saved in snapshot.defaults){
            if(saved.flow<0||saved.flow>1||saved.role<0||saved.role>2||String.IsNullOrEmpty(saved.id))continue;IMMDevice device=null;try{int result=enumerator.GetDevice(saved.id,out device);if(result<0||device==null)continue;uint state;Check(device.GetState(out state));if((state&1)==0)continue;Check(policy.SetDefaultEndpoint(saved.id,saved.role));}finally{Release(device);}
        }}finally{Release(policy);Release(enumerator);}}
    static int AdditionalCommand(string[] args){
        if(args[0]=="check"&&(args.Length==1||args.Length==2)){Print(EnvironmentCheck());return 0;}
        if(args[0]=="route"&&args.Length==4)return RunRoute(Int32.Parse(args[1]),Int32.Parse(args[2]),args[3]);
        if(args[0]=="restore"&&args.Length==3){using(Mutex mutex=new Mutex(false,MutexName())){
            if(!Acquire(mutex,4000))throw new IOException("Another route lease is active");try{
                RouteJournal journal=Read<RouteJournal>(args[1]);ValidateJournal(journal,Int32.Parse(args[2]));
                if(!journal.restored||journal.status!="restored"){
                    if(Alive(journal.routePid,journal.routeStart))throw new IOException("Route lease has not stopped");
                    using(PerAppPolicy policy=new PerAppPolicy())RestoreRoutes(journal,args[1],policy);
                }
                Print(new RouteStatus{status="restored",ready=false});return 0;
            }finally{mutex.ReleaseMutex();}
        }}
        if(args[0]=="watch"&&args.Length==6)return Watch(args[1],Int32.Parse(args[2]),Int32.Parse(args[3]),args[4],Int64.Parse(args[5]));
        if(args[0]=="routes"&&args.Length==3){int source=Int32.Parse(args[1]),owner=Int32.Parse(args[2]);HashSet<int> pids=SafeTree(source,owner);using(PerAppPolicy policy=new PerAppPolicy()){RouteStatus value=Inspect(source,owner,EnvironmentCheck().cableRenderId,pids,policy);Print(value);}return 0;}
        return 2;
    }
    [DataContract]public sealed class EnvironmentStatus {
        [DataMember]public string status;
        [DataMember]public bool ready,driverPresent,routeSupported,defaultRenderCable;
        [DataMember]public string cableRenderId="",cableCaptureId="",defaultRenderId="",defaultRenderName="";
        [DataMember]public int hresult;
    }
    [DataContract]public sealed class RouteStatus {
        [DataMember]public string status;
        [DataMember]public bool ready;
        [DataMember]public int sourcePid,activeSessions,verifiedSessions,routedProcesses,hresult;
    }
    [DataContract]public sealed class SavedRoute {
        [DataMember]public int pid;
        [DataMember]public long start;
        [DataMember]public string path;
        [DataMember]public string[] original;
        [DataMember]public bool applied,attempted,skippedUserChoice;
    }
    [DataContract]public sealed class RouteJournal {
        [DataMember]public int schema=1;
        [DataMember]public string kind="CheatProgram.AudioRoute",lease,status="prepared",cableId;
        [DataMember]public int sourcePid,ownerPid,routePid;
        [DataMember]public long ownerStart,routeStart;
        [DataMember]public bool restored,attemptTracking=true;
        [DataMember]public List<SavedRoute> processes=new List<SavedRoute>();
    }
    [DllImport("combase.dll",CharSet=CharSet.Unicode)]static extern int WindowsCreateString(string source,uint length,out IntPtr value);
    [DllImport("combase.dll")]static extern int WindowsDeleteString(IntPtr value);
    [DllImport("combase.dll")]static extern IntPtr WindowsGetStringRawBuffer(IntPtr value,out uint length);
    [DllImport("combase.dll")]static extern int RoGetActivationFactory(IntPtr name,ref Guid iid,out IntPtr factory);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]delegate int SetApplicationEndpoint(IntPtr instance,uint pid,int flow,int role,IntPtr device);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]delegate int GetApplicationEndpoint(IntPtr instance,uint pid,int flow,int role,out IntPtr device);
    // ABI/UUIDs are described by EarTrumpet's MIT-licensed interoperability declarations.
    // We invoke only the two known methods, never the reset-all-applications method.
    sealed class PerAppPolicy:IDisposable {
        IntPtr factory;SetApplicationEndpoint set;GetApplicationEndpoint get;
        public PerAppPolicy(){IntPtr name;string className="Windows.Media.Internal.AudioPolicyConfig";Check(WindowsCreateString(className,(uint)className.Length,out name));try{
            Guid modern=new Guid("AB3D4648-E242-459F-B02F-541C70306324");int result=RoGetActivationFactory(name,ref modern,out factory);if(result<0){Guid legacy=new Guid("2A59116D-6C4F-45E0-A74F-707E3FEF9258");Check(RoGetActivationFactory(name,ref legacy,out factory));}
            IntPtr table=Marshal.ReadIntPtr(factory);set=(SetApplicationEndpoint)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table,25*IntPtr.Size),typeof(SetApplicationEndpoint));get=(GetApplicationEndpoint)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table,26*IntPtr.Size),typeof(GetApplicationEndpoint));
        }finally{WindowsDeleteString(name);}}
        public string Get(int pid,int role){IntPtr value=IntPtr.Zero;try{int result=get(factory,(uint)pid,0,role,out value);if(result==unchecked((int)0x80070490))return "";Check(result);if(value==IntPtr.Zero)return "";uint length;IntPtr raw=WindowsGetStringRawBuffer(value,out length);return Marshal.PtrToStringUni(raw,(int)length)??"";}finally{if(value!=IntPtr.Zero)WindowsDeleteString(value);}}
        public void Set(int pid,int role,string endpoint){IntPtr value=IntPtr.Zero;try{if(!String.IsNullOrEmpty(endpoint))Check(WindowsCreateString(endpoint,(uint)endpoint.Length,out value));Check(set(factory,(uint)pid,0,role,value));}finally{if(value!=IntPtr.Zero)WindowsDeleteString(value);}}
        public void Dispose(){if(factory!=IntPtr.Zero){Marshal.Release(factory);factory=IntPtr.Zero;}}
    }
    const string RenderSuffix="#{e6327cad-dcec-4949-ae8a-991e976a79d2}";
    const string EndpointPrefix=@"\\?\SWD#MMDEVAPI#";
    static string Packed(string id){return String.IsNullOrEmpty(id)?"":id.StartsWith(EndpointPrefix,StringComparison.OrdinalIgnoreCase)?id:EndpointPrefix+id+RenderSuffix;}
    static string Unpacked(string id){if(String.IsNullOrEmpty(id))return "";if(id.StartsWith(EndpointPrefix,StringComparison.OrdinalIgnoreCase))id=id.Substring(EndpointPrefix.Length);if(id.EndsWith(RenderSuffix,StringComparison.OrdinalIgnoreCase))id=id.Substring(0,id.Length-RenderSuffix.Length);return id;}
    static EnvironmentStatus EnvironmentCheck(){Snapshot snapshot=TakeSnapshot();EnvironmentStatus result=new EnvironmentStatus{status="driverMissing"};
        foreach(Endpoint endpoint in snapshot.endpoints)if((endpoint.state&1)!=0){
            if(endpoint.flow==0&&endpoint.cable&&endpoint.name.IndexOf("CABLE Input",StringComparison.OrdinalIgnoreCase)>=0&&String.IsNullOrEmpty(result.cableRenderId))result.cableRenderId=endpoint.id;
            if(endpoint.flow==1&&endpoint.cable&&endpoint.name.IndexOf("CABLE Output",StringComparison.OrdinalIgnoreCase)>=0&&String.IsNullOrEmpty(result.cableCaptureId))result.cableCaptureId=endpoint.id;
        }
        foreach(DefaultEndpoint saved in snapshot.defaults)if(saved.flow==0){foreach(Endpoint endpoint in snapshot.endpoints)if(endpoint.id==saved.id){if(endpoint.cable)result.defaultRenderCable=true;if(saved.role==1){result.defaultRenderId=endpoint.id;result.defaultRenderName=endpoint.name;}}}
        result.driverPresent=!String.IsNullOrEmpty(result.cableRenderId)&&!String.IsNullOrEmpty(result.cableCaptureId);
        try{using(PerAppPolicy policy=new PerAppPolicy()){policy.Get(Process.GetCurrentProcess().Id,1);result.routeSupported=true;}}catch(Exception error){result.hresult=Marshal.GetHRForException(error);}
        result.ready=result.driverPresent&&result.routeSupported&&!result.defaultRenderCable&&!String.IsNullOrEmpty(result.defaultRenderId);
        result.status=result.defaultRenderCable?"defaultIsCable":!result.driverPresent?"driverMissing":!result.routeSupported?"routingUnsupported":String.IsNullOrEmpty(result.defaultRenderId)?"noPlaybackDevice":"ready";return result;
    }
    static long Started(int pid){using(Process process=Process.GetProcessById(pid))return process.StartTime.ToUniversalTime().Ticks;}
    static bool Alive(int pid,long start){try{return Started(pid)==start;}catch{return false;}}
    static string ProcessPath(int pid){using(Process process=Process.GetProcessById(pid))return process.MainModule.FileName;}
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct ProcessEntry {
        public uint size,usage,pid;public UIntPtr heap;public uint module,threads,parent;public int priority;public uint flags;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]public string name;
    }
    [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr CreateToolhelp32Snapshot(uint flags,uint pid);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool Process32FirstW(IntPtr snapshot,ref ProcessEntry entry);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool Process32NextW(IntPtr snapshot,ref ProcessEntry entry);
    [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
    static Dictionary<int,int> ProcessParents(){IntPtr snapshot=CreateToolhelp32Snapshot(2,0);if(snapshot==new IntPtr(-1))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        try{Dictionary<int,int> parents=new Dictionary<int,int>();ProcessEntry entry=new ProcessEntry();entry.size=(uint)Marshal.SizeOf(typeof(ProcessEntry));if(!Process32FirstW(snapshot,ref entry))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());do{parents[(int)entry.pid]=(int)entry.parent;}while(Process32NextW(snapshot,ref entry));return parents;}finally{CloseHandle(snapshot);}}
    static HashSet<int> SafeTree(int source,int owner){if(source<=4||owner<=4||source==owner||source==Process.GetCurrentProcess().Id)throw new InvalidOperationException("Unsafe source");Started(source);Started(owner);
        Dictionary<int,int> parents=ProcessParents();
        HashSet<int> found=new HashSet<int>();found.Add(source);bool again=true;while(again){again=false;foreach(KeyValuePair<int,int> pair in parents)if(found.Contains(pair.Value)&&found.Add(pair.Key))again=true;}if(found.Contains(owner)||found.Contains(Process.GetCurrentProcess().Id))throw new InvalidOperationException("Source contains Minecraft");string ownerPath=ProcessPath(owner);foreach(int pid in found)try{if(String.Equals(ProcessPath(pid),ownerPath,StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("Source shares the Minecraft executable identity");}catch(ArgumentException){}return found;
    }
    static volatile bool quit;static long heartbeat=Stopwatch.GetTimestamp();
    static void ReadControl(){try{string line;while((line=Console.ReadLine())!=null){if(line=="Q")break;if(line=="P")Interlocked.Exchange(ref heartbeat,Stopwatch.GetTimestamp());}}catch{}finally{quit=true;}}
    static string MutexName(){return @"Local\CheatProgram-AudioRoute-"+System.Security.Principal.WindowsIdentity.GetCurrent().User.Value;}
    static bool Acquire(Mutex mutex,int timeout){try{return mutex.WaitOne(timeout);}catch(AbandonedMutexException){return true;}}
    static void ValidateJournal(RouteJournal journal,int owner){if(journal==null||journal.schema!=1||journal.kind!="CheatProgram.AudioRoute"||String.IsNullOrEmpty(journal.lease)||journal.processes==null||journal.processes.Count>1024)throw new InvalidDataException("Invalid routing state");if(journal.ownerPid!=owner&&Alive(journal.ownerPid,journal.ownerStart))throw new InvalidOperationException("Another game owns this route");foreach(SavedRoute saved in journal.processes)if(saved.pid<=4||saved.pid==journal.ownerPid||saved.original==null||saved.original.Length!=3||String.IsNullOrEmpty(saved.path))throw new InvalidDataException("Invalid process routing state");}
    static int RunRoute(int source,int owner,string file){
        EnvironmentStatus environment=EnvironmentCheck();if(!environment.ready){Print(new RouteStatus{status="failed",hresult=environment.hresult});return 3;}
        HashSet<int> initial=SafeTree(source,owner);long sourceStart=Started(source);RouteJournal journal=new RouteJournal{lease=Guid.NewGuid().ToString("N"),sourcePid=source,ownerPid=owner,ownerStart=Started(owner),routePid=Process.GetCurrentProcess().Id,routeStart=Started(Process.GetCurrentProcess().Id),cableId=environment.cableRenderId};
        Mutex mutex=new Mutex(false,MutexName());if(!Acquire(mutex,0)){mutex.Dispose();Print(new RouteStatus{status="failed",hresult=unchecked((int)0x800700AA)});return 4;}
        Process watcher=null;Dictionary<int,Process> handles=new Dictionary<int,Process>();
        try{using(PerAppPolicy policy=new PerAppPolicy()){
            if(File.Exists(file)){RouteJournal old=Read<RouteJournal>(file);ValidateJournal(old,owner);if(old.status!="restored")throw new InvalidOperationException("Restore old lease before starting");}
            AddProcesses(initial,journal,handles,policy);Save(file,journal);
            ProcessStartInfo start=new ProcessStartInfo(Process.GetCurrentProcess().MainModule.FileName,"watch "+Quote(Path.GetFullPath(file))+" "+owner+" "+journal.routePid+" "+journal.lease+" "+journal.routeStart);start.UseShellExecute=false;start.CreateNoWindow=true;start.RedirectStandardInput=true;start.RedirectStandardOutput=true;start.RedirectStandardError=true;watcher=Process.Start(start);
            if(watcher.StandardOutput.ReadLine()!="WATCHING")throw new IOException("Route watchdog unavailable");
            new Thread(ReadControl){IsBackground=true,Name="Audio route control"}.Start();string previous="";long nextCheck=0;
            try{while(!quit&&Alive(owner,journal.ownerStart)&&Alive(source,sourceStart)&&(Stopwatch.GetTimestamp()-Interlocked.Read(ref heartbeat))/(double)Stopwatch.Frequency<8){
                if(watcher.HasExited)throw new IOException("Route watchdog stopped");watcher.StandardInput.WriteLine("P");watcher.StandardInput.Flush();
                HashSet<int> pids=SafeTree(source,owner);bool changed=AddProcesses(pids,journal,handles,policy);
                if(changed){Save(file,journal);watcher.StandardInput.WriteLine("SYNC");watcher.StandardInput.Flush();if(watcher.StandardOutput.ReadLine()!="SYNCED")throw new IOException("Route watchdog could not retain process identities");}
                HashSet<int> active;RouteStatus state=Inspect(source,owner,journal.cableId,pids,policy,out active);
                // Only a newly retained identity may be routed. Never fight a later user's Volume mixer choice.
                // Utility/root processes with no audio session may accept Get but reject Set with
                // E_INVALIDARG. Route the actual active audio children, and discover new ones next tick.
                foreach(SavedRoute saved in journal.processes)if(!saved.applied&&!saved.skippedUserChoice&&active.Contains(saved.pid)&&handles.ContainsKey(saved.pid)&&Alive(saved.pid,saved.start)){
                    // A fresh PID is a fresh audio identity, even when its executable matches an older child.
                    // Applied identities are excluded above, preserving later mixer changes on those identities.
                    saved.attempted=true;Save(file,journal); // Persist possible partial writes before touching any role.
                    for(int role=0;role<3;role++)if(!String.Equals(Unpacked(policy.Get(saved.pid,role)),journal.cableId,StringComparison.OrdinalIgnoreCase))policy.Set(saved.pid,role,Packed(journal.cableId));
                    saved.applied=true;changed=true;
                }
                if(changed||journal.status!="active"){journal.status="active";Save(file,journal);}string encoded=Encoding.UTF8.GetString(Json(state,typeof(RouteStatus)));long now=Stopwatch.GetTimestamp();if(encoded!=previous||now>=nextCheck){Print(state);previous=encoded;nextCheck=now+Stopwatch.Frequency*2;}
                Thread.Sleep(350);
            }}finally{RestoreRoutes(journal,file,policy,handles);Print(new RouteStatus{status="restored",ready=false,sourcePid=source});}
        }return 0;}catch(Exception error){try{using(PerAppPolicy policy=new PerAppPolicy())RestoreRoutes(journal,file,policy,handles);}catch{}Print(new RouteStatus{status="failed",ready=false,sourcePid=source,hresult=Marshal.GetHRForException(error)});return 5;}
        finally{if(watcher!=null){try{watcher.StandardInput.WriteLine("Q");watcher.StandardInput.Flush();watcher.StandardInput.Close();}catch{}watcher.Dispose();}foreach(Process process in handles.Values)process.Dispose();mutex.ReleaseMutex();mutex.Dispose();}
    }
    static bool AddProcesses(HashSet<int> pids,RouteJournal journal,Dictionary<int,Process> handles,PerAppPolicy policy){bool changed=false;foreach(int pid in pids){if(handles.ContainsKey(pid))continue;Process process=null;try{process=Process.GetProcessById(pid);IntPtr keep=process.Handle;long created=process.StartTime.ToUniversalTime().Ticks;string path=process.MainModule.FileName;if(!Alive(pid,created))continue;string[] original=null;foreach(SavedRoute saved in journal.processes)if(String.Equals(saved.path,path,StringComparison.OrdinalIgnoreCase)){original=(string[])saved.original.Clone();break;}if(original==null){original=new string[3];for(int role=0;role<3;role++)original[role]=policy.Get(pid,role);}journal.processes.Add(new SavedRoute{pid=pid,start=created,path=path,original=original});handles.Add(pid,process);process=null;changed=true;}catch(ArgumentException){}catch(InvalidOperationException){}finally{if(process!=null)process.Dispose();}}return changed;}
    static void RestoreRoutes(RouteJournal journal,string file,PerAppPolicy policy,Dictionary<int,Process> retained=null){ValidateJournal(journal,journal.ownerPid);int failed=0;HashSet<string> completed=new HashSet<string>(StringComparer.OrdinalIgnoreCase);foreach(SavedRoute saved in journal.processes){
        if(!completed.Add(saved.path))continue;
        if(journal.attemptTracking){bool touched=false;foreach(SavedRoute candidate in journal.processes)if(String.Equals(candidate.path,saved.path,StringComparison.OrdinalIgnoreCase))touched|=candidate.attempted||candidate.applied;if(!touched)continue;}
        try{
            // A browser's parent can lack an audio-policy identity even while its child is active.
            // Prefer every live audio identity, then try dormant/restarted instances of that exact exe.
            HashSet<int> candidates=FindProcesses(saved.path,journal.ownerPid),active;
            Inspect(0,journal.ownerPid,journal.cableId,candidates,policy,out active);
            if(candidates.Count==0){failed++;continue;}
            bool restored=false;
            foreach(int pid in active.Count>0?active:candidates)try{
                using(Process identity=Process.GetProcessById(pid)){IntPtr keep=identity.Handle;
                    if(!String.Equals(identity.MainModule.FileName,saved.path,StringComparison.OrdinalIgnoreCase))continue;
                    bool known=false,matching=false;foreach(SavedRoute old in journal.processes)if(old.pid==pid){known=true;matching|=identity.StartTime.ToUniversalTime().Ticks==old.start;}
                    if(known&&!matching)continue;
                    for(int role=0;role<3;role++){string current=policy.Get(pid,role);if(String.Equals(Unpacked(current),journal.cableId,StringComparison.OrdinalIgnoreCase))policy.Set(pid,role,saved.original[role]);}
                    restored=true;
                }
            }catch{if(active.Contains(pid)){failed++;}}
            if(!restored)failed++;
        }catch{failed++;}
        }journal.restored=failed==0;journal.status=journal.restored?"restored":"restorePending";Save(file,journal);if(failed!=0)throw new IOException("Some application routes could not be restored");
    }
    static HashSet<int> FindProcesses(string path,int owner){HashSet<int> found=new HashSet<int>();foreach(Process process in Process.GetProcesses())using(process)try{if(process.Id!=owner&&process.Id!=Process.GetCurrentProcess().Id&&String.Equals(process.MainModule.FileName,path,StringComparison.OrdinalIgnoreCase))found.Add(process.Id);}catch{}return found;}
    static string Quote(string value){return "\""+value.Replace("\\\"","\\\\\"")+"\"";}
    static volatile bool syncRequested;
    static void WatchControl(){try{string line;while((line=Console.ReadLine())!=null){if(line=="Q")break;if(line=="P"||line=="SYNC")Interlocked.Exchange(ref heartbeat,Stopwatch.GetTimestamp());if(line=="SYNC")syncRequested=true;}}catch{}finally{quit=true;}}
    static void HoldProcesses(RouteJournal journal,Dictionary<int,Process> handles){foreach(SavedRoute saved in journal.processes)if(!handles.ContainsKey(saved.pid)&&Alive(saved.pid,saved.start)){Process process=null;try{process=Process.GetProcessById(saved.pid);IntPtr keep=process.Handle;if(process.StartTime.ToUniversalTime().Ticks!=saved.start||!String.Equals(process.MainModule.FileName,saved.path,StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("Changed process identity");handles.Add(saved.pid,process);process=null;}finally{if(process!=null)process.Dispose();}}}
    static int Watch(string file,int owner,int parent,string lease,long parentStart){
        Dictionary<int,Process> handles=new Dictionary<int,Process>();RouteJournal observed=Read<RouteJournal>(file);HoldProcesses(observed,handles);new Thread(WatchControl){IsBackground=true}.Start();Console.WriteLine("WATCHING");Console.Out.Flush();
        try{while(!quit&&Alive(parent,parentStart)&&Alive(owner,observed.ownerStart)&&(Stopwatch.GetTimestamp()-Interlocked.Read(ref heartbeat))/(double)Stopwatch.Frequency<8){
            if(syncRequested){syncRequested=false;observed=Read<RouteJournal>(file);if(observed.lease!=lease||observed.status=="restored")return 0;HoldProcesses(observed,handles);Console.WriteLine("SYNCED");Console.Out.Flush();}Thread.Sleep(100);
        }}catch{}finally{
            try{RouteJournal current=Read<RouteJournal>(file);if(current.lease==lease&&current.status!="restored"){
                if(Alive(parent,parentStart))try{using(Process process=Process.GetProcessById(parent)){if(String.Equals(process.MainModule.FileName,Process.GetCurrentProcess().MainModule.FileName,StringComparison.OrdinalIgnoreCase)){process.Kill();process.WaitForExit(1500);}}}catch{}
                using(Mutex mutex=new Mutex(false,MutexName()))if(Acquire(mutex,2500)){try{using(PerAppPolicy policy=new PerAppPolicy())RestoreRoutes(current,file,policy,handles);}finally{mutex.ReleaseMutex();}}
            }}catch{}foreach(Process process in handles.Values)process.Dispose();
        }return 0;
    }
    static RouteStatus Inspect(int source,int owner,string cable,HashSet<int> pids,PerAppPolicy policy){HashSet<int> active;return Inspect(source,owner,cable,pids,policy,out active);}
    static RouteStatus Inspect(int source,int owner,string cable,HashSet<int> pids,PerAppPolicy policy,out HashSet<int> activePids){RouteStatus status=new RouteStatus{sourcePid=source,status="waitingApp"};activePids=new HashSet<int>();foreach(int pid in pids){try{bool all=true;for(int role=0;role<3;role++)all&=String.Equals(Unpacked(policy.Get(pid,role)),cable,StringComparison.OrdinalIgnoreCase);if(all)status.routedProcesses++;}catch{}}
        IMMDeviceEnumerator enumerator=null;IMMDeviceCollection endpoints=null;try{enumerator=(IMMDeviceEnumerator)new MMDeviceEnumerator();Check(enumerator.EnumAudioEndpoints(0,1,out endpoints));uint count;Check(endpoints.GetCount(out count));for(uint i=0;i<count;i++){
            IMMDevice device=null;object managerObject=null;IAudioSessionEnumerator sessions=null;try{Check(endpoints.Item(i,out device));string id;Check(device.GetId(out id));Guid managerId=typeof(IAudioSessionManager2).GUID;Check(device.Activate(ref managerId,23,IntPtr.Zero,out managerObject));Check(((IAudioSessionManager2)managerObject).GetSessionEnumerator(out sessions));int number;Check(sessions.GetCount(out number));for(int j=0;j<number;j++){
                IAudioSessionControl2 control=null;try{Check(sessions.GetSession(j,out control));uint pid;int active;Check(control.GetProcessId(out pid));Check(control.GetState(out active));if(active!=1||!pids.Contains((int)pid))continue;activePids.Add((int)pid);status.activeSessions++;if(String.Equals(id,cable,StringComparison.OrdinalIgnoreCase))status.verifiedSessions++;}finally{Release(control);}
            }}finally{Release(sessions);Release(managerObject);Release(device);}
        }}finally{Release(endpoints);Release(enumerator);}
        // Inactive utility/console children can have no application policy identity at all.
        // Every ACTIVE audio identity must still have all roles persisted AND every live session on CABLE.
        bool persisted=true;foreach(int pid in activePids)try{for(int role=0;role<3;role++)persisted&=String.Equals(Unpacked(policy.Get(pid,role)),cable,StringComparison.OrdinalIgnoreCase);}catch{persisted=false;}
        status.ready=persisted&&status.activeSessions>0&&status.activeSessions==status.verifiedSessions;status.status=status.ready?"routed":"waitingApp";return status;
    }
    [ComImport,Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IAudioSessionManager2 {
        [PreserveSig]int GetAudioSessionControl(IntPtr session,uint flags,out IntPtr control);[PreserveSig]int GetSimpleAudioVolume(IntPtr session,uint flags,out IntPtr volume);[PreserveSig]int GetSessionEnumerator(out IAudioSessionEnumerator sessions);[PreserveSig]int RegisterSessionNotification(IntPtr callback);[PreserveSig]int UnregisterSessionNotification(IntPtr callback);[PreserveSig]int RegisterDuckNotification(IntPtr id,IntPtr callback);[PreserveSig]int UnregisterDuckNotification(IntPtr callback);
    }
    [ComImport,Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IAudioSessionEnumerator {[PreserveSig]int GetCount(out int count);[PreserveSig]int GetSession(int index,out IAudioSessionControl2 control);}
    [ComImport,Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IAudioSessionControl2 {
        [PreserveSig]int GetState(out int state);[PreserveSig]int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)]out string name);[PreserveSig]int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)]string name,IntPtr context);[PreserveSig]int GetIconPath([MarshalAs(UnmanagedType.LPWStr)]out string path);[PreserveSig]int SetIconPath([MarshalAs(UnmanagedType.LPWStr)]string path,IntPtr context);[PreserveSig]int GetGroupingParam(out Guid group);[PreserveSig]int SetGroupingParam(ref Guid group,IntPtr context);[PreserveSig]int RegisterAudioSessionNotification(IntPtr callback);[PreserveSig]int UnregisterAudioSessionNotification(IntPtr callback);[PreserveSig]int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)]out string id);[PreserveSig]int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)]out string id);[PreserveSig]int GetProcessId(out uint pid);[PreserveSig]int IsSystemSoundsSession();[PreserveSig]int SetDuckingPreference([MarshalAs(UnmanagedType.Bool)]bool preference);
    }
    [ComImport,Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]class MMDeviceEnumerator{}
    [ComImport,Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IMMDeviceEnumerator {
        [PreserveSig]int EnumAudioEndpoints(int flow,uint mask,out IMMDeviceCollection collection);[PreserveSig]int GetDefaultAudioEndpoint(int flow,int role,out IMMDevice device);[PreserveSig]int GetDevice([MarshalAs(UnmanagedType.LPWStr)]string id,out IMMDevice device);[PreserveSig]int RegisterEndpointNotificationCallback(IntPtr callback);[PreserveSig]int UnregisterEndpointNotificationCallback(IntPtr callback);
    }
    [ComImport,Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IMMDeviceCollection {[PreserveSig]int GetCount(out uint count);[PreserveSig]int Item(uint index,out IMMDevice device);}
    [ComImport,Guid("D666063F-1587-4E43-81F1-B948E807363F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IMMDevice {
        [PreserveSig]int Activate(ref Guid iid,uint context,IntPtr parameters,[MarshalAs(UnmanagedType.IUnknown)]out object instance);[PreserveSig]int OpenPropertyStore(uint access,out IPropertyStore store);[PreserveSig]int GetId([MarshalAs(UnmanagedType.LPWStr)]out string id);[PreserveSig]int GetState(out uint state);
    }
    [ComImport,Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IPropertyStore {
        [PreserveSig]int GetCount(out uint count);[PreserveSig]int GetAt(uint index,out PropertyKey key);[PreserveSig]int GetValue(ref PropertyKey key,out Variant value);[PreserveSig]int SetValue(ref PropertyKey key,ref Variant value);[PreserveSig]int Commit();
    }
    [ComImport,Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")]class PolicyConfigClient{}
    [ComImport,Guid("F8679F50-850A-41CF-9C72-430F290290C8"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]interface IPolicyConfig {
        [PreserveSig]int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)]string id,out IntPtr format);[PreserveSig]int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)]string id,int mode,out IntPtr format);[PreserveSig]int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)]string id);[PreserveSig]int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)]string id,IntPtr device,IntPtr mix);[PreserveSig]int GetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)]string id,int mode,out long normal,out long minimum);[PreserveSig]int SetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)]string id,ref long period);[PreserveSig]int GetShareMode([MarshalAs(UnmanagedType.LPWStr)]string id,IntPtr mode);[PreserveSig]int SetShareMode([MarshalAs(UnmanagedType.LPWStr)]string id,IntPtr mode);[PreserveSig]int GetPropertyValue([MarshalAs(UnmanagedType.LPWStr)]string id,ref PropertyKey key,out Variant value);[PreserveSig]int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)]string id,ref PropertyKey key,ref Variant value);[PreserveSig]int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)]string id,int role);[PreserveSig]int SetEndpointVisibility([MarshalAs(UnmanagedType.LPWStr)]string id,int visible);
    }
}
