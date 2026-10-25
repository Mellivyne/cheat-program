using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.Runtime.InteropServices;

// A window can belong to a UI child while an audio service is its sibling.
// Ascend only through the exact same executable and Windows session. Never
// expand to Explorer, a launcher, or every process with a matching short name.
internal static class ApplicationAudioTree {
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct Entry {
        public uint size,usage,pid;public UIntPtr heap;public uint module,threads,parent;public int priority;public uint flags;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]public string name;
    }
    [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr CreateToolhelp32Snapshot(uint flags,uint pid);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode)]static extern bool Process32FirstW(IntPtr snapshot,ref Entry entry);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode)]static extern bool Process32NextW(IntPtr snapshot,ref Entry entry);
    [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
    internal static int Root(int selected,int owner) {
        var parents=new Dictionary<int,int>();IntPtr snapshot=CreateToolhelp32Snapshot(2,0);
        if(snapshot==new IntPtr(-1))return selected;
        try {Entry entry=new Entry();entry.size=(uint)Marshal.SizeOf(typeof(Entry));if(Process32FirstW(snapshot,ref entry))do{parents[(int)entry.pid]=(int)entry.parent;}while(Process32NextW(snapshot,ref entry));}
        finally{CloseHandle(snapshot);}
        try {
            using(Process child=Process.GetProcessById(selected)) {
                string path=child.MainModule.FileName;int session=child.SessionId,root=selected;long started=child.StartTime.ToUniversalTime().Ticks;
                var seen=new HashSet<int>();seen.Add(root);
                int parent;
                while(parents.TryGetValue(root,out parent)&&parent>4&&parent!=owner&&parent!=Process.GetCurrentProcess().Id&&seen.Add(parent)) {
                    try {using(Process candidate=Process.GetProcessById(parent)) {
                        long time=candidate.StartTime.ToUniversalTime().Ticks;
                        if(candidate.HasExited||candidate.SessionId!=session||time>started||!String.Equals(candidate.MainModule.FileName,path,StringComparison.OrdinalIgnoreCase))break;
                        root=parent;started=time;
                    }}catch{break;}
                }
                return root;
            }
        }catch{return selected;}
    }
}
