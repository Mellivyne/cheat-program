package com.cheatprogram.media.player;

import com.cheatprogram.media.LrcLyrics;
import java.util.List;

/** Provider IDs stay attached to every item; no cross-platform matching or substituted songs. */
public final class MusicModels {
    private MusicModels() { }
    public enum Provider {
        NETEASE("netease","网易云音乐","https://music.163.com/"),
        QQ("qq","QQ音乐","https://y.qq.com/"),
        KUGOU("kugou","酷狗音乐","https://www.kugou.com/"),
        QISHUI("qishui","汽水音乐","https://qishui.douyin.com/"),
        KUWO("kuwo","酷我音乐","https://www.kuwo.cn/"),
        SPOTIFY("spotify","Spotify","https://open.spotify.com/");
        public final String id,label,home;
        Provider(String id,String label,String home){this.id=id;this.label=label;this.home=home;}
    }
    public record Account(String id,String name,String avatar) { }
    public record Track(Provider provider,String id,String title,String artist,String album,String cover,long durationMs) {
        public String key(){return provider.id+":"+id;}
    }
    public record Playlist(String id,String name,String cover,int count) { }
    public record SearchPage(List<Track> tracks,int page,boolean hasMore) {public SearchPage{tracks=tracks==null?List.of():List.copyOf(tracks);}}
    public record Login(String key,String url,byte[] qrPng,String status,boolean complete) {
        public Login {qrPng=qrPng==null?new byte[0]:qrPng.clone();}
        @Override public byte[] qrPng(){return qrPng.clone();}
        @Override public String toString(){return "MusicLogin[status="+status+",complete="+complete+"]";}
    }
    /** IDs and names belong to the source platform; an empty ID means its default choice. */
    public record Quality(String id,String label) { }
    /** quality is the platform-confirmed returned tier, not merely the requested tier. */
    public record Media(String url,String format,String quality) {
        public Media(String url,String format) { this(url,format,""); }
        @Override public String toString(){return "MusicMedia[format="+format+"]";}
    }
    public record Playback(Track track,boolean playing,long positionMs,long sampledNanos,long durationMs,long revision,String status) {
        public long position(){if(track==null)return 0;long extra=playing?Math.max(0,(System.nanoTime()-sampledNanos)/1_000_000):0;
            long value=Math.max(0,positionMs+extra);return durationMs>0?Math.min(durationMs,value):value;}
    }
    public record Snapshot(Provider provider,Account account,List<Track> tracks,List<Playlist> playlists,
                           Playback playback,LrcLyrics lyrics,Login login,String status,boolean busy,long generation) {
        public Snapshot {tracks=List.copyOf(tracks);playlists=List.copyOf(playlists);}
    }
}
