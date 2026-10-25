// Original interop implementation of the documented Windows process-loopback API.
// No endpoint/system loopback fallback: Minecraft's replay is never captured.
using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Diagnostics;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class ProcessAudio {
    const int Magic=0x31415043; // CPA1, every packet is 40 bytes plus PCM bytes.
    static volatile bool quit; static long heartbeat=Stopwatch.GetTimestamp();
    static BinaryWriter output; static long sequence; static string stage="start";
    [DllImport("mmdevapi.dll",CharSet=CharSet.Unicode,ExactSpelling=true)] static extern int ActivateAudioInterfaceAsync(string path,ref Guid iid,ref PropVariant parameters,ICompletionHandler handler,out IAsyncOperation operation);
    [DllImport("ntdll.dll")] static extern int RtlGetVersion(ref OsVersion version);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct OsVersion {public uint size,major,minor,build,platform;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string service;}
    [StructLayout(LayoutKind.Explicit,Size=24)] struct PropVariant {[FieldOffset(0)]public ushort type;[FieldOffset(8)]public uint length;[FieldOffset(16)]public IntPtr data;}
    [StructLayout(LayoutKind.Sequential,Pack=2)] public struct WaveFormat {public ushort tag,channels;public uint rate,bytesPerSecond;public ushort align,bits,extra;}
    [ComVisible(true),Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] public interface ICompletionHandler {[PreserveSig]int ActivateCompleted(IAsyncOperation operation);}
    [ComVisible(true),Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] public interface IAgileObject {}
    [ComImport,Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] public interface IAsyncOperation {[PreserveSig]int GetActivateResult(out int result,[MarshalAs(UnmanagedType.IUnknown)]out object instance);}
    [ComImport,Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] public interface IAudioClient {
        [PreserveSig]int Initialize(int mode,uint flags,long duration,long periodicity,ref WaveFormat format,IntPtr session);
        [PreserveSig]int GetBufferSize(out uint frames);[PreserveSig]int GetStreamLatency(out long latency);[PreserveSig]int GetCurrentPadding(out uint padding);
        [PreserveSig]int IsFormatSupported(int mode,IntPtr format,IntPtr closest);[PreserveSig]int GetMixFormat(out IntPtr format);[PreserveSig]int GetDevicePeriod(out long normal,out long minimum);
        [PreserveSig]int Start();[PreserveSig]int Stop();[PreserveSig]int Reset();[PreserveSig]int SetEventHandle(IntPtr handle);
        [PreserveSig]int GetService(ref Guid iid,[MarshalAs(UnmanagedType.IUnknown)]out object service);
    }
    [ComImport,Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface ICapture {
        [PreserveSig]int GetBuffer(out IntPtr data,out uint frames,out uint flags,out ulong devicePosition,out ulong qpcPosition);
        [PreserveSig]int ReleaseBuffer(uint frames);[PreserveSig]int GetNextPacketSize(out uint frames);
    }
    [ComVisible(true),ClassInterface(ClassInterfaceType.None)] public sealed class Completion:ICompletionHandler,IAgileObject {
        public readonly ManualResetEvent Done=new ManualResetEvent(false);public int Result=unchecked((int)0x80004005);public IAudioClient Client;
        public int ActivateCompleted(IAsyncOperation operation){try{object instance;int result;Check(operation.GetActivateResult(out result,out instance));Check(result);Client=(IAudioClient)instance;Result=0;}catch(Exception error){Result=Marshal.GetHRForException(error);}finally{Done.Set();}return 0;}
    }
    static void Check(int value){if(value<0)Marshal.ThrowExceptionForHR(value);}
    static bool Alive(int pid){try{using(Process p=Process.GetProcessById(pid))return !p.HasExited;}catch{return false;}}
    static bool LeaseAlive(Process owner){return !quit&&!owner.HasExited&&(Stopwatch.GetTimestamp()-Interlocked.Read(ref heartbeat))/(double)Stopwatch.Frequency<8;}
    static void Input(){try{string line;while((line=Console.ReadLine())!=null){if(line=="Q")break;if(line=="P")Interlocked.Exchange(ref heartbeat,Stopwatch.GetTimestamp());}}catch{}finally{quit=true;}}
    static void Packet(int status,byte[] bytes,int frames,int error){lock(output){output.Write(Magic);output.Write(status);output.Write(48000);output.Write(2);output.Write(frames);output.Write(bytes==null?0:bytes.Length);output.Write(error);output.Write(0);output.Write(++sequence);if(bytes!=null)output.Write(bytes);output.Flush();}}
    static int Build(){OsVersion v=new OsVersion();v.size=(uint)Marshal.SizeOf(typeof(OsVersion));return RtlGetVersion(ref v)==0?(int)v.build:0;}
    [MTAThread] static int Main(string[] args){
        try{
            if(args.Length>0&&args[0]=="tone")return Tone();
            output=new BinaryWriter(Console.OpenStandardOutput());
            if(args.Length!=4||args[0]!="capture"){Packet(104,null,0,0);return 2;}
            int target=int.Parse(args[1]),owner=int.Parse(args[2]);bool suppress=args[3]=="1";
            if(Build()<20348){Packet(101,null,0,0);return 3;}
            if(target<=4||owner<=4||target==owner||target==Process.GetCurrentProcess().Id||!Alive(target)||!Alive(owner)){Packet(104,null,0,0);return 4;}
            // Hold both kernel identities before checking the tree or activating capture.
            using(Process sourceIdentity=Process.GetProcessById(target))using(Process ownerIdentity=Process.GetProcessById(owner)){
            IntPtr sourceHandle=sourceIdentity.Handle,ownerHandle=ownerIdentity.Handle;
            if(sourceIdentity.HasExited||ownerIdentity.HasExited){Packet(104,null,0,0);return 4;}
            int captureRoot=ApplicationAudioTree.Root(target,owner);
            using(Process rootIdentity=Process.GetProcessById(captureRoot)){IntPtr rootHandle=rootIdentity.Handle;
            HashSet<int> targetTree=Descendants(captureRoot);if(targetTree.Contains(owner)||targetTree.Contains(Process.GetCurrentProcess().Id)){Packet(104,null,0,0);return 4;}
            // A session mute silences process-loopback too (verified with our own synthetic source).
            // Do not silently duplicate sound or alter the application when isolated routing is absent.
            if(suppress){Packet(103,null,0,0);return 5;}
            new Thread(Input){IsBackground=true,Name="Audio control"}.Start();
            using(Capture capture=new Capture(captureRoot)){
                capture.Start();Packet(2,null,0,0);long lastPacket=Stopwatch.GetTimestamp();
                while(LeaseAlive(ownerIdentity)&&!sourceIdentity.HasExited&&!rootIdentity.HasExited){
                    long now=Stopwatch.GetTimestamp();byte[] data;int frames;
                    if(capture.Next(out data,out frames)){Packet(1,data,frames,0);lastPacket=now;}
                    else if((now-lastPacket)/(double)Stopwatch.Frequency>1){Packet(3,null,0,0);lastPacket=now;}
                }
            }
            }
            }
            Packet(4,null,0,0);return 0;
        }catch(Exception error){try{Console.Error.WriteLine("AUDIO: "+stage+" 0x"+Marshal.GetHRForException(error).ToString("X8"));if(output!=null)Packet(102,null,0,Marshal.GetHRForException(error));}catch{}return 6;}
    }
    sealed class Capture:IDisposable {
        IAudioClient client;ICapture capture;readonly AutoResetEvent ready=new AutoResetEvent(false);object operation;
        public Capture(int pid){IntPtr parameters=Marshal.AllocCoTaskMem(12);try{
            Marshal.WriteInt32(parameters,0,1);Marshal.WriteInt32(parameters,4,pid);Marshal.WriteInt32(parameters,8,0);
            PropVariant variant=new PropVariant();variant.type=65;variant.length=12;variant.data=parameters;Completion completion=new Completion();Guid iid=typeof(IAudioClient).GUID;IAsyncOperation pending;
            stage="activate";Check(ActivateAudioInterfaceAsync("VAD\\Process_Loopback",ref iid,ref variant,completion,out pending));operation=pending;
            if(!completion.Done.WaitOne(6000))throw new TimeoutException("Process audio activation timed out");Check(completion.Result);client=completion.Client;
            WaveFormat format=new WaveFormat();format.tag=1;format.channels=2;format.rate=48000;format.bits=16;format.align=4;format.bytesPerSecond=192000;
            stage="initialize";// PCM conversion plus the documented high-quality SRC path for audible playback.
            Check(client.Initialize(0,0x88060000u,0,0,ref format,IntPtr.Zero));Guid captureId=typeof(ICapture).GUID;object service;stage="service";Check(client.GetService(ref captureId,out service));capture=(ICapture)service;stage="event";Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()));GC.KeepAlive(completion);
        }finally{Marshal.FreeCoTaskMem(parameters);}}
        public void Start(){Check(client.Start());}
        public bool Next(out byte[] data,out int frames){data=null;frames=0;uint available;Check(capture.GetNextPacketSize(out available));if(available==0){ready.WaitOne(15);Check(capture.GetNextPacketSize(out available));if(available==0)return false;}
            IntPtr pointer;uint count,flags;ulong position,time;Check(capture.GetBuffer(out pointer,out count,out flags,out position,out time));try{if(count>48000)throw new IOException("Oversized audio packet");frames=(int)count;data=new byte[frames*4];if((flags&2)==0&&pointer!=IntPtr.Zero)Marshal.Copy(pointer,data,0,data.Length);}finally{Check(capture.ReleaseBuffer(count));}return true;
        }
        public void Dispose(){if(client!=null)try{client.Stop();}catch{}Release(capture);Release(client);Release(operation);capture=null;client=null;operation=null;ready.Dispose();}
    }
    static void Release(object value){if(value!=null&&Marshal.IsComObject(value))try{Marshal.ReleaseComObject(value);}catch{}}
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct ProcessEntry{public uint size,usage,pid;public UIntPtr heap;public uint module,threads,parent;public int priority;public uint flags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]public string name;}
    [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr CreateToolhelp32Snapshot(uint flags,uint pid);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool Process32FirstW(IntPtr snapshot,ref ProcessEntry entry);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool Process32NextW(IntPtr snapshot,ref ProcessEntry entry);
    [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
    static HashSet<int> Descendants(int root){HashSet<int> found=new HashSet<int>();found.Add(root);IntPtr snapshot=CreateToolhelp32Snapshot(2,0);if(snapshot==new IntPtr(-1))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());try{Dictionary<int,int> parents=new Dictionary<int,int>();ProcessEntry entry=new ProcessEntry();entry.size=(uint)Marshal.SizeOf(typeof(ProcessEntry));if(!Process32FirstW(snapshot,ref entry))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());do{parents[(int)entry.pid]=(int)entry.parent;}while(Process32NextW(snapshot,ref entry));bool again=true;while(again){again=false;foreach(KeyValuePair<int,int> pair in parents)if(found.Contains(pair.Value)&&found.Add(pair.Key))again=true;}}finally{CloseHandle(snapshot);}return found;}
    static int Tone(){// Used only by an explicit synthetic test; never captures another program.
        using(MemoryStream stream=new MemoryStream())using(BinaryWriter writer=new BinaryWriter(stream)){
            int count=48000*12;writer.Write(Encoding.ASCII.GetBytes("RIFF"));writer.Write(36+count*4);writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));writer.Write(16);writer.Write((short)1);writer.Write((short)2);writer.Write(48000);writer.Write(192000);writer.Write((short)4);writer.Write((short)16);writer.Write(Encoding.ASCII.GetBytes("data"));writer.Write(count*4);for(int i=0;i<count;i++){short sample=(short)(Math.Sin(i*2*Math.PI*440/48000)*300);writer.Write(sample);writer.Write(sample);}writer.Flush();stream.Position=0;using(System.Media.SoundPlayer player=new System.Media.SoundPlayer(stream))player.PlaySync();
        }return 0;
    }
}

