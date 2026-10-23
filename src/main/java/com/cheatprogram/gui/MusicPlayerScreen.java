package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.media.player.MusicArtworkCache;
import com.cheatprogram.media.player.MusicModels.*;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.media.player.MusicNetwork;
import com.cheatprogram.module.visual.FloatingSubtitles;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.AlbumWallModel;
import com.cheatprogram.render.MusicCoverCarouselRenderer;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** The floating-subtitle module's main page: account library, perspective covers and playback. */
public final class MusicPlayerScreen extends GuiBase {
    private final Screen parent;private final FloatingSubtitles module;
    private final MusicPlayer player=MusicPlayer.INSTANCE;
    private MusicArtworkCache artwork=new MusicArtworkCache();
    private final MusicCoverCarouselRenderer covers=new MusicCoverCarouselRenderer();
    private MusicCoverCarouselRenderer.Motion motion=new MusicCoverCarouselRenderer.Motion();
    private MusicCoverCarouselRenderer.RingMotion ringMotion=new MusicCoverCarouselRenderer.RingMotion();
    private AlbumWallModel wall=new AlbumWallModel();
    private final MusicCoverCarouselRenderer.ControlMotion controls=new MusicCoverCarouselRenderer.ControlMotion();
    private final MusicCoverCarouselRenderer.ContentFade<Track> footerFade=new MusicCoverCarouselRenderer.ContentFade<>(),titleFade=new MusicCoverCarouselRenderer.ContentFade<>();
    private Scroller songs=new Scroller(),playlists=new Scroller();
    private final SeekPreview seek=new SeekPreview();
    private final TextField searchField=new TextField(120);
    private record SearchReturn(MusicPlayer.LibraryPage page,List<Track> tracks,Provider provider,String activePlaylist,boolean showPlaylists,String layout,MusicCoverCarouselRenderer.Motion motion,MusicCoverCarouselRenderer.RingMotion ringMotion,AlbumWallModel wall,Scroller songs,Scroller playlists) {}
    private SearchReturn searchReturn;
    private List<Track> observed=List.of();private Provider provider;private String playingKey="";
    private static double volume=.8;private String activePlaylist="",layout="";private boolean showPlaylists,coverDragging,qualityMenu;private float carouselY,carouselH,coverStartX,coverStartY;
    public MusicPlayerScreen(Screen parent,FloatingSubtitles module){super(Lang.t("ui.music.title"));this.parent=parent;this.module=module;searchField.onEnter=this::runSearch;}
    @Override protected void init(){if(artwork==null)artwork=new MusicArtworkCache();Snapshot data=player.snapshot();Track current=data.playback()==null?null:data.playback().track();int selected=0;if(current!=null)for(int i=0;i<data.tracks().size();i++)if(current.key().equals(data.tracks().get(i).key())){selected=i;break;}artwork.preload(data.tracks(),selected,8);}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float h=layoutPanel(1120,780);drawPanelSurface(graphics,1120,h,22);Snapshot data=player.snapshot();sync(data);
            c.rrect(10,10,184,h-20,16,0x28000000);
            c.icon(Icon.MUSIC,35,36,18,0xffec5267);c.textC(BOLD,Lang.t("ui.music.title"),53,36,15,Theme.TEXT);
            c.textC(REG,Lang.t("ui.music.subtitle"),24,64,8,Theme.TEXT_3);
            float py=91;for(Provider value:Provider.values()){
                boolean selected=data.provider()==value;
                if(selected)c.rrect(20,py,164,35,9,0x33ef5268);
                c.icon(value==Provider.NETEASE?Icon.NETEASE:Icon.MUSIC,37,py+17,13,selected?0xfffb7185:Theme.TEXT_3);
                c.textC(selected?MED:REG,value.label,53,py+17,10.5f,selected?Theme.TEXT:Theme.TEXT_2);
                hit(20,py,164,35).left(()->{searchReturn=null;showPlaylists=false;activePlaylist="";player.selectProvider(value);});py+=43;
            }
            c.rect(27,py+3,145,1,0x15ffffff);py+=23;
            button("music.liked",Lang.t("ui.music.liked"),Icon.STAR,21,py,162,32,!showPlaylists,()->{searchReturn=null;showPlaylists=false;activePlaylist="";songs.target=0;player.refreshLiked();});py+=42;
            button("music.playlists",Lang.t("ui.music.playlists"),Icon.FOLDER,21,py,162,32,showPlaylists,()->{searchReturn=null;showPlaylists=true;player.loadPlaylists();});
            button("music.preview",Lang.t("ui.lyrics.preview"),Icon.EYE,21,h-140,162,29,false,()->{module.preview();mc.setScreen(null);});
            button("music.captions",Lang.t("ui.music.captions"),Icon.GEAR,21,h-102,162,31,false,()->mc.setScreen(new FloatingSubtitlesScreen(this,module)));
            button("music.caption.enable",Lang.t(module.isEnabled()?"ui.lyrics.disable":"ui.lyrics.enable"),Icon.POWER,21,h-64,162,29,module.isEnabled(),module::toggle);
            main(graphics,data,h);footer(data,h);qualityControl(data,h);
        }finally{R2D.end();endFrame();}
    }
    private void sync(Snapshot data){
        Track current=data.playback()==null?null:data.playback().track();String key=current==null?"":current.key();
        if(seek.active()&&!seek.matches(data.playback())){seek.cancel();drag=null;}
        if(provider!=data.provider()||!observed.equals(data.tracks())){
            qualityMenu=false;
            observed=data.tracks();provider=data.provider();resetCovers(Math.max(0,indexOf(current)));songs.target=0;
        }else if(!key.equals(playingKey)){int index=Math.max(0,indexOf(current));motion.follow(index,observed.size());ringMotion.follow(index,observed.size());wall.select(index);}
        String requestedLayout=player.layout();if(!layout.equals(requestedLayout)){int index=indexOf(current);if(index<0)index=frontIndex();layout=requestedLayout;resetCovers(index);}
        playingKey=key;
        // An inertial album animation must not turn playback back on after the module was disabled.
        int commit=wallLayout()?-1:ringLayout()?ringMotion.update(dt):motion.update(dt);if(module.isEnabled()&&commit>=0&&commit<observed.size()&&(ringLayout()||!observed.get(commit).key().equals(key)))playTrack(commit);
        if(wallLayout()){wall.update(dt);artwork.preload(data.tracks(),frontIndex(),0);artwork.preloadIdle(data.tracks(),frontIndex());}
        else {artwork.preload(data.tracks(),frontIndex(),8);artwork.preloadIdle(data.tracks(),frontIndex());}
    }
    private boolean ringLayout(){return layout.equals("ring");}
    private boolean wallLayout(){return layout.equals("wall");}
    private float coverPosition(){return ringLayout()?ringMotion.position():motion.position();}
    private int frontIndex(){return wallLayout()?wall.selected():ringLayout()?MusicCoverCarouselRenderer.RingMotion.index(ringMotion.position(),observed.size()):Math.max(0,Math.min(Math.max(0,observed.size()-1),Math.round(motion.position())));}
    private void resetCovers(int index){covers.clearHover();motion.reset(index,observed.size());ringMotion.reset(index,observed.size());wall.reset(index,observed.size());if(coverDragging)drag=null;coverDragging=false;}
    private void moveCover(int delta){if(wallLayout()){if(!observed.isEmpty()){int index=Math.max(0,Math.min(observed.size()-1,wall.selected()+delta));wall.select(index);playTrack(index);}return;}enableMusic();if(ringLayout())ringMotion.move(delta);else motion.move(delta);}
    private int indexOf(Track track){if(track!=null)for(int i=0;i<observed.size();i++)if(observed.get(i).key().equals(track.key()))return i;return -1;}
    private void main(GuiGraphics graphics,Snapshot data,float h){
        c.textC(BOLD,data.provider().label,218,34,20,Theme.TEXT);
        c.textC(REG,c.ellipsize(REG,data.account()==null?Lang.t("ui.music.account"):data.account().name(),9,460),220,59,9,Theme.TEXT_3);
        button("music.layout",c.ellipsize(REG,Lang.t("ui.music.layout")+" · "+Lang.t(wallLayout()?"mode.music_layout_wall":ringLayout()?"mode.music_layout_ring":"mode.music_layout_coverflow"),8,178),Icon.GEAR,620,23,208,30,false,()->player.layout(wallLayout()?"coverflow":ringLayout()?"wall":"ring"));
        button("music.auth",Lang.t(data.account()==null?"ui.music.login":"ui.music.logout"),Icon.USER,865,23,146,30,data.account()==null,()->{
            if(data.account()!=null)player.logout();
            else if(data.provider()==Provider.KUWO||data.provider()==Provider.SPOTIFY&&player.accountConfiguration().isBlank())mc.setScreen(new MusicAccountScreen(this,data.provider()));
            else player.login();
        });
        if(data.provider()==Provider.SPOTIFY||data.provider()==Provider.KUWO)
            iconButton("music.account.settings",Icon.GEAR,843,38,13,Theme.TEXT_2,()->mc.setScreen(new MusicAccountScreen(this,data.provider())));
        iconButton("music.reload",Icon.REFRESH,1038,38,15,Theme.TEXT_2,()->{if(searchReturn!=null)runSearch();else if(showPlaylists)player.loadPlaylists(true);else player.refreshLiked(true);});
        iconButton("music.close",Icon.CLOSE,1081,38,14,Theme.TEXT_2,this::onClose);
        java.net.URI loginUri=loginUri(data);if(loginUri!=null)button("music.browser",Lang.t("ui.music.browser"),Icon.LINK,865,63,205,25,false,()->net.minecraft.Util.getPlatform().openUri(loginUri));
        String status=data.status()==null?"":Lang.t(data.status());
        c.textC(REG,c.ellipsize(REG,status.isBlank()?Lang.t(data.busy()?"ui.music.loading":"ui.music.ready"):status,8.5f,loginUri==null?866:624),220,86,8.5f,Theme.TEXT_2);
        String requirement=player.requirement();boolean notice=requirement!=null&&!requirement.isBlank();
        MusicPlayer.Capabilities capabilities=player.capabilities();
        if(capabilities.search()){textField(searchField,Lang.t("ui.music.searchHint"),Icon.MUSIC,219,103,465,30);button("music.search",Lang.t("ui.music.search"),Icon.CHEVRON_RIGHT,694,103,107,30,false,this::runSearch);}
        else c.textC(REG,Lang.t("ui.musicPlayer.searchUnsupported"),220,118,8.5f,Theme.TEXT_4);
        if(capabilities.radio())button("music.radio",Lang.t("ui.music.radio"),Icon.MUSIC,815,103,263,30,false,()->{searchReturn=null;showPlaylists=false;activePlaylist="";enableMusic();player.radio();});
        else c.textRight(REG,Lang.t("ui.musicPlayer.radioUnsupported"),1078,118,8,Theme.TEXT_4);
        if(notice)c.textC(REG,c.ellipsize(REG,Lang.t(requirement),8,866),220,146,8,Theme.TEXT_3);
        if(searchReturn!=null){String source=searchReturn.showPlaylists()?Lang.t("ui.music.playlists"):searchReturn.activePlaylist().isBlank()?Lang.t("ui.music.liked"):searchReturn.activePlaylist();button("music.search.back",c.ellipsize(REG,Lang.t("ui.music.backToLibrary")+" · "+source,8.5f,420),Icon.CHEVRON_LEFT,219,notice?162:142,465,26,false,this::returnFromSearch);}
        carouselY=(notice?164:144)+(searchReturn==null?0:34);carouselH=wallLayout()?Math.min(420,Math.max(160,h-carouselY-256)):Math.min(260,Math.max(148,(h-330)*.58f));
        if(!data.tracks().isEmpty()){
            if(wallLayout())albumWall(data);
            else covers.draw(graphics,c.px(214),c.py(carouselY),884*c.scale,carouselH*c.scale,data.tracks(),coverPosition(),artwork,ringLayout(),coverDragging?Float.NaN:c.px(mx),coverDragging?Float.NaN:c.py(my),dt);
            if(!wallLayout()&&covers.failed())c.textMid(REG,Lang.t("ui.music.coverUnavailable"),656,carouselY+carouselH/2,10,Theme.TEXT_3);
            int selected=frontIndex();Track preview=data.tracks().get(selected);
            titleFade.update(preview,dt);for(var layer:titleFade.layers()){R2D.pushAlpha(layer.alpha());try{float offset=(1-layer.alpha())*9;
                c.textMid(BOLD,c.ellipsize(BOLD,layer.value().title(),16,700),656,carouselY+carouselH+15+offset,16,Theme.TEXT);
                c.textMid(REG,c.ellipsize(REG,layer.value().artist()+" · "+layer.value().album(),9,700),656,carouselY+carouselH+39+offset,9,Theme.TEXT_3);
            }finally{R2D.popAlpha();}}
            c.textMid(REG,Lang.t(wallLayout()?"ui.music.wallSwipe":artwork.unavailable(preview.provider(),preview.cover())?"ui.music.coverUnavailable":ringLayout()?"ui.music.ringSwipe":"ui.music.swipe"),656,carouselY+carouselH+59,8,Theme.TEXT_4);
            if(wallLayout())hit(214,carouselY,884,carouselH).left(()->{coverDragging=true;wall.begin(mx-214,my-carouselY);drag=(x,y)->wall.drag(x-214,y-carouselY);}).scroll(amount->wall.zoomAt(mx-214,my-carouselY,amount));
            else hit(214,carouselY,884,carouselH).left(()->{enableMusic();coverDragging=true;coverStartX=mx;coverStartY=my;if(ringLayout()){ringMotion.begin(mx);drag=(x,y)->ringMotion.drag(x);}else{motion.begin(mx);drag=(x,y)->motion.drag(x);}}).scroll(amount->moveCover(amount>0?-1:1));
        }else {titleFade.clear();empty(data,carouselY,carouselH+60);}
        float top=carouselY+carouselH+82,bottom=h-99;
        if(bottom-top<35)return;
        if(showPlaylists)playlistRows(data,top,bottom);else trackRows(data,top,bottom);
    }
    private void albumWall(Snapshot data){
        wall.viewport(884,carouselH);wall.hoverAt(mx-214,my-carouselY);List<AlbumWallModel.Card> visible=wall.visible(0);
        for(int index:wall.preloadIndices()){Track track=data.tracks().get(index);artwork.artwork(track.provider(),track.cover());}
        c.rrect(214,carouselY,884,carouselH,10,0x25000000);
        covers.drawWall(c.px(214),c.py(carouselY),884*c.scale,carouselH*c.scale,c.scale,wall.zoom(),data.tracks(),visible,artwork);
        c.clip(214,carouselY,884,carouselH);try{for(AlbumWallModel.Card card:visible){
            Track track=data.tracks().get(card.index());float x=214+card.x(),y=carouselY+card.y(),size=card.size(),font=Math.min(12,Math.max(7.5f,size*.068f));
            if(size<46)continue;
            c.textC(MED,c.ellipsize(MED,track.title(),font,size-14),x+7,y+size-23,font,Theme.TEXT);
            if(size>=74)c.textC(REG,c.ellipsize(REG,track.artist(),Math.max(6.5f,font-2),size-14),x+7,y+size-10,Math.max(6.5f,font-2),Theme.TEXT_2);
        }}finally{c.unclip();}
    }
    private void empty(Snapshot data,float y,float height){
        ResourceLocation qr=artwork.login(data.login());
        if(data.login()!=null&&!data.login().complete()&&qr!=null){
            c.rrect(581,y+8,150,150,12,0xffffffff);R2D.texture(qr,c.px(590),c.py(y+17),132*c.scale,132*c.scale,0,0,1,1,0xffffffff);
            c.textMid(REG,Lang.t("ui.music.qr"),656,y+177,10,Theme.TEXT_2);
            c.textMid(REG,c.ellipsize(REG,Lang.t(data.login().status()),9,660),656,y+199,9,Theme.TEXT_3);
        }else{
            c.icon(Icon.MUSIC,656,y+height*.31f,39,Theme.TEXT_4);
            c.textMid(MED,Lang.t(data.busy()?"ui.music.loading":data.account()==null?"ui.music.empty":"ui.music.noTracks"),656,y+height*.56f,11,Theme.TEXT_2);
            c.textMid(REG,Lang.t("ui.music.privacy"),656,y+height*.56f+27,8.5f,Theme.TEXT_4);
            if(data.login()!=null)c.textMid(REG,c.ellipsize(REG,Lang.t(data.login().status()),9,660),656,y+height*.56f+50,9,Theme.TEXT_3);
        }
    }
    private void trackRows(Snapshot data,float top,float bottom){
        float available=bottom-top;MusicPlayer.Discovery discovery=player.discovery();String heading=discovery.kind().equals("search")?Lang.t("ui.music.search")+" · "+discovery.query():discovery.kind().equals("radio")?Lang.t("ui.music.radio"):activePlaylist.isBlank()?Lang.t("ui.music.liked"):activePlaylist;c.textC(MED,c.ellipsize(MED,heading+" · "+data.tracks().size(),10.5f,650),221,top-10,10.5f,Theme.TEXT_2);
        if(discovery.kind().equals("search")&&discovery.hasMore())button("music.more",Lang.t("ui.music.moreResults"),Icon.CHEVRON_RIGHT,932,top-25,150,25,false,player::moreSearch);
        c.textC(REG,Lang.t("ui.music.track"),253,top+16,8,Theme.TEXT_4);c.textC(REG,Lang.t("ui.music.artist"),611,top+16,8,Theme.TEXT_4);
        c.textC(REG,Lang.t("ui.music.album"),819,top+16,8,Theme.TEXT_4);c.textRight(REG,Lang.t("ui.music.duration"),1077,top+16,8,Theme.TEXT_4);
        float y0=top+33,viewport=Math.max(1,available-33);songs.max=Math.max(0,data.tracks().size()*39-viewport);songs.update(dt);
        hit(216,y0,874,viewport).scroll(songs::scroll);c.clip(216,y0,874,viewport);pushHitClip(216,y0,874,viewport);
        try{int first=Math.max(0,(int)(songs.get()/39)),last=Math.min(data.tracks().size(),first+(int)(viewport/39)+2);
            for(int i=first;i<last;i++){
                Track track=data.tracks().get(i);int index=i;float y=y0+i*39-songs.get();boolean active=data.playback()!=null&&data.playback().track()!=null&&track.key().equals(data.playback().track().key());
                var rowCover=artwork.artwork(track.provider(),track.cover());
                if(active||over(217,y,871,36))c.rrect(217,y,871,36,6,active?0x22ef5268:0x0fffffff);
                c.textC(REG,active?"♫":String.valueOf(i+1),228,y+18,9,active?0xfffb7185:Theme.TEXT_4);
                if(rowCover!=null)R2D.texture(rowCover,c.px(251),c.py(y+4),28*c.scale,28*c.scale,0,0,1,1,0xffffffff);
                else {c.rrect(251,y+4,28,28,4,0x18ffffff);c.icon(Icon.MUSIC,265,y+18,13,Theme.TEXT_3);}
                c.textC(active?MED:REG,c.ellipsize(REG,track.title(),10,306),286,y+18,10,active?0xfffb7185:Theme.TEXT);
                c.textC(REG,c.ellipsize(REG,track.artist(),9,190),611,y+18,9,Theme.TEXT_3);
                c.textC(REG,c.ellipsize(REG,track.album(),9,176),819,y+18,9,Theme.TEXT_3);c.textRight(REG,time(track.durationMs()),1077,y+18,9,Theme.TEXT_4);
                hit(217,y,871,36).left(()->playTrack(index));
            }
        }finally{popHitClip();c.unclip();}panelScrollBar("music.songs.scroll",songs,1096,y0,viewport);
    }
    private void playlistRows(Snapshot data,float top,float bottom){
        c.textC(MED,Lang.t("ui.music.playlists")+" · "+data.playlists().size(),221,top-10,10.5f,Theme.TEXT_2);
        float viewport=bottom-top;playlists.max=Math.max(0,data.playlists().size()*53-viewport);playlists.update(dt);
        hit(216,top,874,viewport).scroll(playlists::scroll);c.clip(216,top,874,viewport);pushHitClip(216,top,874,viewport);
        try{int first=Math.max(0,(int)(playlists.get()/53)),last=Math.min(data.playlists().size(),first+(int)(viewport/53)+2);
            for(int i=first;i<last;i++){Playlist list=data.playlists().get(i);float y=top+i*53-playlists.get();
                if(over(217,y,871,49))c.rrect(217,y,871,49,7,0x0fffffff);
                ResourceLocation image=artwork.artwork(data.provider(),list.cover());
                if(image!=null)R2D.texture(image,c.px(227),c.py(y+6),36*c.scale,36*c.scale,0,0,1,1,0xffffffff);else c.icon(Icon.FOLDER,245,y+24,18,Theme.TEXT_4);
                c.textC(MED,c.ellipsize(MED,list.name(),10.5f,650),276,y+18,10.5f,Theme.TEXT);c.textC(REG,String.valueOf(list.count()),277,y+36,8.5f,Theme.TEXT_4);
                c.icon(Icon.CHEVRON_RIGHT,1075,y+24,13,Theme.TEXT_3);hit(217,y,871,49).left(()->{showPlaylists=false;activePlaylist=list.name();player.openPlaylist(list);});
            }
        }finally{popHitClip();c.unclip();}panelScrollBar("music.playlists.scroll",playlists,1096,top,viewport);
    }
    private void footer(Snapshot data,float h){
        float y=h-88;c.rect(207,y,892,1,0x18ffffff);Playback playback=data.playback();Track current=playback==null?null:playback.track();
        footerFade.update(current,dt);
        if(current!=null){for(var layer:footerFade.layers()){Track track=layer.value();R2D.pushAlpha(layer.alpha());try{ResourceLocation image=artwork.artwork(track.provider(),track.cover());float inset=(1-layer.alpha())*3;if(image!=null)R2D.texture(image,c.px(220+inset),c.py(y+16+inset),(46-2*inset)*c.scale,(46-2*inset)*c.scale,0,0,1,1,0xffffffff);
                c.textC(MED,c.ellipsize(MED,track.title(),10,246),278,y+29+(1-layer.alpha())*6,10,Theme.TEXT);c.textC(REG,c.ellipsize(REG,track.artist(),8.5f,245),278,y+49+(1-layer.alpha())*6,8.5f,Theme.TEXT_3);
            }finally{R2D.popAlpha();}}}
        else c.textC(REG,Lang.t("ui.music.noTracks"),225,y+37,9,Theme.TEXT_4);
        boolean playing=playback!=null&&playback.playing();controls.update(playing,over(591,y+8,42,42),dt);
        skipControl(-1,564,y+29);skipControl(1,660,y+29);
        float size=controls.scale();c.circle(612,y+29,20*size,0xffeb5267);playIcon(612,y+29,size,controls.playing());
        hit(591,y+8,42,42).left(this::togglePlayback);
        long duration=playback==null?0:playback.durationMs(),position=seek.active()?seek.position():playback==null?0:playback.position();
        float seekX=549,seekW=311,seekY=y+65;c.rrect(seekX,seekY-2,seekW,3,1.5f,0x23ffffff);
        float fraction=duration>0?Math.max(0,Math.min(1,position/(float)duration)):0;c.rrect(seekX,seekY-2,seekW*fraction,3,1.5f,0xffed6175);
        c.textRight(REG,time(position),seekX-9,seekY,7.5f,Theme.TEXT_4);c.textC(REG,time(duration),seekX+seekW+9,seekY,7.5f,Theme.TEXT_4);
        if(duration>0)hit(seekX-3,seekY-8,seekW+6,16).left(()->{seek.begin(player.snapshot().playback());if(!seek.active())return;drag=(x,my)->seek.drag((x-seekX)/seekW);drag.drag(mx,my);});
        c.textC(REG,Lang.t("ui.music.volume"),923,y+29,8.5f,Theme.TEXT_3);c.rrect(923,y+47,151,3,1.5f,0x23ffffff);c.rrect(923,y+47,(float)volume*151,3,1.5f,Theme.TEXT_2);
        hit(919,y+39,159,19).left(()->{drag=(x,my)->{volume=Math.max(0,Math.min(1,(x-923)/151));player.volume(volume);};drag.drag(mx,my);});
        c.textC(REG,Lang.t("ui.music.privacy"),925,y+69,6.8f,Theme.TEXT_4);
    }
    private void enableMusic(){if(!module.isEnabled())module.setEnabled(true);player.setModuleEnabled(true);}
    private void playTrack(int index){enableMusic();player.playIndex(index);}
    private void runSearch(){
        if(searchField.text.isBlank())return;
        if(searchReturn==null){MusicPlayer.LibraryPage page=player.captureLibraryPage();if(page!=null){motion.cancelInteraction();ringMotion.cancelInteraction();wall.cancel();int selected=frontIndex();searchReturn=new SearchReturn(page,observed,provider,activePlaylist,showPlaylists,layout,motion,ringMotion,wall,songs,playlists);motion=new MusicCoverCarouselRenderer.Motion();ringMotion=new MusicCoverCarouselRenderer.RingMotion();wall=new AlbumWallModel();songs=new Scroller();playlists=new Scroller();resetCovers(selected);}}
        showPlaylists=false;activePlaylist="";focused=null;songs.target=0;player.search(searchField.text);
    }
    private void returnFromSearch(){
        SearchReturn saved=searchReturn;if(saved==null)return;
        if(!player.restoreLibraryPage(saved.page())){searchReturn=null;return;}
        searchReturn=null;observed=saved.tracks();provider=saved.provider();activePlaylist=saved.activePlaylist();showPlaylists=saved.showPlaylists();layout=saved.layout();player.layout(layout);
        motion=saved.motion();ringMotion=saved.ringMotion();wall=saved.wall();songs=saved.songs();playlists=saved.playlists();focused=null;qualityMenu=false;coverDragging=false;drag=null;seek.cancel();titleFade.clear();searchField.text="";
        Playback playback=player.snapshot().playback();playingKey=playback==null||playback.track()==null?"":playback.track().key();artwork.preload(observed,frontIndex(),8);
    }
    private void togglePlayback(){controls.click(0);enableMusic();player.togglePlaying();}
    private void qualityControl(Snapshot data,float height){
        MusicPlayer.QualitySettings quality=player.qualitySettings();
        String requested=quality.requested().isEmpty()?Lang.t("ui.music.platformDefault"):quality.offered().stream().filter(option->option.id().equals(quality.requested())).map(Quality::label).findFirst().orElse(quality.requested());
        boolean managed=quality.offered().isEmpty()&&quality.requested().isEmpty();
        if(managed){qualityMenu=false;c.textC(REG,c.ellipsize(REG,Lang.t("ui.music.qualityPlatformManaged"),8,182),711,height-61,8,Theme.TEXT_3);}
        else button("music.quality",c.ellipsize(REG,Lang.t("ui.music.quality")+" · "+requested,8,143),Icon.GEAR,708,height-75,183,27,qualityMenu,()->qualityMenu=!qualityMenu);
        String returned=quality.offered().stream().filter(option->option.id().equals(quality.applied())).map(Quality::label).findFirst().orElse(quality.applied());
        String applied=quality.applied().isBlank()?Lang.t(managed?"ui.music.qualityPlatformManaged":"ui.music.qualityUnknown"):Lang.t("ui.music.qualityApplied")+" · "+returned;
        c.textC(REG,c.ellipsize(REG,applied,7,183),711,height-40,7,Theme.TEXT_4);
        if(!qualityMenu||managed)return;
        java.util.ArrayList<Quality> options=new java.util.ArrayList<>();options.add(new Quality("",Lang.t("ui.music.platformDefault")));options.addAll(quality.offered());
        int columns=options.size()>8?2:1,rows=(options.size()+columns-1)/columns;float width=columns*190,left=896-width,top=height-86-rows*27;
        hit(0,0,1120,height).left(()->qualityMenu=false);
        c.rrect(left-5,top-5,width+10,rows*27+10,10,0xf222242d);
        for(int index=0;index<options.size();index++){Quality option=options.get(index);float x=left+(index/rows)*190,y=top+(index%rows)*27;boolean selected=option.id().equals(quality.requested());
            if(selected||over(x,y,190,27))c.rrect(x,y,190,27,6,selected?0x33ef5268:0x18ffffff);
            if(selected)c.circle(x+12,y+13.5f,2.5f,0xfffb7185);
            c.textC(selected?MED:REG,c.ellipsize(REG,option.label(),8.5f,163),x+23,y+13.5f,8.5f,selected?Theme.TEXT:Theme.TEXT_2);
            hit(x,y,190,27).left(()->{player.quality(option.id());qualityMenu=false;});
        }
    }
    private void skipControl(int direction,float x,float y){float pulse=controls.skip(direction),offset=direction*pulse*5;
        c.circle(x,y,17+pulse*3,Math.round(pulse*28)<<24|0xfb7185);
        c.icon(direction<0?Icon.CHEVRON_LEFT:Icon.CHEVRON_RIGHT,x+offset,y,18+pulse*2,Theme.TEXT_2);
        hit(x-20,y-20,40,40).left(()->{controls.click(direction);enableMusic();player.next(direction);});
    }
    /** Two connected triangle halves continuously become the two pause bars. */
    private void playIcon(float x,float y,float scale,float pause){for(int row=0;row<16;row++){float dy=-7+row*.875f,width=12*(1-Math.abs(dy+.4375f)/7),middle=-4+width*.5f;
        float left=-4+(-5+4)*pause,leftEnd=middle+(-1.5f-middle)*pause,right=middle+(1.5f-middle)*pause,rightEnd=-4+width+(5+4-width)*pause;
        c.rrect(x+left*scale,y+dy*scale,Math.max(.01f,(leftEnd-left)*scale),.95f*scale,.2f,0xffffffff);
        c.rrect(x+right*scale,y+dy*scale,Math.max(.01f,(rightEnd-right)*scale),.95f*scale,.2f,0xffffffff);
    }}
    /** Scrubbing changes only this preview. The release returns one guarded decoder seek. */
    public static final class SeekPreview {
        private boolean active;private String key="";private long revision,duration,position;
        public boolean active(){return active;}public long position(){return position;}
        public void begin(Playback playback){cancel();if(playback==null||playback.track()==null||playback.durationMs()<=0)return;active=true;key=playback.track().key();revision=playback.revision();duration=playback.durationMs();position=playback.position();}
        public void drag(float fraction){if(active&&Float.isFinite(fraction))position=(long)(Math.max(0,Math.min(1,fraction))*duration);}
        public boolean matches(Playback playback){return active&&playback!=null&&playback.track()!=null&&key.equals(playback.track().key())&&revision==playback.revision();}
        public long release(Playback playback){long target=matches(playback)?position:-1;cancel();return target;}
        public void cancel(){active=false;key="";}
    }
    private static java.net.URI loginUri(Snapshot data){if(data.login()==null||data.login().complete())return null;try{java.net.URI uri=java.net.URI.create(data.login().url());return MusicNetwork.allowed(data.provider(),uri)?uri:null;}catch(RuntimeException invalid){return null;}}
    private static String time(long ms){long seconds=Math.max(0,ms/1000);return seconds/60+":"+String.format(java.util.Locale.ROOT,"%02d",seconds%60);}
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseReleased(double x,double y,int button){if(coverDragging&&button==0){mouseFromEvent(x,y);if(wallLayout()){int index=wall.release(mx-214,my-carouselY);if(index>=0){wall.select(index);playTrack(index);}}else if(ringLayout())ringMotion.release(Math.hypot(mx-coverStartX,my-coverStartY)<4&&mx>556&&mx<756);else motion.release();coverDragging=false;}if(button==0&&seek.active()){mouseFromEvent(x,y);if(drag!=null)drag.drag(mx,my);long target=seek.release(player.snapshot().playback());if(target>=0)player.seek(target);}return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){mouseFromEvent(x,y);if(wallLayout()&&!observed.isEmpty()&&over(214,carouselY,884,carouselH)){if(Math.abs(horizontal)>.01)wall.pan((float)-horizontal*40,0);if(Math.abs(vertical)>.001)wall.zoomAt(mx-214,my-carouselY,vertical);return true;}if(!observed.isEmpty()&&over(214,carouselY,884,carouselH)&&Math.abs(horizontal)>.01){moveCover(horizontal>0?-1:1);return true;}return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){if(focused!=null)focused=null;else if(qualityMenu)qualityMenu=false;else if(searchReturn!=null)returnFromSearch();else onClose();return true;}if(focused!=null)return focused.keyPressed(key,mods);if(key==GLFW.GLFW_KEY_SPACE){togglePlayback();return true;}if(key==GLFW.GLFW_KEY_LEFT||key==GLFW.GLFW_KEY_RIGHT){moveCover(key==GLFW.GLFW_KEY_LEFT?-1:1);return true;}return false;}
    @Override public boolean charTyped(char character,int mods){return focused!=null&&focused.charTyped(character);}
    @Override public void removed(){covers.close();if(artwork!=null){artwork.close();artwork=null;}coverDragging=false;seek.cancel();drag=null;titleFade.clear();footerFade.clear();resetCovers(frontIndex());}
    @Override public void onClose(){mc.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}





