using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Automation;

// UIA only: no audio, OCR, network, playback controls, injection, or main-player window hiding.
public static class CheatProgramPlayerLyrics {
    public delegate bool EnumProc(IntPtr window,IntPtr data);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback,IntPtr data);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint process);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window,StringBuilder value,int count);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window,StringBuilder value,int count);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr window,int index);
    [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr window,uint command);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window,out Rect rect);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr window,int command);
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left,Top,Right,Bottom; }
    public sealed class Lease { public long window;public int pid;public long started;public string windowClass;public long expires;public bool hidden; }
    static readonly object Gate=new object(),Output=new object();
    static volatile bool Stopping;
    static long AckSequence,AckTicks,Sequence;
    static long MetadataRevision;
    static Lease Hidden;
    static string Journal;
    static Process Guardian;
    static int Owner;
    static string Text="";
    static long Window;
    static bool CannotHide;
    static long HiddenAt;
    static string HiddenText="";
    static string SelectedFamily="",BlockedText="";
    static string TrackTitle="",TrackArtist="";
    static int SelectedProcess;
    static bool ValidCandidate(IntPtr window){
        uint pid;GetWindowThreadProcessId(window,out pid);Rect rect;
        if(!IsWindow(window)||!GetWindowRect(window,out rect)||SelectedProcess>0&&pid!=(uint)SelectedProcess)return false;
        try{using(var process=Process.GetProcessById((int)pid)){
            if(process.ProcessName!=SelectedFamily)return false;
            return LyricWindowShape(Caption(window),WindowClass(window),GetWindowLong(window,-16),GetWindowLong(window,-20),rect.Right-rect.Left,rect.Bottom-rect.Top,process.MainWindowHandle==window);
        }}catch{return false;}
    }
    static string WindowClass(IntPtr window){var value=new StringBuilder(256);GetClassName(window,value,value.Capacity);return value.ToString();}
    static string Caption(IntPtr window){var value=new StringBuilder(512);GetWindowText(window,value,value.Capacity);return value.ToString();}
    static long Ticks(){return DateTime.UtcNow.Ticks;}
    static Process OpenProcess(int pid){try{var result=Process.GetProcessById(pid);var handle=result.Handle;return result;}catch{return null;}}
    static bool Alive(Process process){try{return process!=null&&!process.HasExited;}catch{return false;}}
    static bool MatchWindow(Lease lease){
        IntPtr window=new IntPtr(lease.window);uint pid;GetWindowThreadProcessId(window,out pid);
        if(!IsWindow(window)||pid!=(uint)lease.pid||WindowClass(window)!=lease.windowClass)return false;
        try{using(var process=Process.GetProcessById(lease.pid))return process.StartTime.ToUniversalTime().Ticks==lease.started;}catch{return false;}
    }
    public static bool LyricWindowShape(string caption,string windowClass,int style,int extended,int width,int height,bool main){
        bool lyricClass=windowClass.IndexOf("lyric",StringComparison.OrdinalIgnoreCase)>=0;
        bool lyricCaption=caption=="桌面歌词"||caption=="桌面歌詞"||caption=="Desktop Lyrics"||caption=="Lyrics"||caption=="歌詞"||caption=="歌词";
        bool tool=(extended&0x80)!=0;bool popup=(style&unchecked((int)0x80000000))!=0;
        return !main&&(lyricClass||lyricCaption)&&(tool||popup)&&(style&0x40000000)==0&&width>=40&&width<=2400&&height>=15&&height<=700;
    }
    static IntPtr FindWindow(string family){
        var ids=new Dictionary<int,long>();var main=new HashSet<long>();
        try{foreach(var process in Process.GetProcessesByName(family)){using(process){try{if(SelectedProcess>0&&process.Id!=SelectedProcess)continue;ids[process.Id]=process.StartTime.ToUniversalTime().Ticks;main.Add(process.MainWindowHandle.ToInt64());}catch{}}}}catch{}
        IntPtr result=IntPtr.Zero;int count=0,matches=0;
        EnumProc callback=delegate(IntPtr window,IntPtr data){
            if(++count>2048)return false;uint pid;GetWindowThreadProcessId(window,out pid);
            if(!ids.ContainsKey((int)pid)||!IsWindowVisible(window))return true;
            Rect rect;if(!GetWindowRect(window,out rect))return true;
            if(LyricWindowShape(Caption(window),WindowClass(window),GetWindowLong(window,-16),GetWindowLong(window,-20),rect.Right-rect.Left,rect.Bottom-rect.Top,main.Contains(window.ToInt64()))){result=window;matches++;}
            return true;
        };
        EnumWindows(callback,IntPtr.Zero);GC.KeepAlive(callback);return matches==1?result:IntPtr.Zero;
    }
    public static string FirstLine(string text){
        if(text==null)return "";foreach(string row in text.Split(new char[]{'\r','\n'})){
            string value=row.Trim();if(value.Length<1||value.Length>512)continue;
            bool content=false;foreach(char c in value)if(Char.IsLetterOrDigit(c)){content=true;break;}
            if(content)return value;
        }return "";
    }
    static string ReadText(IntPtr window){
        AutomationElement root=AutomationElement.FromHandle(window);
        if(root==null)return "";
        var pending=new Queue<AutomationElement>();pending.Enqueue(root);int count=0;
        while(pending.Count>0&&count++<128){
            AutomationElement element=pending.Dequeue();ControlType kind=element.Current.ControlType;
            if(kind==ControlType.Menu||kind==ControlType.MenuBar||kind==ControlType.MenuItem||kind==ControlType.ToolBar||kind==ControlType.Button||kind==ControlType.Edit||kind==ControlType.ComboBox||kind==ControlType.TitleBar)continue;
            if(kind==ControlType.Text||kind==ControlType.Document){
                object pattern;string text="";
                if(element.TryGetCurrentPattern(TextPattern.Pattern,out pattern))text=((TextPattern)pattern).DocumentRange.GetText(513);
                if(String.IsNullOrWhiteSpace(text))text=element.Current.Name;
                text=FirstLine(text);if(text.Length>0&&text!=Caption(window)&&!UiLabel(text)&&!Metadata(text))return text;
            }
            AutomationElement child=TreeWalker.ControlViewWalker.GetFirstChild(element);int children=0;
            while(child!=null&&children++<32&&pending.Count<128){pending.Enqueue(child);child=TreeWalker.ControlViewWalker.GetNextSibling(child);}
        }
        return "";
    }
    static bool UiLabel(string text){return text=="锁定歌词"||text=="解锁歌词"||text=="关闭歌词"||text=="桌面歌词"||text=="歌詞"||text=="歌词"||text=="Lyrics"||text=="设置"||text=="Settings"||text=="Lock lyrics"||text=="Unlock lyrics"||text=="Close lyrics";}
    static bool Metadata(string text){return String.Equals(text,TrackTitle,StringComparison.OrdinalIgnoreCase)||String.Equals(text,TrackArtist,StringComparison.OrdinalIgnoreCase);}
    static void Emit(string status,string text){lock(Output){if(text.Length>0&&text==BlockedText){text="";status="unreadable";}Console.WriteLine(new JavaScriptSerializer().Serialize(new {status=status,text=text,sequence=Sequence,metadata=Interlocked.Read(ref MetadataRevision)}));Console.Out.Flush();}}
    static bool Restore(){
        lock(Gate){
            bool restored=true;
            if(Hidden!=null&&Hidden.hidden&&MatchWindow(Hidden)){
                IntPtr window=new IntPtr(Hidden.window);if(!IsWindowVisible(window)){ShowWindow(window,8);restored=IsWindowVisible(window);}
            }
            if(!restored)return false;
            if(Hidden!=null){Hidden.hidden=false;WriteLease();}
            Hidden=null;HiddenAt=0;HiddenText="";return true;
        }
    }
    static void WriteLease(){if(Hidden!=null&&!String.IsNullOrEmpty(Journal)){try{File.WriteAllText(Journal,new JavaScriptSerializer().Serialize(Hidden),new UTF8Encoding(false));}catch{}}}
    static bool Hide(IntPtr window){
        lock(Gate){
            if(!ValidCandidate(window)){Restore();return false;}
            if(Interlocked.Read(ref AckSequence)<=0||Ticks()-Interlocked.Read(ref AckTicks)>TimeSpan.TicksPerSecond){Restore();return false;}
            if(Hidden!=null&&Hidden.window==window.ToInt64()&&Alive(Guardian)){Hidden.expires=Ticks()+TimeSpan.TicksPerSecond*2;WriteLease();return !IsWindowVisible(window);}
            if(!Restore()||!IsWindowVisible(window))return false;
            uint pid;GetWindowThreadProcessId(window,out pid);long started;
            try{using(var process=Process.GetProcessById((int)pid))started=process.StartTime.ToUniversalTime().Ticks;}catch{return false;}
            Hidden=new Lease{window=window.ToInt64(),pid=(int)pid,started=started,windowClass=WindowClass(window),expires=Ticks()+TimeSpan.TicksPerSecond*2,hidden=true};
            Journal=Path.Combine(Path.GetTempPath(),"cheatprogram-lyrics-"+Guid.NewGuid().ToString("N")+".json");WriteLease();
            try{
                string exe=Assembly.GetExecutingAssembly().Location;
                Guardian=Process.Start(new ProcessStartInfo(exe,"guard "+Process.GetCurrentProcess().Id+" "+Owner+" \""+Journal+"\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true});
                long until=Ticks()+TimeSpan.TicksPerSecond;
                while(!File.Exists(Journal+".ready")&&Alive(Guardian)&&Ticks()<until)Thread.Sleep(10);
                if(!File.Exists(Journal+".ready")){Restore();return false;}
                ShowWindow(window,0);HiddenAt=Ticks();HiddenText=Text;return !IsWindowVisible(window);
            }catch{Restore();return false;}
        }
    }
    static int Guard(int watcher,int owner,string file){
        Lease last=null;using(var watch=OpenProcess(watcher))using(var parent=OpenProcess(owner)){
            try{
                last=new JavaScriptSerializer().Deserialize<Lease>(File.ReadAllText(file));if(last==null||!MatchWindow(last))return 1;
                File.WriteAllText(file+".ready","ready");
                while(Alive(watch)&&Alive(parent)){
                    try{var latest=new JavaScriptSerializer().Deserialize<Lease>(File.ReadAllText(file));if(latest!=null)last=latest;}catch{}
                    if(!last.hidden||Ticks()>last.expires)break;Thread.Sleep(100);
                }
            }finally{
                if(last!=null&&last.hidden&&MatchWindow(last)&&!IsWindowVisible(new IntPtr(last.window)))ShowWindow(new IntPtr(last.window),8);
                try{File.Delete(file);File.Delete(file+".ready");}catch{}
            }
        }return 0;
    }
    static void Control(){
        try{string line;while((line=Console.ReadLine())!=null){
            if(line=="Q")break;if(line=="R"){Interlocked.Exchange(ref AckTicks,0);Restore();continue;}
            if(line.StartsWith("M|",StringComparison.Ordinal)){try{string[] fields=line.Split('|');long revision;if(fields.Length==4&&Int64.TryParse(fields[1],out revision)){TrackTitle=Encoding.UTF8.GetString(Convert.FromBase64String(fields[2])).Trim();TrackArtist=Encoding.UTF8.GetString(Convert.FromBase64String(fields[3])).Trim();BlockedText=Text;Interlocked.Exchange(ref AckTicks,0);Interlocked.Exchange(ref MetadataRevision,revision);Restore();}}catch{}continue;}
            if(line.StartsWith("D|",StringComparison.Ordinal)){long sequence;if(Int64.TryParse(line.Substring(2),out sequence)){Interlocked.Exchange(ref AckSequence,sequence);Interlocked.Exchange(ref AckTicks,Ticks());}}
        }}finally{Stopping=true;Restore();}
    }
    static int Run(string family,int owner){
        if(family!="cloudmusic"&&family!="qqmusic"&&family!="spotify"&&family!="applemusic"&&family!="foobar2000"&&family!="musicbee"&&family!="aimp"&&family!="kugou"&&family!="kwmusic")return 2;
        Owner=owner;SelectedFamily=family;var input=new Thread(Control);input.IsBackground=true;input.Start();
        using(var parent=OpenProcess(owner))try{
            IntPtr window=IntPtr.Zero;
            while(!Stopping&&Alive(parent)){
                if(window==IntPtr.Zero||!ValidCandidate(window)){Restore();window=FindWindow(family);Window=window.ToInt64();CannotHide=false;Text="";Interlocked.Exchange(ref AckTicks,0);Sequence++;}
                if(window==IntPtr.Zero){Emit("enableLyrics","");Thread.Sleep(250);continue;}
                string text="";try{text=ReadText(window);}catch{}
                if(text==BlockedText)text="";else if(text.Length>0)BlockedText="";
                if(text.Length==0){if(Hidden!=null)CannotHide=true;if(!Restore())Emit("restoreFailed","");else Emit("unreadable","");Thread.Sleep(250);continue;}
                if(text!=Text){Text=text;Sequence++;if(Hidden!=null&&text!=HiddenText){HiddenText=text;HiddenAt=Ticks();}}
                // A custom renderer may stop updating after its overlay is hidden. If no changed
                // real text verifies continued updates within five seconds, restore it permanently
                // for this window instead of leaving stale text in the game.
                if(Hidden!=null&&Ticks()-HiddenAt>TimeSpan.TicksPerSecond*5){CannotHide=true;Restore();}
                bool acknowledged=Interlocked.Read(ref AckSequence)>0&&Interlocked.Read(ref AckSequence)<=Sequence&&Ticks()-Interlocked.Read(ref AckTicks)<TimeSpan.TicksPerSecond;
                bool hidden=false;if(acknowledged&&!CannotHide)hidden=Hide(window);else if(!Restore()){Emit("restoreFailed","");Thread.Sleep(200);continue;}
                Emit(hidden?"relocated":"live",text);Thread.Sleep(200);
            }
        }finally{Restore();}return 0;
    }
    [MTAThread] public static int Main(string[] args){
        Console.OutputEncoding=new UTF8Encoding(false);
        try{
            if(args.Length==4&&args[0]=="guard")return Guard(Int32.Parse(args[1]),Int32.Parse(args[2]),args[3]);
            if(args.Length==6&&args[0]=="run"){TrackTitle=Encoding.UTF8.GetString(Convert.FromBase64String(args[3])).Trim();TrackArtist=Encoding.UTF8.GetString(Convert.FromBase64String(args[4])).Trim();SelectedProcess=Int32.Parse(args[5]);return Run(args[1],Int32.Parse(args[2]));}
            if(args.Length==1&&args[0]=="selftest"){
                if(!LyricWindowShape("Desktop Lyrics","DesktopLyrics",unchecked((int)0x80000000),0x80,600,100,false))throw new Exception("lyric tool rejected");
                if(LyricWindowShape("Lyrics","MainPlayer",0,0,1200,800,true)||LyricWindowShape("song","MainPlayer",unchecked((int)0x80000000),0,600,100,false))throw new Exception("ordinary window accepted");
                if(FirstLine("original song\ntranslation")!="original song"||FirstLine(" \n🎵\n")!="")throw new Exception("original text filter");
                Console.WriteLine("PASS lyric-window shape, main-window rejection, original first-line text; no desktop inspection");return 0;
            }
            return 2;
        }catch{Emit("failed","");Restore();return 1;}
    }
}
