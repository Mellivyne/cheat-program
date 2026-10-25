package com.cheatprogram.media.player;

import com.cheatprogram.core.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** In-game player. Only confirmed provider accounts and original provider lyrics enter snapshots. */
public final class MusicPlayer {
    private static final LrcLyrics EMPTY_LYRICS = LrcLyrics.parse("");
    private static final ThreadPoolExecutor PREFERENCES=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),MusicAudioPlayer.daemon("music-preferences"),new ThreadPoolExecutor.DiscardOldestPolicy());
    /** Stable stored ids shared by the layout picker and preference validation. */
    public static final List<String> LAYOUTS = List.of("coverflow","ring","wall","floor","stars","spiral","sphere","gallery");
    public static final MusicPlayer INSTANCE = new MusicPlayer();
    private static String status(String name) { return "ui.musicPlayer." + name; }
    private final Object lock = new Object();
    private final Function<Provider, MusicProvider> providers;
    private final Map<Provider,MusicProvider> providerInstances=new ConcurrentHashMap<>();
    private final MusicAudioPlayer audio;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(48), MusicAudioPlayer.daemon("music-provider"));
    private final ThreadPoolExecutor playbackWorker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),MusicAudioPlayer.daemon("music-playback-provider"));
    private final ReentrantReadWriteLock providerAccess=new ReentrantReadWriteLock(true);
    private volatile Job runningSong;
    private record CachedTracks(long time,List<Track> tracks){}
    private record CachedPlaylists(long time,List<Playlist> lists){}
    private final Map<String,CachedTracks> libraryCache=new LinkedHashMap<>();
    private final Map<String,CachedPlaylists> playlistCache=new LinkedHashMap<>();
    public record Discovery(String kind,String query,int page,boolean hasMore) { }
    public record Capabilities(boolean search,boolean radio,boolean translation) { }
    /** Opaque browsing-only bookmark; account and playback state are never restored from it. */
    public static final class LibraryPage {
        private final MusicPlayer owner;
        private final long epoch;
        private final Provider provider;
        private final String accountId,library;
        private final List<Track> tracks;
        private final List<Playlist> playlists;
        private final Discovery discovery;
        private LibraryPage(MusicPlayer owner,long epoch,Provider provider,String accountId,List<Track> tracks,List<Playlist> playlists,String library,Discovery discovery){
            this.owner=owner;this.epoch=epoch;this.provider=provider;this.accountId=accountId;
            this.tracks=List.copyOf(tracks);this.playlists=List.copyOf(playlists);this.library=library;this.discovery=discovery;
        }
    }
    private record CachedSession(Account account,List<Track> tracks,List<Playlist> playlists,String library,List<Quality> qualities,Discovery discovery){}
    private final Map<Provider,CachedSession> sessionCache=new EnumMap<>(Provider.class);
    private CompletableFuture<Boolean> accountVerification=new CompletableFuture<>();
    private boolean accountVerified;
    public record QualitySettings(List<Quality> offered,String requested,String applied){public QualitySettings{offered=List.copyOf(offered);}}
    private final Map<Provider,String> requestedQualities=new EnumMap<>(Provider.class);
    private List<Quality> offeredQualities=List.of();
    private String layout="coverflow";
    private boolean moduleEnabled=true,translationEnabled;
    private LrcLyrics translated=EMPTY_LYRICS;private String translatedTrack="",translationState=status("translatedLyricsDisabled");private long translationRequest;
    private Discovery discovery=new Discovery("library","",0,false);
    private String currentLibrary="";
    private int streamFailures;
    private long lastAudioProgress,recoveryPosition;
    private ScheduledFuture<?> recovery;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, MusicAudioPlayer.daemon("music-login"));
    private final Set<Long> operations = new HashSet<>();
    private volatile Snapshot snapshot;
    private volatile String requirement = "";
    private Provider provider = Provider.NETEASE;
    private MusicProvider service;
    private Account account;
    private List<Track> tracks = List.of();
    private List<Playlist> playlists = List.of();
    private Playback playback = emptyPlayback(0);
    private LrcLyrics lyrics = EMPTY_LYRICS;
    private Login login;
    private Media media;
    private String state = status("idle");
    private long epoch, revision, generation, operationSequence, libraryRequest, playlistsRequest, cancelledOperationsThrough;
    private int selectedIndex = -1;
    private boolean loginPending, desiredPlaying, closed;
    private ScheduledFuture<?> loginPoll;
    private ScheduledFuture<?> remotePoll,volumeUpdate;

    private MusicPlayer() {
        this(p -> switch(p) {
            case NETEASE -> new NeteaseMusicProvider();
            case QQ -> new QQMusicProvider();
            case KUGOU -> new KugouMusicProvider();
            case QISHUI -> new QishuiMusicProvider();
            case KUWO -> new KuwoMusicProvider();
            case SPOTIFY -> new SpotifyMusicProvider();
            default -> new UnavailableMusicProvider(p);
        }, null);
    }
    /** Tests inject provider operations and a device-free audio factory. */
    MusicPlayer(Function<Provider, MusicProvider> providers, Function<MusicAudioPlayer.Listener, MusicAudioPlayer> audioFactory) {
        this.providers = Objects.requireNonNull(providers);
        readQualityPreferences();
        this.audio = audioFactory == null ? new MusicAudioPlayer(this::audioSample) : audioFactory.apply(this::audioSample);
        timer.setRemoveOnCancelPolicy(true);
        synchronized (lock) { publish(); }
        selectProvider(Provider.NETEASE);
    }
    public Snapshot snapshot() { return snapshot; }
    public String requirement() { return requirement; }
    public Discovery discovery(){synchronized(lock){return discovery;}}
    /** Captures the current account's browsing page for a later return from discovery. */
    public LibraryPage captureLibraryPage(){
        synchronized(lock){
            if(closed||account==null||verificationRejected())return null;
            return new LibraryPage(this,epoch,provider,account.id(),tracks,playlists,currentLibrary,discovery);
        }
    }
    /** Returns false when the bookmark belongs to another player, provider or account session. */
    public boolean restoreLibraryPage(LibraryPage page){
        synchronized(lock){
            if(page==null||closed||page.owner!=this||page.epoch!=epoch||page.provider!=provider||account==null||!Objects.equals(page.accountId,account.id())||verificationRejected())return false;
            libraryRequest++;playlistsRequest++;
            // Obsolete loads may finish upstream, but must not keep this page busy or report failures on it.
            cancelledOperationsThrough=operationSequence;operations.clear();
            worker.getQueue().removeIf(task->task instanceof Job job&&job.operation!=0&&job.operation<=cancelledOperationsThrough);
            tracks=page.tracks;playlists=page.playlists;currentLibrary=page.library;discovery=page.discovery;
            alignSelection();state=status("ready");publish();return true;
        }
    }
    public Capabilities capabilities(){synchronized(lock){return new Capabilities(service!=null&&service.supportsSearch(),service!=null&&service.supportsRadio(),service!=null&&service.supportsTranslatedLyrics());}}
    public LrcLyrics translatedLyrics(){synchronized(lock){return translationEnabled&&playback.track()!=null&&playback.track().key().equals(translatedTrack)?translated:EMPTY_LYRICS;}}
    public String translationStatus(){synchronized(lock){return translationState;}}
    public void translation(boolean enabled){synchronized(lock){if(closed||translationEnabled==enabled)return;translationEnabled=enabled;translationRequest++;translated=EMPTY_LYRICS;translatedTrack="";translationState=status(enabled?"loading":"translatedLyricsDisabled");}if(enabled)requestTranslation();}
    public void setModuleEnabled(boolean enabled){if(!enabled){pauseForModule();return;}synchronized(lock){if(closed||moduleEnabled)return;moduleEnabled=true;}requestTranslation();}
    /** Stops the current decoder and invalidates pending stream/retry work without touching accounts. */
    public void pauseForModule(){
        final long token;final SpotifyMusicProvider remote;
        synchronized(lock){if(closed||!moduleEnabled)return;moduleEnabled=false;desiredPlaying=false;token=epoch;remote=service instanceof SpotifyMusicProvider spotify?spotify:null;
            long position=playback.position();revision++;generation++;media=null;if(recovery!=null){recovery.cancel(false);recovery=null;}if(remotePoll!=null){remotePoll.cancel(false);remotePoll=null;}
            playbackWorker.getQueue().clear();Job running=runningSong;if(running!=null&&running.runner!=null)running.runner.interrupt();audio.stop();
            state=status(playback.track()==null?"idle":"paused");playback=new Playback(playback.track(),false,position,System.nanoTime(),playback.durationMs(),revision,state);publish();
        }
        if(remote!=null)submit(token,remote::pauseRemote);
    }
    public String layout(){synchronized(lock){return layout;}}
    public void layout(String selected){synchronized(lock){if(closed||selected==null||!LAYOUTS.contains(selected)||layout.equals(selected))return;layout=selected;writeQualityPreferences();}}
    public QualitySettings qualitySettings(){synchronized(lock){return new QualitySettings(offeredQualities,requestedQualities.getOrDefault(provider,""),media==null||media.quality()==null?"":media.quality());}}
    /** Only provider-offered IDs can be selected; the actual stream label comes from Media. */
    public boolean quality(String id){
        final Track track;final long token,song,position;final boolean fetchLyrics;
        synchronized(lock){
            if(closed||verificationRejected()||id==null||!id.isEmpty()&&offeredQualities.stream().noneMatch(q->q.id().equals(id)))return false;
            if(id.equals(requestedQualities.getOrDefault(provider,"")))return true;
            if(id.isEmpty())requestedQualities.remove(provider);else requestedQualities.put(provider,id);writeQualityPreferences();
            track=playback.track();if(track==null){publish();return true;}
            position=playback.position();token=epoch;song=++revision;generation++;media=null;fetchLyrics=lyrics.isEmpty();
            streamFailures=0;lastAudioProgress=System.nanoTime();if(recovery!=null){recovery.cancel(false);recovery=null;}
            state=status(desiredPlaying?"buffering":"paused");playback=new Playback(track,false,position,System.nanoTime(),playback.durationMs(),song,state);audio.stop();publish();
            if(!moduleEnabled)return true;
        }
        queueSong(token,song,track,position,null,fetchLyrics);return true;
    }
    public void selectProvider(Provider selected) {
        if (selected == null) return;
        final long token;
        synchronized (lock) {
            if (closed || provider == selected && service != null) return;
            if(service instanceof SpotifyMusicProvider spotify)spotify.cancelLogin();
            provider = selected; service = providerInstances.get(selected); resetSession(); token = epoch;
            CachedSession cached=sessionCache.get(selected);
            if(cached!=null){account=cached.account();tracks=cached.tracks();playlists=cached.playlists();currentLibrary=cached.library();offeredQualities=cached.qualities();discovery=cached.discovery();}
            state = status(cached==null?"loading":"ready"); audio.stop(); publish();
        }
        submitExclusive(token, () -> {
            try{
                MusicProvider active = activeProvider(token);
                if (active == null) return;
                if (unavailable(active)) { setState(token, "unsupported"); return; }
                validateSelectedAccount(token,active);
            }finally{synchronized(lock){if(valid(token)&&!accountVerification.isDone())accountVerification.complete(false);}}
        });
    }
    public void login() {
        final long token;
        synchronized (lock) {
            if (closed) return;
            clearAccountCache();resetSession(); token = epoch; loginPending = true; state = status("loginPending"); audio.stop(); publish();
        }
        submitExclusive(token, () -> {
            MusicProvider active = activeProvider(token);
            if (active == null) return;
            if (unavailable(active)) { finishLoginFailure(token, "unsupported"); return; }
            Login started = active.beginLogin();
            if (started == null) { finishLoginFailure(token, "failed"); return; }
            synchronized (lock) { if (!valid(token)) return; login = started; state = safeStatus(started.status()); publish(); }
            pollLogin(token, started, System.nanoTime());
        });
    }
    public void logout() {
        final long token; final MusicProvider old; final Provider selected;
        synchronized (lock) {
            if (closed) return;
            clearAccountCache();
            old = service; selected = provider; resetSession(); token = epoch; state = status("loginRequired"); audio.stop(); publish();
        }
        // Clearing saved credentials remains required even if the user immediately changes providers.
        enqueue(new Job(token, () -> { MusicProvider target = old == null ? providers.apply(selected) : old; if (target != null) target.logout(); }, 0, true));
    }
    public void refreshLiked() { refreshLiked(false); }
    public void refreshLiked(boolean force) { loadTracks(null,force); }
    public String accountConfiguration(){synchronized(lock){return service instanceof SpotifyMusicProvider spotify?spotify.clientId():"";}}
    public void configureAccount(Provider expected,String input){
        final long token;
        synchronized(lock){if(closed||provider!=expected)return;clearAccountCache();resetSession();token=epoch;state=status("loading");audio.stop();publish();}
        submitExclusive(token,()->{
            MusicProvider active=activeProvider(token);Account confirmed=null;
            if(active instanceof SpotifyMusicProvider spotify)spotify.clientId(input);
            else if(active instanceof KuwoMusicProvider kuwo)confirmed=kuwo.importCookie(input);
            else throw new IllegalArgumentException(status("unsupported"));
            if(confirmed!=null){if(acceptAccount(token,confirmed))refreshLiked();}
            else setState(token,"loginRequired");
        });
    }
    public void openPlaylist(Playlist playlist) { if (playlist != null) loadTracks(playlist,false); }
    public void search(String query){search(query,0,false);}
    public void moreSearch(){Discovery shown=discovery();if(shown.kind().equals("search")&&shown.hasMore())search(shown.query(),shown.page()+1,true);}
    private void search(String query,int page,boolean append){
        String text=query==null?"":query.strip();if(text.isEmpty()||text.length()>120||page<0||page>=100){synchronized(lock){state=status("invalidSearch");publish();}return;}
        discoveryRequest("search",text,page,append,false);
    }
    public void radio(){discoveryRequest("radio","",0,false,true);}
    private void discoveryRequest(String kind,String query,int page,boolean append,boolean autoplay){
        final long token,request,operation;
        synchronized(lock){if(closed||verificationRejected())return;if(account==null){state=status("loginRequired");publish();return;}token=epoch;request=++libraryRequest;operation=beginOperation();state=status("loading");publish();}
        submit(token,()->{try{MusicProvider active=activeProvider(token);if(active==null)return;
            SearchPage result;if(kind.equals("radio")){if(!active.supportsRadio())throw new IllegalArgumentException(status("radioUnsupported"));result=new SearchPage(active.radio(),0,false);}
            else {if(!active.supportsSearch())throw new IllegalArgumentException(status("searchUnsupported"));result=active.search(query,page);}
            List<Track> safe=result==null?List.of():safeTracks(active,result.tracks(),30);List<Quality> offers=safe.isEmpty()?List.of():qualityOffers(active,safe.getFirst());boolean start=false;
            synchronized(lock){if(!valid(token)||request!=libraryRequest)return;LinkedHashMap<String,Track> combined=new LinkedHashMap<>();if(append&&discovery.kind().equals(kind)&&discovery.query().equals(query))for(Track track:tracks)combined.put(track.key(),track);for(Track track:safe)if(combined.size()<300)combined.putIfAbsent(track.key(),track);
                tracks=List.copyOf(combined.values());discovery=new Discovery(kind,query,page,result!=null&&result.hasMore()&&tracks.size()<300&&page<99);currentLibrary=kind+":"+query;alignSelection();if(playback.track()==null)offeredQualities=offers;
                state=status(tracks.isEmpty()?"noTracks":"ready");publish();start=autoplay&&moduleEnabled&&!tracks.isEmpty();}
            if(start)synchronized(lock){if(valid(token)&&request==libraryRequest)playIndex(0);}
        }catch(Exception failed){synchronized(lock){if(valid(token)&&request==libraryRequest){state=failureStatus(failed);publish();}}}finally{endOperation(operation);}},operation);
    }
    private static List<Track> safeTracks(MusicProvider active,List<Track> values,int limit){return values==null?List.of():values.stream().filter(t->t!=null&&t.provider()==active.provider()&&t.id()!=null&&!t.id().isBlank()).limit(limit).toList();}
    private void loadTracks(Playlist playlist,boolean force) {
        final long token, request, operation;
        final String key;
        synchronized (lock) {
            if (closed) return;
            if(verificationRejected())return;
            if (account == null) { state = status("loginRequired"); publish(); return; }
            token = epoch; request = ++libraryRequest;key=accountKey()+":"+(playlist==null?"liked":playlist.id());
            CachedTracks cached=libraryCache.get(key);
            if(!force&&cached!=null&&System.nanoTime()-cached.time()<TimeUnit.MINUTES.toNanos(2)){
                discovery=new Discovery("library","",0,false);currentLibrary=key;tracks=cached.tracks();alignSelection();state=playback.track()==null?status("ready"):playback.status();publish();return;
            }
            operation = beginOperation();
            state = status("loading"); publish();
        }
        submit(token, () -> {
            try {
                MusicProvider active = activeProvider(token); if (active == null) return;
                List<Track> loaded = playlist == null ? active.liked() : active.tracks(playlist);
                List<Track> safe = loaded == null ? List.of() : loaded.stream()
                        .filter(t -> t != null && t.provider() == active.provider() && t.id() != null && !t.id().isBlank()).toList();
                List<Quality> offers=safe.isEmpty()?List.of():qualityOffers(active,safe.getFirst());
                synchronized (lock) {
                    if (!valid(token) || request != libraryRequest) return;
                    discovery=new Discovery("library","",0,false);tracks = List.copyOf(safe);currentLibrary=key;libraryCache.put(key,new CachedTracks(System.nanoTime(),tracks));trim(libraryCache,8);alignSelection();
                    if(playback.track()==null)offeredQualities=offers;
                    state = playback.track()==null?status("ready"):playback.status(); publish();
                }
            } finally { endOperation(operation); }
        }, operation);
    }
    public void loadPlaylists() {loadPlaylists(false);}
    public void loadPlaylists(boolean force) {
        final long token, request, operation;
        final String key;
        synchronized (lock) {
            if (closed) return;
            if(verificationRejected())return;
            if (account == null) { state = status("loginRequired"); publish(); return; }
            token = epoch; request = ++playlistsRequest;key=accountKey();CachedPlaylists cached=playlistCache.get(key);
            if(!force&&cached!=null&&System.nanoTime()-cached.time()<TimeUnit.MINUTES.toNanos(2)){playlists=cached.lists();publish();return;}
            operation = beginOperation(); state = status("loading"); publish();
        }
        submit(token, () -> {
            try {
                MusicProvider active = activeProvider(token); if (active == null) return;
                List<Playlist> loaded = active.playlists();
                synchronized (lock) {
                    if (!valid(token) || request != playlistsRequest) return;
                    playlists = loaded == null ? List.of() : loaded.stream().filter(Objects::nonNull).toList();
                    playlistCache.put(key,new CachedPlaylists(System.nanoTime(),playlists));trim(playlistCache,8);
                    state = playback.track()==null?status("ready"):playback.status(); publish();
                }
            } finally { endOperation(operation); }
        }, operation);
    }
    public void playIndex(int index) {
        final Track track; final long token, song;
        synchronized (lock) {
            if (closed || !moduleEnabled || verificationRejected() || index < 0 || index >= tracks.size()) return;
            track = tracks.get(index); clearPlayback(); selectedIndex = index; token = epoch; song = revision;
            desiredPlaying = true; state = status("loading");
            playback = new Playback(track, false, 0, System.nanoTime(), Math.max(0, track.durationMs()), song, state); audio.stop(); publish();
        }
        queueSong(token, song, track, 0, null, true);
    }
    public void togglePlaying() {
        int restart = -1; boolean pause = false,resolve=false;long resumePosition=0;
        synchronized (lock) {
            if (closed || !moduleEnabled || verificationRejected()) return;
            if (playback.track() == null) { if (!tracks.isEmpty()) restart = 0; else return; }
            else if (Set.of(status("ended"), status("failed"), status("decoderRequired"), status("audioUnavailable")).contains(playback.status())) restart = selectedIndex;
            else {
                desiredPlaying = !desiredPlaying; pause = !desiredPlaying;
                if(desiredPlaying)lastAudioProgress=System.nanoTime();
                String next = status(pause ? "paused" : "buffering"); state = next;
                playback = new Playback(playback.track(), false, playback.position(), System.nanoTime(), playback.durationMs(), revision, next); publish();
                resolve=desiredPlaying&&media==null&&!(service instanceof SpotifyMusicProvider);resumePosition=playback.positionMs();
            }
        }
        if (restart >= 0) playIndex(restart);
        else if(resolve)seek(resumePosition);
        else {
            final long token,song;final SpotifyMusicProvider remote;final boolean pauseRemote=pause;
            synchronized(lock){token=epoch;song=revision;remote=service instanceof SpotifyMusicProvider spotify?spotify:null;}
            if(remote==null)audio.paused(pause);
            else submitSong(token,song,()->{try{if(pauseRemote)remote.pauseRemote();else remote.resumeRemote();applyRemote(token,song,remote.remotePlayback());}catch(Exception failure){failSong(token,song,failure);}});
        }
    }
    public void seek(long positionMs) {
        final Track track; final Media cached; final long token, song, position; final boolean requestLyrics;
        synchronized (lock) {
            if (closed || verificationRejected() || playback.track() == null) return;
            track = playback.track(); cached = media; requestLyrics = lyrics.isEmpty(); position = Math.max(0, playback.durationMs() > 0 ? Math.min(playback.durationMs(), positionMs) : positionMs);
            token = epoch; song = ++revision; generation++;
            lastAudioProgress=System.nanoTime();streamFailures=0;if(recovery!=null){recovery.cancel(false);recovery=null;}
            state = status(desiredPlaying ? "buffering" : "paused");
            playback = new Playback(track, false, position, System.nanoTime(), playback.durationMs(), song, state); audio.stop(); publish();
        }
        synchronized(lock){if(!moduleEnabled)return;}queueSong(token, song, track, position, cached, requestLyrics);
    }
    public void next(int delta) {
        int index;
        synchronized (lock) {
            if (closed || !moduleEnabled || tracks.isEmpty()) return;
            if(discovery.kind().equals("radio")&&delta>0&&selectedIndex>=tracks.size()-1){radio();return;}
            index = selectedIndex<0?(delta<0?tracks.size()-1:0):Math.floorMod((long)selectedIndex+delta, tracks.size());
        }
        playIndex(index);
    }
    public void volume(double value) {
        if(!Double.isFinite(value))return;
        synchronized(lock){
            if(closed)return;
            if(!(service instanceof SpotifyMusicProvider remote)){audio.volume(value);return;}
            long token=epoch;if(volumeUpdate!=null)volumeUpdate.cancel(false);
            volumeUpdate=timer.schedule(()->submit(token,()->remote.volumeRemote(value)),180,TimeUnit.MILLISECONDS);
        }
    }
    public void shutdown() {
        synchronized (lock) {
            if (closed) return;
            if(service instanceof SpotifyMusicProvider spotify)spotify.cancelLogin();
            resetSession(); closed = true; state = status("idle"); publish();
        }
        timer.shutdownNow(); worker.shutdown();playbackWorker.shutdownNow(); audio.close();
    }
    private void queueSong(long token, long song, Track track, long position, Media cached, boolean fetchLyrics) {
        submitSong(token, song, () -> {
            MusicProvider active = activeProvider(token); synchronized(lock){if(active==null||!moduleEnabled||!songValidLocked(token,song))return;}
            if(active instanceof SpotifyMusicProvider remote){
                try{
                    if(cached==null)remote.playRemote(track,position);else remote.seekRemote(position);
                    boolean disabled;synchronized(lock){disabled=!moduleEnabled;}if(disabled){remote.pauseRemote();return;}
                    synchronized(lock){if(!songValidLocked(token,song))return;media=new Media("spotify-connect","");}
                    applyRemote(token,song,remote.remotePlayback());
                }catch(Exception failure){failSong(token,song,failure);}return;
            }
            try {
                List<Quality> offers=qualityOffers(active,track);String requested;
                synchronized(lock){if(!songValidLocked(token,song))return;offeredQualities=offers;requested=requestedQualities.getOrDefault(provider,"");publish();}
                if(!requested.isEmpty()&&!offers.isEmpty()&&offers.stream().noneMatch(q->q.id().equals(requested)))throw new IllegalArgumentException(status("qualityUnavailable"));
                Media resolved = cached == null ? active.media(track,requested) : cached;
                if (resolved == null || resolved.url() == null || resolved.url().isBlank()) throw new IllegalStateException("No playable media");
                List<Quality> resolvedOffers=qualityOffers(active,track);
                synchronized (lock) {
                    if (!moduleEnabled||!songValidLocked(token, song)) return;
                    media = resolved;offeredQualities=resolvedOffers;
                    audio.play(track.provider(), resolved, position, song, !desiredPlaying);
                }
            } catch (Exception failure) {
                try{List<Quality> available=qualityOffers(active,track);synchronized(lock){if(songValidLocked(token,song))offeredQualities=available;}}catch(RuntimeException ignored){ }
                failSong(token, song, failure);
            }
            if (fetchLyrics && songValid(token, song)) {
                LrcLyrics original;
                try { original = active.lyrics(track); } catch (Exception absent) { original = EMPTY_LYRICS; }
                synchronized (lock) {
                    if (!songValidLocked(token, song)) return;
                    lyrics = original == null ? EMPTY_LYRICS : original; publish();
                }
            }
            requestTranslation();
        });
    }
    private void requestTranslation(){
        final long token,song,request;final Track track;
        synchronized(lock){if(closed||!translationEnabled||playback.track()==null)return;if(playback.track().key().equals(translatedTrack)&&!translated.isEmpty())return;token=epoch;song=revision;track=playback.track();request=++translationRequest;translationState=status("loading");}
        submit(token,()->{try{MusicProvider active=activeProvider(token);if(active==null)return;if(!active.supportsTranslatedLyrics())throw new IllegalArgumentException(status("translatedLyricsUnsupported"));LrcLyrics result=active.translatedLyrics(track);
            synchronized(lock){if(!songValidLocked(token,song)||request!=translationRequest||!translationEnabled)return;translated=result==null?EMPTY_LYRICS:result;translatedTrack=track.key();translationState=status(translated.isEmpty()?"translatedLyricsUnavailable":!translated.synced()?"noTimedLyrics":"translatedLyricsReady");}
        }catch(Exception unavailable){synchronized(lock){if(songValidLocked(token,song)&&request==translationRequest){translated=EMPTY_LYRICS;translatedTrack="";translationState=failureStatus(unavailable);}}}});
    }
    private void audioSample(MusicAudioPlayer.Sample sample) {
        synchronized (lock) {
            if (closed || !moduleEnabled || sample.token() != revision || playback.track() == null) return;
            long position = Math.max(0, sample.positionMs());
            if (playback.durationMs() > 0) position = Math.min(playback.durationMs(), position);
            boolean playing = sample.playing() && desiredPlaying;
            String next = !desiredPlaying && sample.status().equals(status("playing")) ? status("paused") : sample.status();
            if(playing){lastAudioProgress=System.nanoTime();if(position>recoveryPosition+1000)streamFailures=0;}
            boolean premature=sample.status().equals(status("ended"))&&playback.durationMs()>0&&position<playback.durationMs()-2000;
            boolean stalled=sample.status().equals(status("buffering"))&&System.nanoTime()-lastAudioProgress>TimeUnit.SECONDS.toNanos(12);
            boolean recoverable=sample.status().equals(status("failed"))||premature||stalled;
            if(desiredPlaying&&recoverable&&streamFailures<3){
                next=status("buffering");scheduleRecovery(epoch,revision,playback.track(),position);
            }else if(premature)next=status("failed");
            playback = new Playback(playback.track(), playing, position, System.nanoTime(), playback.durationMs(), revision, next);
            state = next; publish();
            if(desiredPlaying&&sample.status().equals(status("ended"))&&!premature&&!tracks.isEmpty()){
                long token=epoch,song=revision;timer.schedule(()->{synchronized(lock){if(!songValidLocked(token,song)||!desiredPlaying)return;}next(1);},150,TimeUnit.MILLISECONDS);
            }
        }
    }
    private void scheduleRecovery(long token,long song,Track track,long position){
        if(recovery!=null&&!recovery.isDone())return;
        long delay=500L*(1L<<streamFailures);streamFailures++;recoveryPosition=position;
        recovery=timer.schedule(()->{
            long retry;
            synchronized(lock){
                recovery=null;
                if(!moduleEnabled||!songValidLocked(token,song)||!desiredPlaying)return;
                retry=++revision;generation++;media=null;audio.stop();lastAudioProgress=System.nanoTime();
                playback=new Playback(track,false,position,System.nanoTime(),playback.durationMs(),retry,status("buffering"));publish();
            }
            queueSong(token,retry,track,position,null,lyrics.isEmpty());
        },delay,TimeUnit.MILLISECONDS);
    }
    private void pollLogin(long token, Login pending, long startedNanos) {
        if (!validSafe(token)) return;
        if (Set.of(status("qrExpired"),status("loginDeclined"),status("captchaRequired"),status("platformVerification"),status("loginAuthorizationRequired")).contains(pending.status())) {
            synchronized(lock){if(!valid(token))return;loginPending=false;state=safeStatus(pending.status());publish();}return;
        }
        if (System.nanoTime() - startedNanos > TimeUnit.MINUTES.toNanos(3)) { finishLoginFailure(token, "failed"); return; }
        if (pending.complete()) {
            MusicProvider active;
            synchronized (lock) { active = service; }
            try {
                Account confirmed = active == null ? null : active.account();
                if (confirmed != null && confirmed.id() != null && !confirmed.id().isBlank()) {
                    if (acceptAccount(token, confirmed)) refreshLiked();
                    return;
                }
            } catch (Exception unconfirmed) { }
            finishLoginFailure(token, "failed"); return;
        }
        synchronized (lock) {
            if (!valid(token)) return;
            loginPoll = timer.schedule(() -> submitExclusive(token, () -> {
                MusicProvider active = activeProvider(token); if (active == null) return;
                Login result = active.pollLogin(pending);
                if (result == null) { finishLoginFailure(token, "failed"); return; }
                synchronized (lock) { if (!valid(token)) return; login = result; state = safeStatus(result.status()); publish(); }
                pollLogin(token, result, startedNanos);
            }), 1800, TimeUnit.MILLISECONDS);
        }
    }
    private boolean acceptAccount(long token, Account confirmed) {
        synchronized (lock) {
            if (!valid(token)) return false;
            resetSession(); account = confirmed;accountVerified=true;accountVerification.complete(true);state = status("ready"); publish();
            if(service instanceof SpotifyMusicProvider remote)scheduleRemote(epoch,remote,1000);
            return true;
        }
    }
    /** Cached rows are display-only until this epoch confirms the same account upstream. */
    private void validateSelectedAccount(long token,MusicProvider active)throws Exception{
        boolean refresh=false;
        try{
            Account confirmed=active.account();
            synchronized(lock){
                if(!valid(token))return;
                boolean validAccount=confirmed!=null&&confirmed.id()!=null&&!confirmed.id().isBlank();
                if(validAccount&&account!=null&&confirmed.id().equals(account.id())){
                    account=confirmed;accountVerified=true;accountVerification.complete(true);
                    state=playback.track()==null?status("ready"):playback.status();publish();
                    if(active instanceof SpotifyMusicProvider remote)scheduleRemote(epoch,remote,1000);
                    refresh=tracks.isEmpty();
                }else{
                    clearAccountCache();resetSession();audio.stop();
                    if(validAccount){account=confirmed;accountVerified=true;accountVerification.complete(true);state=status("ready");refresh=true;}
                    else {accountVerification.complete(false);state=status("loginRequired");}
                    publish();
                    if(validAccount&&active instanceof SpotifyMusicProvider remote)scheduleRemote(epoch,remote,1000);
                }
            }
            if(refresh)refreshLiked();
        }catch(Exception failure){synchronized(lock){if(valid(token)){if(failureStatus(failure).equals(status("loginRequired"))){clearAccountCache();resetSession();audio.stop();}accountVerification.complete(false);state=failureStatus(failure);publish();}}throw failure;}
    }
    private void scheduleRemote(long token,SpotifyMusicProvider remote,long delay){
        synchronized(lock){if(!moduleEnabled||!valid(token))return;remotePoll=timer.schedule(()->submit(token,()->{
            long song;synchronized(lock){if(!valid(token))return;song=revision;}
            long next=1000;
            try{applyRemote(token,song,remote.remotePlayback());}
            catch(Exception failure){next=5000;synchronized(lock){if(valid(token)){state=failureStatus(failure);publish();}}}
            finally{scheduleRemote(token,remote,next);}
        }),delay,TimeUnit.MILLISECONDS);}
    }
    private void applyRemote(long token,long expectedSong,SpotifyMusicProvider.RemotePlayback actual){
        synchronized(lock){
            if(!moduleEnabled||!valid(token)||revision!=expectedSong||actual==null)return;
            Track old=playback.track(),next=actual.track();
            if(!Objects.equals(old==null?null:old.key(),next==null?null:next.key())){
                revision++;generation++;lyrics=EMPTY_LYRICS;selectedIndex=-1;
                for(int i=0;next!=null&&i<tracks.size();i++)if(tracks.get(i).key().equals(next.key())){selectedIndex=i;break;}
            }
            desiredPlaying=actual.playing();state=safeStatus(actual.status());
            playback=new Playback(next,actual.playing(),actual.positionMs(),actual.sampledNanos(),actual.durationMs(),revision,state);publish();
        }
    }
    private void finishLoginFailure(long token, String name) {
        synchronized (lock) { if (!valid(token)) return; if(service instanceof SpotifyMusicProvider spotify)spotify.cancelLogin();loginPending = false; state = status(name); publish(); }
    }
    private MusicProvider activeProvider(long token) {
        Provider selected;
        synchronized (lock) { if (!valid(token)) return null; if (service != null) return service; selected = provider; }
        MusicProvider created = providerInstances.computeIfAbsent(selected,providers);
        if (created == null || created.provider() != selected) throw new IllegalStateException("Invalid provider");
        synchronized (lock) { if (!valid(token)) return null; service = created; return created; }
    }
    private long beginOperation() { long id = ++operationSequence; operations.add(id); return id; }
    private void endOperation(long operation) { synchronized (lock) { if (operations.remove(operation)) publish(); } }
    private void clearPlayback() {
        revision++; generation++; selectedIndex = -1; media = null; desiredPlaying = false;
        streamFailures=0;lastAudioProgress=System.nanoTime();if(recovery!=null){recovery.cancel(false);recovery=null;}
        playback = emptyPlayback(revision); lyrics = EMPTY_LYRICS;
        translated=EMPTY_LYRICS;translatedTrack="";translationRequest++;translationState=status(translationEnabled?"loading":"translatedLyricsDisabled");
    }
    private void resetSession() {
        accountVerification.complete(false);accountVerification=new CompletableFuture<>();accountVerified=false;
        epoch++; generation++; account = null; tracks = List.of(); playlists = List.of(); login = null;
        currentLibrary="";
        discovery=new Discovery("library","",0,false);
        offeredQualities=List.of();
        operations.clear(); loginPending = false; libraryRequest++; playlistsRequest++;
        if (loginPoll != null) { loginPoll.cancel(false); loginPoll = null; }
        if (remotePoll != null) { remotePoll.cancel(false); remotePoll = null; }
        if (volumeUpdate != null) { volumeUpdate.cancel(false); volumeUpdate = null; }
        worker.getQueue().removeIf(task -> task instanceof Job job && !job.mandatory);
        playbackWorker.getQueue().clear();Job playing=runningSong;if(playing!=null&&playing.runner!=null)playing.runner.interrupt();
        clearPlayback();
    }
    private String accountKey(){return provider.id+":"+(account==null?"":account.id());}
    private static List<Quality> qualityOffers(MusicProvider active,Track track){List<Quality> offers=active.qualities(track);if(offers==null)return List.of();LinkedHashMap<String,Quality> safe=new LinkedHashMap<>();for(Quality quality:offers){if(quality!=null&&quality.id()!=null&&quality.id().matches("[A-Za-z0-9_-]{1,64}")&&quality.label()!=null&&!quality.label().isBlank()&&quality.label().length()<=100&&quality.label().chars().noneMatch(Character::isISOControl))safe.putIfAbsent(quality.id(),quality);if(safe.size()>=16)break;}return List.copyOf(safe.values());}
    private void readQualityPreferences(){try{synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())return;Path path=Config.directory().resolve("music-preferences.json");if(!Files.isRegularFile(path)||Files.size(path)>8192)return;JsonObject root=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();if(root.has("layout")&&LAYOUTS.contains(root.get("layout").getAsString()))layout=root.get("layout").getAsString();if(!root.has("quality"))return;JsonObject choices=root.getAsJsonObject("quality");for(Provider selected:Provider.values())if(choices.has(selected.id)){String id=choices.get(selected.id).getAsString();if(id.matches("[A-Za-z0-9_-]{1,64}"))requestedQualities.put(selected,id);}}}catch(Exception|LinkageError invalid){ }}
    private void writeQualityPreferences(){Map<Provider,String> choices=Map.copyOf(requestedQualities);String selectedLayout=layout;PREFERENCES.execute(()->{try{synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())return;JsonObject root=new JsonObject(),quality=new JsonObject();root.addProperty("version",1);root.addProperty("layout",selectedLayout);choices.forEach((selected,id)->quality.addProperty(selected.id,id));root.add("quality",quality);Path path=Config.directory().resolve("music-preferences.json"),temporary=path.resolveSibling("music-preferences.json.tmp");Files.createDirectories(path.getParent());try{Files.writeString(temporary,root.toString(),StandardCharsets.UTF_8);try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}}}catch(Exception|LinkageError ignored){ }});}
    private boolean verificationRejected(){return !accountVerified&&accountVerification.isDone();}
    private void clearAccountCache(){String prefix=provider.id+":";sessionCache.remove(provider);libraryCache.keySet().removeIf(k->k.startsWith(prefix));playlistCache.keySet().removeIf(k->k.startsWith(prefix));}
    private void alignSelection(){selectedIndex=-1;if(playback.track()!=null)for(int i=0;i<tracks.size();i++)if(tracks.get(i).key().equals(playback.track().key())){selectedIndex=i;break;}}
    private static void trim(Map<?,?> map,int max){while(map.size()>max)map.remove(map.keySet().iterator().next());}
    private void publish() {
        if(accountVerified&&account!=null)sessionCache.put(provider,new CachedSession(account,tracks,playlists,currentLibrary,offeredQualities,discovery));
        requirement = service == null ? "" : service.requirement();
        snapshot = new Snapshot(provider, account, tracks, playlists, playback, lyrics, login, state, loginPending || !operations.isEmpty(), generation);
    }
    private static Playback emptyPlayback(long revision) { return new Playback(null, false, 0, System.nanoTime(), 0, revision, status("idle")); }
    private boolean valid(long token) { return !closed && token == epoch; }
    private boolean operationCurrent(long operation){return operation==0||operation>cancelledOperationsThrough;}
    private boolean jobValidSafe(long token,long operation){synchronized(lock){return valid(token)&&operationCurrent(operation);}}
    private boolean validSafe(long token) { synchronized (lock) { return valid(token); } }
    private boolean songValid(long token, long song) { synchronized (lock) { return songValidLocked(token, song); } }
    private boolean songValidLocked(long token, long song) { return valid(token) && revision == song && playback.track() != null; }
    private void setState(long token, String name) { synchronized (lock) { if (valid(token)) { state = status(name); publish(); } } }
    private void failSong(long token, long song, Exception failure) {
        synchronized (lock) {
            if (!songValidLocked(token, song)) return;
            String next = failureStatus(failure);
            if(desiredPlaying&&streamFailures<3&&Set.of(status("failed"),status("networkFailed")).contains(next)){scheduleRecovery(token,song,playback.track(),playback.positionMs());next=status("buffering");}
            state = next;
            playback = new Playback(playback.track(), false, playback.positionMs(), System.nanoTime(), playback.durationMs(), revision, next); publish();
        }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void submit(long token, Work action) { submit(token, action, 0); }
    private void submit(long token, Work action, long operation) {
        enqueue(new Job(token, action, operation, false));
    }
    private void submitExclusive(long token,Work action){enqueue(new Job(token,action,0,false,0,true));}
    private void submitSong(long token, long song, Work action) { enqueue(new Job(token, action, 0, false, song)); }
    private final class Job implements Runnable {
        final long token, operation, song; final Work action; final boolean mandatory,exclusive;volatile Thread runner;
        Job(long token, Work action, long operation, boolean mandatory) { this(token, action, operation, mandatory, 0); }
        Job(long token, Work action, long operation, boolean mandatory, long song) {this(token,action,operation,mandatory,song,mandatory);}
        Job(long token, Work action, long operation, boolean mandatory, long song,boolean exclusive) { this.token = token; this.action = action; this.operation = operation; this.mandatory = mandatory; this.song = song;this.exclusive=exclusive; }
        @Override public void run() {
            if (!mandatory && (!jobValidSafe(token,operation)||song!=0&&!songValid(token,song))) { if (operation != 0) endOperation(operation); return; }
            var gate=exclusive?providerAccess.writeLock():providerAccess.readLock();boolean acquired=false;
            runner=Thread.currentThread();if(song!=0)runningSong=this;
            try {
                if(!mandatory&&!exclusive){CompletableFuture<Boolean> verification;synchronized(lock){if(!valid(token)||!operationCurrent(operation))return;verification=accountVerification;}
                    if(!verification.get(30,TimeUnit.SECONDS)){if(operation!=0)endOperation(operation);return;}}
                gate.lockInterruptibly();acquired=true;if(mandatory||jobValidSafe(token,operation)&& (song==0||songValid(token,song)))action.run();
            }
            catch (Exception failure) {
                synchronized (lock) {
                    if (valid(token)&&operationCurrent(operation)&&(song==0||songValidLocked(token,song))) { loginPending = false; state = failureStatus(failure); publish(); }
                }
                if (operation != 0) endOperation(operation);
            }finally{if(acquired)gate.unlock();runner=null;if(runningSong==this)runningSong=null;}
        }
    }
    private void enqueue(Job job) {
        if (job.song != 0) synchronized (lock) {
            if (!songValidLocked(job.token, job.song)) return;
            worker.getQueue().removeIf(task -> task instanceof Job old && old.song != 0 && old.song != job.song);
            playbackWorker.getQueue().removeIf(task -> task instanceof Job old && old.song != job.song);
            Job active=runningSong;if(active!=null&&active.song!=job.song&&active.runner!=null)active.runner.interrupt();
        }
        try {
            (job.song!=0?playbackWorker:worker).execute(job);
        } catch (RejectedExecutionException full) {
            synchronized (lock) {
                if (valid(job.token)&&operationCurrent(job.operation)) {
                    operations.remove(job.operation); loginPending = false; state = status("failed");
                    if (job.song == revision && playback.track() != null)
                        playback = new Playback(playback.track(), false, playback.positionMs(), System.nanoTime(), playback.durationMs(), revision, state);
                    publish();
                }
            }
        }
    }
    private static String failureStatus(Exception failure) {
        return safeStatus(failure.getMessage());
    }
    private static String safeStatus(String key) {return key!=null&&key.matches("ui\\.musicPlayer\\.[A-Za-z]{1,50}")?key:status("failed");}
    private static boolean unavailable(MusicProvider provider) {
        return !provider.supported();
    }
}
