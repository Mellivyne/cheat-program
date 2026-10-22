package com.cheatprogram.media.player;

import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import java.io.IOException;
import java.util.List;

/** Unsupported authentication is explicit, never a pretend signed-in account or sample library. */
public final class UnavailableMusicProvider implements MusicProvider {
    private final Provider provider;
    public UnavailableMusicProvider(Provider provider){this.provider=provider;}
    @Override public Provider provider(){return provider;}
    @Override public boolean supported(){return false;}
    @Override public String requirement(){return "ui.musicPlayer.providerPending";}
    private IOException unavailable(){return new IOException(requirement());}
    @Override public Login beginLogin()throws IOException{throw unavailable();}
    @Override public Login pollLogin(Login login)throws IOException{throw unavailable();}
    @Override public Account account()throws IOException{throw unavailable();}
    @Override public List<Track> liked()throws IOException{throw unavailable();}
    @Override public List<Playlist> playlists()throws IOException{throw unavailable();}
    @Override public List<Track> tracks(Playlist playlist)throws IOException{throw unavailable();}
    @Override public LrcLyrics lyrics(Track track)throws IOException{throw unavailable();}
    @Override public Media media(Track track)throws IOException{throw unavailable();}
}
