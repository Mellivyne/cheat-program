package com.cheatprogram.media.player;

import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import java.io.IOException;
import java.util.List;

/** Blocking provider operations are called only on the player's bounded worker. */
public interface MusicProvider {
    Provider provider();
    default boolean supported(){return true;}
    Login beginLogin() throws Exception;
    Login pollLogin(Login login) throws Exception;
    Account account() throws Exception;
    List<Track> liked() throws Exception;
    List<Playlist> playlists() throws Exception;
    List<Track> tracks(Playlist playlist) throws Exception;
    LrcLyrics lyrics(Track track) throws Exception;
    /** Local capability metadata; unsupported providers never synthesize catalog or lyric results. */
    default boolean supportsSearch() { return false; }
    default boolean supportsRadio() { return false; }
    default boolean supportsTranslatedLyrics() { return false; }
    /** One zero-based page of at most 30 exact provider tracks; implementations cap page numbers. */
    default SearchPage search(String query,int page) throws Exception { throw new IOException("ui.musicPlayer.searchUnsupported"); }
    /** One bounded batch from this provider's own personalized radio service. */
    default List<Track> radio() throws Exception { throw new IOException("ui.musicPlayer.radioUnsupported"); }
    /** Only the supplied track ID's platform translation; no inferred translations or song matching. */
    default LrcLyrics translatedLyrics(Track track) throws Exception { throw new IOException("ui.musicPlayer.translatedLyricsUnsupported"); }
    Media media(Track track) throws Exception;
    /** Local capability metadata only; render-thread callers must never trigger a network request. */
    default List<Quality> qualities(Track track) { return List.of(); }
    default Media media(Track track,String quality) throws Exception { return media(track); }
    default void logout() throws Exception { }
    default String requirement(){return "";}
}
