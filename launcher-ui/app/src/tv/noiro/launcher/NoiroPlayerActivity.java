package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.*;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground-only player. Stream credentials stay in memory, never Intent extras or saved state. */
@UnstableApi
public class NoiroPlayerActivity extends Activity {
    private static final class Request {
        final StremioStreams.Source source;
        final String type, videoId, title, seriesTitle;
        final List<StremioStreams.Source> sources;final java.util.TreeMap<Integer,StremioStreams.Source> qualitySources=new java.util.TreeMap<>(java.util.Collections.reverseOrder());
        final AccountState owner;
        final long startMs;
        final double resumePercent;
        String trailerAudio = "";
        boolean trailer() { return "trailer".equals(type); }
        Request(StremioStreams.Source source, String type, String videoId, String title, String seriesTitle, long startMs, double resumePercent, List<StremioStreams.Source> sources, AccountState owner) {
            this.source = source; this.type = type; this.videoId = videoId; this.title = title; this.seriesTitle=seriesTitle; this.owner=owner;
            List<StremioStreams.Source> eligible=new ArrayList<>();
            for(StremioStreams.Source s:sources)if(!s.needsStremio&&eligible.size()<500)eligible.add(s);
            if(!eligible.contains(source))eligible.add(0,source);
            this.sources=java.util.Collections.unmodifiableList(eligible);for(StremioStreams.Source s:eligible){int quality=PlayerSessionPolicy.resolution(s.name+" "+s.description+" "+s.filename);if(quality>0)qualitySources.putIfAbsent(quality,s);}
            this.startMs = Math.max(0, startMs); this.resumePercent = resumePercent;
        }
    }
    private static final AtomicReference<Request> pending = new AtomicReference<>();
    static void open(Context context, StremioStreams.Source source, String type, String videoId, String title) {
        open(context, source, type, videoId, title, 0, 0);
    }
    static void open(Context context, StremioStreams.Source source, String type, String videoId, String title, long position, double percent) {
        open(context,source,type,videoId,title,position,percent,java.util.Collections.singletonList(source));
    }
    static void open(Context context, StremioStreams.Source source, String type, String videoId, String title, long position, double percent,List<StremioStreams.Source> sources) {
        pending.set(new Request(source, type, videoId, title,title, position, percent,sources,NoiroAccount.current()));
        Intent intent = new Intent(context, NoiroPlayerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    static void openTrailer(Context context, TrailerResolver.Result result, String key, String title) {
        try {
            org.json.JSONObject value = new org.json.JSONObject().put("url", result.video).put("name", "Trailer");
            StremioStreams.Source source = new StremioStreams.Source("Trailer", value);
            source.headers.put("User-Agent", result.userAgent);
            Request playback = new Request(source, "trailer", key, title, title, 0, 0,
                    java.util.Collections.singletonList(source), NoiroAccount.current());
            playback.trailerAudio = result.audio;
            pending.set(playback);
            Intent intent = new Intent(context, NoiroPlayerActivity.class);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Invalid trailer"); }
    }
    /** Inert production hooks. Only the non-exported src/debug fixture subclass overrides these. */
    protected boolean ownedPlayerFixture(){return false;}
    protected List<StremioStreams.Source> ownedPlayerFixtureSources(){return java.util.Collections.emptyList();}
    protected androidx.media3.datasource.DataSource.Factory ownedPlayerFixtureDataSource(){return null;}
    private boolean fixtureMode(){
        String hardware=android.os.Build.HARDWARE,model=android.os.Build.MODEL;
        return ownedPlayerFixture()&&!NoiroAccount.current().signedIn()&&(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&(hardware.equals("ranchu")||hardware.equals("goldfish")||model.startsWith("sdk_"))
                &&getClass().getName().equals("tv.noiro.launcher.PlayerParityFixtureActivity");
    }
    private boolean ownerCurrent(AccountState owner){return fixtureMode()?owner==AccountState.EMPTY:NoiroAccount.current().owns(owner.authKey,owner.uid);}
    private boolean fixtureSource(StremioStreams.Source source){return source.headers.isEmpty()&&(source.url.equals("https://fixture.invalid/noiro-owned-a.mkv")||source.url.equals("https://fixture.invalid/noiro-owned-b.mkv"));}
    private Request request;
    private ExoPlayer player;
    private PlayerView video;
    private TextView loading, subtitleStatus;
    private StyledSubtitleView captions;
    private boolean noiroChrome;private long backExitArmedAt;private int openPanels;
    private final PlayerSessionPolicy.Transitions transitions=new PlayerSessionPolicy.Transitions();
    private long sleepDeadline;
    private int sleepMinutes;
    private final PlayerChromePolicy.DialogPlayback dialogPlayback = new PlayerChromePolicy.DialogPlayback();
    private boolean sleepAtEpisodeEnd, upNextDismissed, episodeLoading, sleepPreventAutoNext;
    private int featureGeneration;
    private final List<Thread> featureWorkers=new ArrayList<>();
    private List<PlayerSessionPolicy.Episode> episodes=new ArrayList<>();private boolean websiteEpisodeNavigation;private String websiteNextId="",websitePreviousId="";private long episodeMetadataLoadedAt;
    private List<PlayerSessionPolicy.Chapter> chapters=new ArrayList<>();
    private List<PlayerSessionPolicy.Segment> manualSegments=new ArrayList<>(), segments=new ArrayList<>();
    private PlayerSessionPolicy.Segment editingSegment;
    private final java.util.Set<String> dismissedSegments=new java.util.HashSet<>();
    private TextView playbackPrompt;
    private int preferredAudioDeviceId;
    private boolean manualSubtitleChoice, subtitlesOff, manualAudioChoice, audioTrackApplied, textTrackApplied;private String selectedAudioLabel="",selectedSubtitleLabel="";
    private String selectedSubtitleLanguage="", selectedAudioLanguage="";
    private volatile long subtitleDelayMs;
    private String videoDecoderName="";
    private SubtitlePreferences subtitlePreferences;
    private List<Cue> embeddedCues = java.util.Collections.emptyList();
    private String addonCaption = "";
    private boolean autoSubtitleAttempted;
    private int autoSubtitleGeneration;
    private Thread autoSubtitleDiscovery;
    private AlertDialog subtitleSettingsDialog;
    private boolean positionEditing, syncEditing;
    private AlertDialog subtitleSyncDialog;
    private TextView subtitleSyncHint;
    private long settingsResumeToken = -1;
    private volatile int subtitleGeneration, subtitleMenuGeneration;
    private Thread subtitleWorker, subtitleDiscovery;
    private AddonSubtitles.Option selectedSubtitle;
    private List<TimedSubtitles.Cue> externalCues = java.util.Collections.emptyList();
    private final Runnable captionTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (selectedSubtitle != null) {
                String value = TimedSubtitles.at(externalCues, PlayerSessionPolicy.subtitlePosition(player.getCurrentPosition(),subtitleDelayMs));
                if (!value.equals(addonCaption)) { addonCaption = value; renderCaptions(); }
            }
            main.postDelayed(this, 150);
        }
    };
    private LinearLayout error;
    private float k;
    private long position, waitingSince;
    private boolean resumeResolved, playbackStarted, percentPending;
    private final UpNextPolicy.Checkpoints checkpoints = new UpNextPolicy.Checkpoints();
    private final Runnable progressTick = new Runnable() {
        @Override public void run() { if (player == null) return; checkpoint(false, true); main.postDelayed(this, 15_000); }
    };
    private void checkpoint(boolean ended) { checkpoint(ended, false); }
    private void checkpoint(boolean ended, boolean periodic) {
        if (fixtureMode() || request == null || request.trailer() || player == null || player.isCurrentMediaItemLive() || !resumeResolved || !playbackStarted) return;
        long currentPosition = player.getCurrentPosition(), duration = player.getDuration();
        if (!checkpoints.shouldRecord(currentPosition, duration, ended, periodic, player.isPlaying())) return;
        if (WatchProgress.record(this, request.owner, request.type, request.videoId, request.seriesTitle,
                currentPosition, duration, ended)) checkpoints.recorded(currentPosition, duration, ended);
    }
    private boolean wantsPlay = true, rendered, failed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable chromeTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (PlayerChromePolicy.sleepExpired(sleepDeadline, SystemClock.elapsedRealtime())) {
                sleepDeadline = 0; sleepMinutes = 0; pendingEpisodeId="";sleepPreventAutoNext=true;dialogPlayback.revoke(); wantsPlay = false; player.pause(); video.showController();
                Toast.makeText(NoiroPlayerActivity.this, "Sleep timer finished. Playback paused.", Toast.LENGTH_LONG).show();
            }
            featureTick(); updateChrome(); main.postDelayed(this, 1000);
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (wantsPlay && (!rendered || player.getPlaybackState() == Player.STATE_BUFFERING)) {
                if (waitingSince == 0) waitingSince = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - waitingSince > 45000) { fail("The stream took too long to start or stopped responding. Retry or choose another source."); return; }
            } else waitingSince = 0;
            main.postDelayed(this, 1000);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); k = TvUi.k(this);
        subtitlePreferences = SubtitlePreferences.load(this);
        request = pending.getAndSet(null);
        if(fixtureMode()){
            List<StremioStreams.Source> sources=ownedPlayerFixtureSources();
            if(sources.size()!=2||!fixtureSource(sources.get(0))||!fixtureSource(sources.get(1))){finish();return;}
            request=new Request(sources.get(0),"movie","owned-player-fixture","Owned local player fixture","Owned local player fixture",0,0,sources,AccountState.EMPTY);
        }
        if (request == null) { finish(); return; }
        position = request.startMs;
        subtitleDelayMs=PlayerSessionPolicy.offset(PlayerUiPreferences.integer(this,subtitleTimingKey(),0));
        percentPending = position == 0 && request.resumePercent > 0;
        if (state != null) { position = state.getLong("position"); wantsPlay = state.getBoolean("playing", false); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        noiroChrome = !request.trailer() && PlayerUiPreferences.noiro(this);
        video = (PlayerView)getLayoutInflater().inflate(noiroChrome ? R.layout.noiro_player_parity_view : R.layout.noiro_player_view, root, false);
        applyAspect();
        video.setUseController(!request.trailer());
        video.setControllerAutoShow(!request.trailer());
        video.setControllerShowTimeoutMs(noiroChrome?4000:5000);
        if(noiroChrome)((androidx.media3.ui.DefaultTimeBar)video.findViewById(androidx.media3.ui.R.id.exo_progress)).setKeyTimeIncrement(PlayerSessionPolicy.seekStep(PlayerUiPreferences.integer(this,"seek_step",10))*1000L);
        if (video.getSubtitleView() != null) video.getSubtitleView().setVisibility(View.GONE);
        video.setShowSubtitleButton(true); video.setShowNextButton(false); video.setShowPreviousButton(false);
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        View subtitleButton = video.findViewById(R.id.noiro_subtitles);
        if (subtitleButton != null) subtitleButton.setOnClickListener(v -> subtitleOptions());
        View audioButton = video.findViewById(androidx.media3.ui.R.id.exo_audio_track);
        if (audioButton != null) audioButton.setOnClickListener(v -> audioOptions());
        video.findViewById(R.id.noiro_subtitle_sync).setOnClickListener(v -> subtitleExtras());
        video.findViewById(R.id.noiro_subtitle_settings).setOnClickListener(v -> subtitleSettings());
        ((TextView)video.findViewById(R.id.noiro_player_title)).setText(request.title);
        TextView actionLabel = video.findViewById(R.id.noiro_player_action_label);
        int[] controls = {androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
                androidx.media3.ui.R.id.exo_ffwd, androidx.media3.ui.R.id.exo_audio_track,
                R.id.noiro_subtitles, R.id.noiro_subtitle_sync, R.id.noiro_subtitle_settings, androidx.media3.ui.R.id.exo_settings};
        for (int id : controls) video.findViewById(id).setOnFocusChangeListener((v, focused) -> {
            if (focused) actionLabel.setText(v.getContentDescription());
        });
        if (noiroChrome) {
            video.findViewById(R.id.noiro_player_close).setOnClickListener(v -> finish());
            video.findViewById(R.id.noiro_player_restart).setOnClickListener(v -> restartVideo());
            video.findViewById(R.id.noiro_player_settings).setOnClickListener(v -> playerSettings());
            video.findViewById(R.id.noiro_player_speed).setOnClickListener(v -> playbackSpeed());
            video.findViewById(R.id.noiro_player_aspect).setOnClickListener(v -> aspectRatio());
            video.findViewById(R.id.noiro_player_info).setOnClickListener(v -> playbackInfo());
            video.findViewById(R.id.noiro_player_sleep).setOnClickListener(v -> sleepTimer());
            video.findViewById(R.id.noiro_player_sources).setOnClickListener(v -> sourcesMenu());
            video.findViewById(R.id.noiro_player_quality).setOnClickListener(v -> qualityMenu());
            video.findViewById(R.id.noiro_player_episodes).setOnClickListener(v -> episodesMenu());
            video.findViewById(R.id.noiro_player_chapters).setOnClickListener(v -> chaptersMenu());
            video.findViewById(R.id.noiro_player_grab).setOnClickListener(v -> grabFrame());
            video.findViewById(R.id.noiro_player_segments).setOnClickListener(v -> segmentMenu());
            video.findViewById(R.id.noiro_player_previous).setOnClickListener(v -> {PlayerSessionPolicy.Episode e=adjacentEpisode(-1);if(e!=null)playEpisode(e);});
            video.findViewById(R.id.noiro_player_next).setOnClickListener(v -> {PlayerSessionPolicy.Episode e=adjacentEpisode(1);if(e!=null)playEpisode(e);});
        }
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if(syncEditing&&visibility==View.VISIBLE){video.hideController();return;}
            enableAddonSubtitles(); if (captions != null) main.post(this::applyCaptionAppearance);
        });
        loading = TvUi.text(this, k, 27, Ui.medium, Color.WHITE);
        loading.setText("Noiro · Loading stream…"); loading.setPadding(35, 25, 35, 25); loading.setBackgroundColor(0xB0000000);
        root.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        subtitleStatus = TvUi.text(this, k, 23, Ui.medium, Color.WHITE);
        subtitleStatus.setBackgroundColor(0xB0000000); subtitleStatus.setPadding(20, 15, 20, 15); subtitleStatus.setVisibility(View.GONE);
        root.addView(subtitleStatus, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
        captions = new StyledSubtitleView(this); captions.setVisibility(View.GONE);
        captions.setPadding(Math.round(100 * k), 0, Math.round(100 * k), 0);
        SubtitleAppearance.apply(captions, subtitlePreferences, k);
        root.addView(captions, new FrameLayout.LayoutParams(-1, -1));
        scrubPreview=new android.widget.ImageView(this);scrubPreview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);scrubPreview.setBackgroundColor(0xF00B0D12);scrubPreview.setVisibility(View.GONE);
        FrameLayout.LayoutParams previewLayout=new FrameLayout.LayoutParams(Math.round(320*k),Math.round(200*k),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);previewLayout.bottomMargin=Math.round(310*k);root.addView(scrubPreview,previewLayout);
        if(noiroChrome)((androidx.media3.ui.TimeBar)video.findViewById(androidx.media3.ui.R.id.exo_progress)).addListener(new androidx.media3.ui.TimeBar.OnScrubListener(){
            public void onScrubStart(androidx.media3.ui.TimeBar bar,long time){showScrubFrame(time);}
            public void onScrubMove(androidx.media3.ui.TimeBar bar,long time){showScrubFrame(time);}
            public void onScrubStop(androidx.media3.ui.TimeBar bar,long time,boolean canceled){if(frameCache!=null)frameCache.clearPreview();scrubPreview.setVisibility(View.GONE);scrubPreview.setImageDrawable(null);}
        });
        playbackPrompt=TvUi.button(this,k,"",v->activatePrompt());playbackPrompt.setVisibility(View.GONE);
        FrameLayout.LayoutParams promptLayout=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);
        promptLayout.setMargins(0,0,Math.round(80*k),Math.round(160*k));root.addView(playbackPrompt,promptLayout);
        error = new LinearLayout(this); error.setOrientation(LinearLayout.VERTICAL); error.setPadding(60, 40, 60, 40);
        error.setBackgroundColor(0xF00B0D12); error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); setContentView(root);
        video.setContentDescription(request.trailer() ? "Trailer. OK pauses or resumes; Back exits."
                : "Noiro video player. OK shows controls; Menu opens player options.");
        video.requestFocus();
    }
    @Override protected void onStart() {
        super.onStart();
        if (request == null || (!fixtureMode()&&!AppAccess.ready(this)) || !ownerCurrent(request.owner)) { finish(); return; }
        if (!failed) startPlayer();
    }
    private void startPlayer() {
        release();transitions.reset(request); failed = false; rendered = false; waitingSince = 0;
        playbackStarted = false; resumeResolved = false;
        autoSubtitleAttempted = manualSubtitleChoice || selectedSubtitle != null;audioTrackApplied=false;textTrackApplied=false;
        error.setVisibility(View.GONE); loading.setText("Noiro · Loading stream…"); loading.setVisibility(View.VISIBLE);
        if (!StreamPolicy.web(request.source.url, true)) { fail("This source requires an unsupported connection. Choose another stream or open this title in Stremio."); return; }
        // Media3 exceptions can contain signed source URLs. Never emit library debug/error logs.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(request.trailer() ? request.source.headers.get("User-Agent") : "NoiroTV-Launcher/1.4.0")
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setDefaultRequestProperties(request.source.headers);
        int seekMs=PlayerSessionPolicy.seekStep(PlayerUiPreferences.integer(this,"seek_step",10))*1000;
        androidx.media3.exoplayer.DefaultRenderersFactory renderers=new PlayerRenderers(this,()->subtitleDelayMs*1000).setEnableDecoderFallback(true);
        if(PlayerUiPreferences.enabled(this,"software_decoder",false))renderers.setMediaCodecSelector((mime,secure,tunneling)->{
            List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> defaults=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling);
            if(!mime.startsWith("video/"))return defaults;List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> software=new ArrayList<>();for(androidx.media3.exoplayer.mediacodec.MediaCodecInfo codec:defaults)if(codec.softwareOnly)software.add(codec);return software.isEmpty()?defaults:software;
        });
        player = new ExoPlayer.Builder(this,renderers).setSeekBackIncrementMs(seekMs).setSeekForwardIncrementMs(seekMs)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(fixtureMode()?ownedPlayerFixtureDataSource():http).setSubtitleParserFactory(new LatinSubtitleParserFactory()))
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(10000, 35000, 1000, 2500)
                        .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        if(preferredAudioDeviceId!=0)for(android.media.AudioDeviceInfo device:tv.noiro.player.audio.PlayerAudioOutput.devices(this))if(device.getId()==preferredAudioDeviceId){player.setPreferredAudioDevice(device);break;}
        String preferredAudio = PlayerChromePolicy.audioLanguage(Prefs.defaultAudioLanguage(), selectedAudioLanguage,
                manualAudioChoice, Prefs.defaultAudioLanguageAvailable());
        if (!preferredAudio.isEmpty()) player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon().setPreferredAudioLanguage(preferredAudio).build());
        if(manualSubtitleChoice)player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguage(selectedSubtitleLanguage.isEmpty()?null:selectedSubtitleLanguage).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,subtitlesOff).build());
        player.setHandleAudioBecomingNoisy(true); video.setPlayer(player);
        player.setPlaybackSpeed(PlayerUiPreferences.speed(this));
        videoDecoderName="";
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener(){
            @Override public void onVideoDecoderInitialized(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime event,String name,long initializedTimestampMs,long initializationDurationMs){videoDecoderName=name;}
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackParametersChanged(androidx.media3.common.PlaybackParameters parameters) {
                PlayerUiPreferences.speed(NoiroPlayerActivity.this, parameters.speed); if(!applyingCloud)queueCloudPreference("speed",parameters.speed);updateChrome();
            }
            @Override public void onTracksChanged(Tracks tracks) { enableAddonSubtitles();applyManualTracks(tracks);maybeAutoSelectSubtitles();collectChapters(tracks); }
            @Override public void onMetadata(Metadata metadata){collectChapters(metadata);}
            @Override public void onTimelineChanged(Timeline timeline, int reason) { resolveResume(); }
            @Override public void onCues(CueGroup group) {
                embeddedCues = group.cues;
                if (selectedSubtitle == null) renderCaptions();
            }
            @Override public void onPlayWhenReadyChanged(boolean play, int reason) { if(play&&player!=null&&player.getPlaybackState()!=Player.STATE_ENDED)sleepPreventAutoNext=false;wantsPlay = play; if (!play) checkpoint(false); }
            @Override public void onRenderedFirstFrame() { rendered = true; playbackStarted = true; loading.setVisibility(View.GONE); }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    if (request.trailer()) {
                        finish();
                        return;
                    }
                    checkpoint(true); wantsPlay = false; video.showController();
                    if(sleepAtEpisodeEnd){sleepAtEpisodeEnd=false;sleepPreventAutoNext=true;dialogPlayback.revoke();player.pause();Toast.makeText(NoiroPlayerActivity.this,"End of episode. Playback stopped.",Toast.LENGTH_LONG).show();}
                    else if(mayAutoAdvance()){PlayerSessionPolicy.Episode next=adjacentEpisode(1);if(next!=null)playEpisode(next);}
                }
                else if (state == Player.STATE_BUFFERING) { loading.setText("Noiro · Buffering…"); loading.setVisibility(View.VISIBLE); }
                else if (state == Player.STATE_READY) {
                    resolveResume();applyDeferredCloudMetadata();
                    if(player.isCurrentMediaItemLive()){video.setShowRewindButton(false);video.setShowFastForwardButton(false);}
                    if (rendered) loading.setVisibility(View.GONE); maybeAutoSelectSubtitles();
                }
            }
            @Override public void onPlayerError(PlaybackException e) { fail(PlaybackPolicy.message(e.errorCode)); }
        });
        if (request.trailer() && !request.trailerAudio.isEmpty()) {
            if (!TrailerResolver.mediaUrl(request.source.url, "googlevideo.com") || !TrailerResolver.mediaUrl(request.trailerAudio, "googlevideo.com")) {
                fail("Couldn't play this trailer."); return;
            }
            DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(http);
            player.setMediaSource(new androidx.media3.exoplayer.source.MergingMediaSource(
                    sources.createMediaSource(MediaItem.fromUri(request.source.url)),
                    sources.createMediaSource(MediaItem.fromUri(request.trailerAudio))));
        } else player.setMediaItem(MediaItem.fromUri(request.source.url));
        player.seekTo(position); player.prepare(); player.setPlayWhenReady(wantsPlay);
        video.requestFocus(); main.post(watchdog); main.post(captionTick); main.post(chromeTick); main.postDelayed(progressTick, 15_000);
        player.setVolume(Math.max(0,Math.min(100,PlayerUiPreferences.integer(this,"volume",100)))/100f);
        subtitleDelayMs=PlayerSessionPolicy.offset(PlayerUiPreferences.integer(this,subtitleTimingKey(),0));startFeatureLoading();
        if (selectedSubtitle != null) selectAddonSubtitle(selectedSubtitle);
    }
    private void resolveResume() {
        if (player == null || resumeResolved || player.getPlaybackState() != Player.STATE_READY) return;
        if(player.isCurrentMediaItemLive()){resumeResolved=true;percentPending=false;position=0;player.seekToDefaultPosition();return;}
        if (percentPending && position <= 0 && player.getDuration() <= 0) return;
        long start = UpNextPolicy.start(position, percentPending ? request.resumePercent : 0, player.getDuration(), false);
        resumeResolved = true; percentPending = false;
        if (start > 0) player.seekTo(start);
    }
    private void fail(String message) {
        failed = true; release(); loading.setVisibility(View.GONE); error.removeAllViews(); error.setVisibility(View.VISIBLE);
        TextView text = TvUi.text(this, k, 29, Ui.regular, Color.WHITE); text.setText(message); error.addView(text);
        addButton("Retry stream", () -> { wantsPlay = true; startPlayer(); }).requestFocus();
        addButton(request.trailer() ? "Back to details" : "Choose another stream", this::finish);
        if (!request.trailer()) addButton("Open title in Stremio", this::stremio);
    }
    private boolean featureCurrent(int run,Request expected) {
        return run==featureGeneration&&request==expected&&player!=null&&!isFinishing()&&!isDestroyed()&&ownerCurrent(expected.owner);
    }
    private Thread featureWorker(String name,Runnable action){Thread worker=new Thread(()->{try{action.run();}finally{Thread done=Thread.currentThread();main.post(()->featureWorkers.remove(done));}},name);featureWorkers.add(worker);worker.start();return worker;}
    private PlayerChapterReader chapterReader;private Runnable pendingChapterLoad;private Thread episodeWorker;
    private List<PlayerSessionPolicy.Segment> publicSegments=new ArrayList<>();
    private void startFeatureLoading(){
        final Request expected=request;final int run=featureGeneration;
        chapters=new ArrayList<>();publicSegments=new ArrayList<>();websiteEpisodeNavigation=false;websiteNextId=websitePreviousId="";manualSegments=new ArrayList<>();manualDurationLoaded=-1;cloudSegments=new ArrayList<>();lastCloudMetadata=null;cloudMetadataAppliedDuration=-1;
        skipPolicyLoaded=false;allowedSkipKinds.clear();
        if(fixtureMode() || expected.trailer())return; // Owned local QA never contacts account/cloud/provider metadata services.
        if(NoiroAccount.ready()){
            cloud=new PlayerCloudState(this,expected.owner,expected.type,expected.videoId,main,state->{if(featureCurrent(run,expected))applyCloudState(state);});org.json.JSONObject cached=cloud.cached();if(cached!=null)applyCloudState(cached);cloud.refresh();
        }
        frameCache=new PlayerFrameCache(this,expected.owner,expected.videoId,main);lastPreviewBucket=-1;
        if("series".equals(request.type)&&(episodes.isEmpty()||SystemClock.elapsedRealtime()-episodeMetadataLoadedAt>15*60_000L))featureWorker("player-episodes",()->{
            try{List<PlayerSessionPolicy.Episode> loaded=PlayerEpisodes.load(expected.videoId);main.post(()->{if(featureCurrent(run,expected)){episodeMetadataLoadedAt=SystemClock.elapsedRealtime();if(websiteEpisodeNavigation)mergeEpisodeNames(loaded);else episodes=loaded;updateChrome();}});}catch(Exception unavailable){ }
        });
        chapterReader=new PlayerChapterReader(expected.source,()->featureCurrent(run,expected));final PlayerChapterReader reader=chapterReader;
        pendingChapterLoad=new Runnable(){public void run(){if(!featureCurrent(run,expected))return;if(!rendered&&player.getPlaybackState()!=Player.STATE_READY){main.postDelayed(this,500);return;}featureWorker("player-chapters",()->{
            List<PlayerChapters.Chapter> parsed=PlayerChapters.read(reader);List<PlayerSessionPolicy.Chapter> loaded=new ArrayList<>();
            for(PlayerChapters.Chapter c:parsed)loaded.add(new PlayerSessionPolicy.Chapter(c.title,c.startMs));
            main.post(()->{if(featureCurrent(run,expected)&&!loaded.isEmpty()){chapters=loaded;updateChrome();}});
        });}};main.post(pendingChapterLoad);
        featureWorker("player-skip-metadata",()->{
            try{List<PlayerSessionPolicy.Segment> loaded=PlayerSegmentStore.publicSegments(expected.videoId);main.post(()->{if(featureCurrent(run,expected))publicSegments=loaded;});}catch(Exception unavailable){ }
        });
    }
    private void collectChapters(Tracks tracks){for(Tracks.Group group:tracks.getGroups())for(int i=0;i<group.length;i++){Metadata data=group.getTrackFormat(i).metadata;if(data!=null)collectChapters(data);}}
    private void collectChapters(Metadata metadata){
        java.util.TreeMap<Long,PlayerSessionPolicy.Chapter> found=new java.util.TreeMap<>();for(PlayerSessionPolicy.Chapter c:chapters)found.put(c.start,c);
        for(int i=0;i<metadata.length();i++)if(metadata.get(i) instanceof androidx.media3.extractor.metadata.id3.ChapterFrame){
            androidx.media3.extractor.metadata.id3.ChapterFrame c=(androidx.media3.extractor.metadata.id3.ChapterFrame)metadata.get(i);String title="";
            for(int n=0;n<c.getSubFrameCount();n++)if(c.getSubFrame(n) instanceof androidx.media3.extractor.metadata.id3.TextInformationFrame){androidx.media3.extractor.metadata.id3.TextInformationFrame text=(androidx.media3.extractor.metadata.id3.TextInformationFrame)c.getSubFrame(n);if("TIT2".equals(text.id)&&!text.values.isEmpty())title=text.values.get(0);}
            if(c.startTimeMs>=0)found.put((long)c.startTimeMs,new PlayerSessionPolicy.Chapter(title,c.startTimeMs));
        }
        chapters=new ArrayList<>(found.values());
    }
    private static String safeSourceText(String value){return value==null?"":value.replaceAll("(?i)https?://\\S+","[private link]").replaceAll("[\\r\\n\\t]+"," ").substring(0,Math.min(180,value.replaceAll("(?i)https?://\\S+","[private link]").replaceAll("[\\r\\n\\t]+"," ").length()));}
    private String sourceLabel(StremioStreams.Source source){return safeSourceText(source.addon+" · "+source.name+" · "+source.description);}
    private int sourceResolution(StremioStreams.Source source){return PlayerSessionPolicy.resolution(source.name+" "+source.description+" "+source.filename);}
    private void titleQr(){String titleLink="stremio:///detail/"+request.type+"/"+request.videoId.split(":")[0]+"/"+request.videoId;Qr qr=Qr.encode(titleLink);int scale=Math.max(3,Math.round(480*k)/(qr.size+8)),size=(qr.size+8)*scale;android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.RGB_565);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(Color.WHITE);android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(Color.BLACK);for(int y=0;y<qr.size;y++)for(int x=0;x<qr.size;x++)if(qr.dark(x,y))canvas.drawRect((x+4)*scale,(y+4)*scale,(x+5)*scale,(y+5)*scale,paint);android.widget.ImageView image=new android.widget.ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);showBottom(new AlertDialog.Builder(this).setTitle("Share title · scan with Stremio").setView(image).setPositiveButton("Done",null).create());}
    private void sourcesMenu(){
        if(request.sources.size()<2)return;final Request expected=request;final long menuTicket=transitions.capture(expected);String[] labels=new String[expected.sources.size()];int selected=0;
        for(int i=0;i<labels.length;i++){labels[i]=sourceLabel(expected.sources.get(i));if(expected.sources.get(i)==expected.source)selected=i;}
        showBottom(new AlertDialog.Builder(this).setTitle("Sources · keep playback position").setSingleChoiceItems(labels,selected,(d,which)->{long action=transitions.begin(expected,menuTicket);if(action<0)return;d.dismiss();switchSource(expected,expected.sources.get(which),action);}).setNegativeButton("Back",null).create());
    }
    private java.util.TreeMap<Integer,StremioStreams.Source> qualitySources(){return request.qualitySources;}
    private int adaptiveQualityCount(){if(player==null)return 0;java.util.Set<Integer> heights=new java.util.HashSet<>();for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_VIDEO)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)&&group.getTrackFormat(i).height>0)heights.add(group.getTrackFormat(i).height);return heights.size();}
    private void qualityMenu(){
        if(player==null)return;final Request expected=request;final long menuTicket=transitions.capture(expected);List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(adaptiveQualityCount()>1){labels.add("Auto · current stream");actions.add(()->player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()));
            for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_VIDEO)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)&&group.getTrackFormat(i).height>0){final int index=i;labels.add(group.getTrackFormat(i).height+"p · current stream");actions.add(()->player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),index)).build()));}}
        if(qualitySources().size()>1)for(java.util.Map.Entry<Integer,StremioStreams.Source> entry:qualitySources().entrySet()){StremioStreams.Source source=entry.getValue();labels.add(entry.getKey()+"p · "+safeSourceText(source.addon)+(source==request.source?" ✓":""));actions.add(()->switchSource(expected,source,transitions.capture(expected)));}
        if(labels.size()<2)return;
        showBottom(new AlertDialog.Builder(this).setTitle("Quality · keep playback position").setItems(labels.toArray(new String[0]),(d,which)->{long action=transitions.begin(expected,menuTicket);if(action<0||player==null)return;actions.get(which).run();transitions.complete(expected,action);}).setNegativeButton("Back",null).create());
    }
    private void applyManualTracks(Tracks tracks){
        if(player==null)return;for(int type:new int[]{C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_TEXT}){
            boolean audio=type==C.TRACK_TYPE_AUDIO;if(audio?(!manualAudioChoice||audioTrackApplied):(!manualSubtitleChoice||textTrackApplied||subtitlesOff||selectedSubtitle!=null))continue;
            String language=audio?selectedAudioLanguage:selectedSubtitleLanguage,label=audio?selectedAudioLabel:selectedSubtitleLabel;TrackSelectionOverride fallback=null,exact=null;
            for(Tracks.Group group:tracks.getGroups())if(group.getType()==type)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)){Format f=group.getTrackFormat(i);boolean sameLanguage=!language.isEmpty()&&language.equals(PlayerChromePolicy.language(f.language));boolean sameLabel=!label.isEmpty()&&label.equals(f.label);if(sameLabel&&(language.isEmpty()||sameLanguage))exact=new TrackSelectionOverride(group.getMediaTrackGroup(),i);if(fallback==null&&(sameLanguage||language.isEmpty()&&label.isEmpty()))fallback=new TrackSelectionOverride(group.getMediaTrackGroup(),i);}
            TrackSelectionOverride chosen=exact==null?fallback:exact;if(chosen!=null){if(audio)audioTrackApplied=true;else textTrackApplied=true;player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(chosen).setTrackTypeDisabled(type,false).build());}
        }
    }
    private void captureTrackLanguages(){
        if(player==null||manualAudioChoice)return;for(Tracks.Group group:player.getCurrentTracks().getGroups())for(int i=0;i<group.length;i++)if(group.isTrackSelected(i)){String language=PlayerChromePolicy.language(group.getTrackFormat(i).language);if(group.getType()==C.TRACK_TYPE_AUDIO&&!language.isEmpty())selectedAudioLanguage=language;}
    }
    private void switchSource(Request expected,StremioStreams.Source source,long action){
        if(request!=expected||!transitions.current(expected,action)||!expected.sources.contains(source)||source.needsStremio||fixtureMode()&&!fixtureSource(source)||!ownerCurrent(expected.owner))return;
        if(source==expected.source){transitions.complete(expected,action);return;}
        captureTrackLanguages();long at=player==null?position:player.getCurrentPosition();boolean play=player==null?wantsPlay:player.getPlayWhenReady();Request old=request;
        boolean unresolvedPercent=!resumeResolved&&percentPending;release();request=new Request(source,old.type,old.videoId,old.title,old.seriesTitle,at,unresolvedPercent?old.resumePercent:0,old.sources,old.owner);position=at;percentPending=unresolvedPercent;wantsPlay=play;failed=false;startPlayer();
    }
    private boolean mayAutoAdvance(){return player!=null&&!transitions.pending()&&PlayerSessionPolicy.canAdvance(player.isCurrentMediaItemLive(),PlayerUiPreferences.enabled(this,"autoplay",true),upNextDismissed,sleepAtEpisodeEnd,sleepPreventAutoNext,openPanels);}
    private PlayerSessionPolicy.Episode adjacentEpisode(int direction){if(!"series".equals(request.type))return null;if(websiteEpisodeNavigation){String id=direction<0?websitePreviousId:websiteNextId;for(PlayerSessionPolicy.Episode e:episodes)if(e.id.equals(id)&&e.aired(System.currentTimeMillis()))return e;return null;}return PlayerSessionPolicy.adjacent(episodes,request.videoId,direction,System.currentTimeMillis());}
    private void mergeEpisodeNames(List<PlayerSessionPolicy.Episode> metadata){java.util.Map<String,String> names=new java.util.HashMap<>();for(PlayerSessionPolicy.Episode e:metadata)names.put(e.id,e.title);List<PlayerSessionPolicy.Episode> merged=new ArrayList<>();for(PlayerSessionPolicy.Episode e:episodes)merged.add(new PlayerSessionPolicy.Episode(e.id,names.getOrDefault(e.id,e.title),e.season,e.number,e.released));episodes=merged;}
    private void applyWebsiteEpisodes(org.json.JSONObject state){org.json.JSONObject navigation=state.optJSONObject("episodeNavigation");if(navigation==null||!navigation.optBoolean("available")||!request.videoId.equals(state.optString("videoId")))return;List<PlayerSessionPolicy.Episode> roster=new ArrayList<>();org.json.JSONArray values=navigation.optJSONArray("episodes");for(int i=0;values!=null&&i<Math.min(2500,values.length());i++){org.json.JSONObject value=values.optJSONObject(i);if(value==null)continue;String id=value.optString("id");if(!UpNextPolicy.valid("series",request.videoId.split(":")[0],id)||value.optInt("season")<1)continue;roster.add(new PlayerSessionPolicy.Episode(id,"",value.optInt("season"),value.optInt("episode"),value.optBoolean("aired")?0:Long.MAX_VALUE));}List<PlayerSessionPolicy.Episode> local=episodes;episodes=roster;mergeEpisodeNames(local);websiteEpisodeNavigation=true;org.json.JSONObject next=navigation.optJSONObject("next"),previous=navigation.optJSONObject("previous");websiteNextId=next==null?"":next.optString("id");websitePreviousId=previous==null?"":previous.optString("id");}

    private boolean episodesAvailable(){for(PlayerSessionPolicy.Episode e:episodes)if(e.aired(System.currentTimeMillis()))return true;return false;}
    private void episodesMenu(){
        final Request expected=request;final long menuTicket=transitions.capture(expected);List<PlayerSessionPolicy.Episode> aired=new ArrayList<>();for(PlayerSessionPolicy.Episode e:episodes)if(e.aired(System.currentTimeMillis()))aired.add(e);
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();PlayerSessionPolicy.Episode previous=adjacentEpisode(-1),next=adjacentEpisode(1);
        if(previous!=null){labels.add("Previous · "+previous.label());actions.add(()->selectEpisode(expected,menuTicket,previous));}if(next!=null){labels.add("Next · "+next.label());actions.add(()->selectEpisode(expected,menuTicket,next));}
        for(PlayerSessionPolicy.Episode e:aired){labels.add((e.id.equals(request.videoId)?"✓ ":"")+e.label());actions.add(()->selectEpisode(expected,menuTicket,e));}
        if(labels.isEmpty())return;showBottom(new AlertDialog.Builder(this).setTitle("Episodes").setItems(labels.toArray(new String[0]),(d,which)->actions.get(which).run()).setNegativeButton("Back",null).create());
    }
    private long nextLoadRetryAfter;private String nextLoadFailedId="";
    private long pendingEpisodeAction=-1;private String loadedNextId="",pendingEpisodeId="",loadingEpisodeId="";private List<StremioStreams.Source> loadedNextSources=new ArrayList<>();private int episodeRequestGeneration;
    private void playEpisode(PlayerSessionPolicy.Episode episode){loadEpisode(episode,true,-1);}
    private void selectEpisode(Request expected,long menuTicket,PlayerSessionPolicy.Episode episode){
        long action=transitions.begin(expected,menuTicket);if(action<0)return;
        if(episode.id.equals(expected.videoId)){transitions.complete(expected,action);return;}
        pendingEpisodeId="";loadEpisode(episode,true,action);
    }
    private void loadEpisode(PlayerSessionPolicy.Episode episode,boolean play){loadEpisode(episode,play,-1);}
    private void loadEpisode(PlayerSessionPolicy.Episode episode,boolean play,long explicitAction){
        if(fixtureMode()||player==null||player.isCurrentMediaItemLive()||!episode.aired(System.currentTimeMillis())){transitions.complete(request,explicitAction);return;}
        if(!play&&transitions.pending())return;
        if(!play&&episode.id.equals(nextLoadFailedId)&&SystemClock.elapsedRealtime()<nextLoadRetryAfter)return;
        if(loadedNextId.equals(episode.id)&&!loadedNextSources.isEmpty()){if(play)startEpisode(episode,loadedNextSources);return;}
        if(episodeLoading&&loadingEpisodeId.equals(episode.id)){if(play){pendingEpisodeId=episode.id;pendingEpisodeAction=explicitAction;}return;}
        final int ticket=++episodeRequestGeneration,run=featureGeneration;final Request expected=request;
        episodeLoading=true;loadingEpisodeId=episode.id;pendingEpisodeId=play?episode.id:"";pendingEpisodeAction=explicitAction;
        if(play)Toast.makeText(this,"Loading "+episode.label()+"…",Toast.LENGTH_LONG).show();
        if(episodeWorker!=null)episodeWorker.interrupt();episodeWorker=featureWorker("player-next-sources",()->{
            try{StremioStreams.Result result=StremioStreams.load(expected.owner,"series",episode.id);List<StremioStreams.Source> eligible=new ArrayList<>();for(StremioStreams.Source s:result.sources)if(!s.needsStremio)eligible.add(s);
                main.post(()->{if(!featureCurrent(run,expected)||ticket!=episodeRequestGeneration)return;episodeLoading=false;loadedNextId=episode.id;loadedNextSources=eligible;
                    if(eligible.isEmpty()){nextLoadFailedId=episode.id;nextLoadRetryAfter=SystemClock.elapsedRealtime()+60_000;if(pendingEpisodeId.equals(episode.id))Toast.makeText(this,"No compatible sources found for this episode.",Toast.LENGTH_LONG).show();pendingEpisodeId="";transitions.complete(expected,pendingEpisodeAction);return;}
                    if(pendingEpisodeId.equals(episode.id))startEpisode(episode,eligible);});
            }catch(Exception unavailable){main.post(()->{if(featureCurrent(run,expected)&&ticket==episodeRequestGeneration){episodeLoading=false;nextLoadFailedId=episode.id;nextLoadRetryAfter=SystemClock.elapsedRealtime()+60_000;if(pendingEpisodeId.equals(episode.id))Toast.makeText(this,"Couldn't load episode sources. Try again.",Toast.LENGTH_LONG).show();pendingEpisodeId="";transitions.complete(expected,pendingEpisodeAction);}});}
        });
    }
    private void startEpisode(PlayerSessionPolicy.Episode episode,List<StremioStreams.Source> sources){
        if(!ownerCurrent(request.owner))return;Request old=request;StremioStreams.Source chosen=sources.get(0);int wanted=sourceResolution(old.source),best=Integer.MAX_VALUE;
        for(StremioStreams.Source source:sources){int cost=(source.addon.equals(old.source.addon)?0:10000)+Math.abs(sourceResolution(source)-wanted);if(cost<best){best=cost;chosen=source;}}
        release();selectedSubtitle=null;externalCues=java.util.Collections.emptyList();addonCaption="";manualSubtitleChoice=false;subtitlesOff=false;upNextDismissed=false;sleepPreventAutoNext=false;dismissedSegments.clear();sleepAtEpisodeEnd=false;
        // A fresh episode uses its saved Premium default or Automatic. Manual tracks belong to the previous video.
        manualAudioChoice=false;selectedAudioLanguage="";selectedAudioLabel="";audioTrackApplied=false;
        request=new Request(chosen,"series",episode.id,old.seriesTitle+" · "+episode.label(),old.seriesTitle,0,0,sources,old.owner);position=0;percentPending=false;wantsPlay=true;failed=false;loadedNextId="";loadedNextSources.clear();pendingEpisodeId="";
        ((TextView)video.findViewById(R.id.noiro_player_title)).setText(request.title);startPlayer();
    }
    private void chaptersMenu(){
        if(player==null||player.isCurrentMediaItemLive())return;final List<PlayerSessionPolicy.Chapter> available=new ArrayList<>();for(PlayerSessionPolicy.Chapter c:chapters)if(c.start>=0&&c.start<player.getDuration())available.add(c);if(available.size()<2)return;String[] labels=new String[available.size()];int selected=0;
        for(int i=0;i<labels.length;i++){PlayerSessionPolicy.Chapter c=available.get(i);labels[i]=PlayerChromePolicy.time(c.start)+" · "+(c.title.isEmpty()?"Chapter "+(i+1):c.title);if(c.start<=player.getCurrentPosition())selected=i;}
        showBottom(new AlertDialog.Builder(this).setTitle("Chapters").setSingleChoiceItems(labels,selected,(d,which)->{if(player!=null)player.seekTo(available.get(which).start);d.dismiss();}).setNegativeButton("Back",null).create());
    }
    private void rebuildSegments(){
        long duration=player==null?0:player.getDuration();List<PlayerSessionPolicy.Segment> resolved=PlayerSessionPolicy.chapterSegments(chapters,duration);
        for(List<PlayerSessionPolicy.Segment> layer:java.util.Arrays.asList(publicSegments,cloudSegments,manualSegments))for(PlayerSessionPolicy.Segment candidate:layer)if(PlayerSessionPolicy.valid(candidate,duration)){resolved.removeIf(s->s.kind.equals(candidate.kind)&&s.start<candidate.end&&candidate.start<s.end);resolved.add(candidate);}
        resolved.sort(java.util.Comparator.comparingLong(s->s.start));segments=resolved;
    }
    private PlayerSessionPolicy.Segment activeSegment(){if(player==null||!skipPolicyLoaded)return null;long at=player.getCurrentPosition();for(PlayerSessionPolicy.Segment s:segments)if(Boolean.TRUE.equals(allowedSkipKinds.get(s.kind))&&at>=s.start&&at<s.end&&!dismissedSegments.contains(s.key()))return s;return null;}
    private void featureTick(){
        if(request.trailer())return;
        if(player!=null&&player.getDuration()>0&&manualDurationLoaded!=player.getDuration()){manualDurationLoaded=player.getDuration();manualSegments=PlayerSegmentStore.load(this,request.owner,request.videoId,manualDurationLoaded);}
        if(player!=null&&player.getDuration()>0){long duration=player.getDuration();chapters.removeIf(c->c.start<0||c.start>=duration);}
        if(player==null||player.isCurrentMediaItemLive()){if(playbackPrompt!=null)playbackPrompt.setVisibility(View.GONE);return;}rebuildSegments();
        PlayerSessionPolicy.Episode next=adjacentEpisode(1);long left=PlayerChromePolicy.remaining(player.getCurrentPosition(),player.getDuration());boolean showNext=next!=null&&!upNextDismissed&&!sleepAtEpisodeEnd&&!sleepPreventAutoNext&&left>0&&left<=20_000;
        if(showNext&&PlayerUiPreferences.enabled(this,"autoplay",true))loadEpisode(next,false);
        PlayerSessionPolicy.Segment skip=activeSegment();boolean chromeVisible=video.isControllerFullyVisible()||openPanels>0;
        if(skip!=null&&!showNext&&!chromeVisible&&PlayerUiPreferences.enabled(this,"auto_skip",false)&&player.isPlaying()&&editingSegment==null){dismissedSegments.add(skip.key());player.seekTo(skip.end);skip=null;}
        if(!chromeVisible&&editingSegment==null&&(showNext||skip!=null)){playbackPrompt.setText(showNext?"Up Next · "+next.label()+" · Play now":skip.label());playbackPrompt.setVisibility(View.VISIBLE);}else playbackPrompt.setVisibility(View.GONE);
        maybeCapturePreview();
    }
    private void activatePrompt(){if(player==null)return;final Request expected=request;final long menuTicket=transitions.capture(expected);PlayerSessionPolicy.Episode next=adjacentEpisode(1);long left=PlayerChromePolicy.remaining(player.getCurrentPosition(),player.getDuration());if(next!=null&&!upNextDismissed&&!sleepAtEpisodeEnd&&!sleepPreventAutoNext&&left>0&&left<=20_000){showBottom(new AlertDialog.Builder(this).setTitle("Up Next · "+next.label()).setPositiveButton("Play now",(d,w)->selectEpisode(expected,menuTicket,next)).setNegativeButton("Watch credits",(d,w)->{upNextDismissed=true;pendingEpisodeId="";playbackPrompt.setVisibility(View.GONE);video.requestFocus();}).create());return;}PlayerSessionPolicy.Segment s=activeSegment();if(s!=null){dismissedSegments.add(s.key());player.seekTo(s.end);playbackPrompt.setVisibility(View.GONE);video.requestFocus();}}
    private PlayerFrameCache frameCache;private android.widget.ImageView scrubPreview;private boolean frameCaptureBusy;private long lastPreviewBucket=-1;private byte[] pendingGrab;private String pendingGrabOwner="";
    private void showScrubFrame(long time){if(frameCache==null)return;final int run=featureGeneration;final Request expected=request;frameCache.show(time,image->{if(featureCurrent(run,expected)){scrubPreview.setImageBitmap(image);scrubPreview.setVisibility(image==null?View.GONE:View.VISIBLE);}});}
    private void captureFrame(int maxWidth,java.util.function.Consumer<android.graphics.Bitmap> callback){
        if(player==null||!rendered||frameCaptureBusy){callback.accept(null);return;}View surface=video.getVideoSurfaceView();if(surface==null||surface.getWidth()<1||surface.getHeight()<1){callback.accept(null);return;}
        int width=Math.min(maxWidth,surface.getWidth()),height=Math.max(1,Math.round(width*surface.getHeight()/(float)surface.getWidth()));
        final int run=featureGeneration;final Request expected=request;final android.graphics.Bitmap bitmap;try{bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);}catch(OutOfMemoryError unavailable){callback.accept(null);return;}frameCaptureBusy=true;
        if(surface instanceof android.view.SurfaceView){
            try{android.view.PixelCopy.request((android.view.SurfaceView)surface,bitmap,result->{frameCaptureBusy=false;if(result==android.view.PixelCopy.SUCCESS&&featureCurrent(run,expected))callback.accept(bitmap);else{bitmap.recycle();callback.accept(null);}},main);}catch(Exception unavailable){frameCaptureBusy=false;bitmap.recycle();callback.accept(null);}
        }else if(surface instanceof android.view.TextureView){frameCaptureBusy=false;android.graphics.Bitmap captured=((android.view.TextureView)surface).getBitmap(bitmap);if(captured!=null&&featureCurrent(run,expected))callback.accept(captured);else{bitmap.recycle();callback.accept(null);}}
        else{frameCaptureBusy=false;bitmap.recycle();callback.accept(null);}
    }
    private void maybeCapturePreview(){if(!noiroChrome||player==null||!player.isPlaying()||frameCache==null||frameCaptureBusy)return;long bucket=player.getCurrentPosition()/10_000;if(bucket==lastPreviewBucket)return;lastPreviewBucket=bucket;long at=player.getCurrentPosition();PlayerFrameCache cache=frameCache;captureFrame(240,bitmap->{if(bitmap!=null&&cache==frameCache)cache.save(at,bitmap);else if(bitmap!=null)bitmap.recycle();});}
    private void grabFrame(){
        captureFrame(2560,bitmap->{if(bitmap==null){Toast.makeText(this,"This video frame could not be captured. Try again after playback starts.",Toast.LENGTH_LONG).show();return;}
            android.widget.ImageView image=new android.widget.ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);image.setMinimumHeight(Math.round(360*k));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Frame grab · "+PlayerChromePolicy.time(player==null?position:player.getCurrentPosition())).setView(image).setPositiveButton("Save image…",(d,w)->{
                AccountState owner=request.owner;new Thread(()->{try{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,bytes);byte[] encoded=bytes.toByteArray();main.post(()->{if(!isDestroyed()&&ownerCurrent(owner))saveGrab(encoded,owner);});}catch(Exception unavailable){ }},"player-frame-encode").start();
            }).setNegativeButton("Done",null).create();showBottom(dialog);
        });
    }
    private java.io.File grabFolder(AccountState owner){return new java.io.File(new java.io.File(getFilesDir(),"saved-frames"),PlayerSegmentStore.key(owner,"grabs"));}
    private void saveGrab(byte[] bytes,AccountState owner){
        pendingGrab=bytes;pendingGrabOwner=owner.uid;Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,"Noiro-frame-"+System.currentTimeMillis()+".jpg");
        if(save.resolveActivity(getPackageManager())!=null){try{startActivityForResult(save,731);return;}catch(Exception unavailable){ }}
        pendingGrab=null;new Thread(()->{boolean saved=false;try{if(ownerCurrent(owner)){java.io.File folder=grabFolder(owner);folder.mkdirs();try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(folder,System.currentTimeMillis()+".jpg"))){output.write(bytes);saved=true;}}}catch(Exception unavailable){}final boolean okay=saved;main.post(()->{if(!isDestroyed()&&ownerCurrent(owner))Toast.makeText(this,okay?"Saved in Noiro · Player settings → Saved frames.":"Couldn't save this frame.",Toast.LENGTH_LONG).show();});},"player-frame-local-save").start();
    }
    private void savedFrames(){
        final AccountState owner=request.owner;java.io.File[] files=grabFolder(owner).listFiles((d,n)->n.matches("[0-9]+\\.jpg"));if(files==null||files.length==0)return;java.util.Arrays.sort(files,java.util.Comparator.comparingLong(java.io.File::lastModified).reversed());int count=Math.min(100,files.length);String[] labels=new String[count];java.text.DateFormat date=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Ui.locale());for(int i=0;i<count;i++)labels[i]=date.format(new java.util.Date(files[i].lastModified()));
        showBottom(new AlertDialog.Builder(this).setTitle("Saved frames · this account").setItems(labels,(d,which)->{java.io.File file=files[which];new Thread(()->{android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());main.post(()->{if(image==null||isDestroyed()||!ownerCurrent(owner))return;android.widget.ImageView view=new android.widget.ImageView(this);view.setImageBitmap(image);view.setAdjustViewBounds(true);showBottom(new AlertDialog.Builder(this).setTitle(labels[which]).setView(view).setPositiveButton("Save a copy…",(dialog,w)->{try{byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());saveGrab(bytes,owner);}catch(Exception unavailable){}}).setNeutralButton("Delete",(dialog,w)->{if(ownerCurrent(owner))file.delete();}).setNegativeButton("Done",null).create());});},"player-saved-frame-view").start();}).setNegativeButton("Back",null).create());
    }
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code!=731)return;byte[] bytes=pendingGrab;pendingGrab=null;
        if(result!=RESULT_OK||data==null||data.getData()==null||bytes==null||request==null||!ownerCurrent(request.owner)||!request.owner.uid.equals(pendingGrabOwner))return;
        Uri destination=data.getData();AccountState expected=request.owner;new Thread(()->{boolean saved=false;try{if(ownerCurrent(expected)){try(java.io.OutputStream output=getContentResolver().openOutputStream(destination)){if(output!=null){output.write(bytes);saved=true;}}}}catch(Exception unavailable){ }final boolean okay=saved;main.post(()->{if(!isDestroyed()&&ownerCurrent(expected))Toast.makeText(this,okay?"Frame saved.":"Couldn't save the frame.",Toast.LENGTH_LONG).show();});},"player-frame-save").start();
    }
    private void segmentMenu(){
        if(player==null||player.getDuration()<=0||player.isCurrentMediaItemLive())return;List<String> labels=new ArrayList<>();List<PlayerSessionPolicy.Segment> choices=new ArrayList<>();labels.add("Add my segment");choices.add(null);
        for(PlayerSessionPolicy.Segment s:manualSegments){labels.add("My "+s.kind+" · "+PlayerChromePolicy.time(s.start)+"–"+PlayerChromePolicy.time(s.end));choices.add(s);}
        for(PlayerSessionPolicy.Segment s:segments)if(!manualSegments.contains(s)){labels.add("Copy "+s.kind+" · "+PlayerChromePolicy.time(s.start)+"–"+PlayerChromePolicy.time(s.end));choices.add(s);}
        showBottom(new AlertDialog.Builder(this).setTitle("Skip segments · private markers").setItems(labels.toArray(new String[0]),(d,which)->segmentEditor(choices.get(which))).setNegativeButton("Back",null).create());
    }
    private void segmentEditor(PlayerSessionPolicy.Segment existing){
        if(player==null)return;final long duration=player.getDuration();final long resumeToken=dialogPlayback.capture(player.getPlayWhenReady());player.pause();
        String[] kinds={"intro","recap","credits","preview"};int initial=existing==null?(player.getCurrentPosition()>=duration*.5?2:0):java.util.Arrays.asList(kinds).indexOf(existing.kind);
        final int[] kind={Math.max(0,initial)};final long[] bounds={existing==null?player.getCurrentPosition():existing.start,existing==null?Math.min(duration,player.getCurrentPosition()+60_000):existing.end};
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(24*k);body.setPadding(pad,0,pad,0);ScrollView scroll=new ScrollView(this);scroll.addView(body);scroll.setLayoutParams(new ViewGroup.LayoutParams(-1,Math.round(500*k)));
        TextView status=TvUi.text(this,k,21,Ui.medium,Color.WHITE);body.addView(status);final TextView[] rows=new TextView[9];
        Runnable refresh=()->{editingSegment=new PlayerSessionPolicy.Segment(kinds[kind[0]],bounds[0],bounds[1]);status.setText(kinds[kind[0]]+" · "+PlayerChromePolicy.time(bounds[0])+" → "+PlayerChromePolicy.time(bounds[1]));updateChrome();};
        String[] labels={"Type","Start = current time","End = current time","Start earlier −0.5s","Start later +0.5s","End earlier −0.5s","End later +0.5s","Preview start","Preview end"};
        for(int i=0;i<labels.length;i++){final int action=i;rows[i]=TvUi.button(this,k,labels[i],v->{
            if(player==null)return;if(action==0){kind[0]=(kind[0]+1)%kinds.length;}else if(action==1)bounds[0]=player.getCurrentPosition();else if(action==2)bounds[1]=player.getCurrentPosition();else if(action==3)bounds[0]=Math.max(0,bounds[0]-500);else if(action==4)bounds[0]=Math.min(duration,bounds[0]+500);else if(action==5)bounds[1]=Math.max(0,bounds[1]-500);else if(action==6)bounds[1]=Math.min(duration,bounds[1]+500);else player.seekTo(action==7?bounds[0]:Math.max(bounds[0],bounds[1]-2000));refresh.run();});body.addView(rows[i]);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit skip segment").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Back",null).setNeutralButton(manualSegments.contains(existing)?"Delete":null,null).setOnDismissListener(d->{editingSegment=null;if(player!=null&&dialogPlayback.shouldResume(resumeToken))player.play();updateChrome();video.requestFocus();}).create();showBottom(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{PlayerSessionPolicy.Segment edited=new PlayerSessionPolicy.Segment(kinds[kind[0]],bounds[0],bounds[1]);if(!PlayerSessionPolicy.valid(edited,duration)){status.setText("Invalid bounds. Intro/recap must finish in the first 60%; credits/preview start in the last half. Minimum 5 seconds.");return;}
            List<PlayerSessionPolicy.Segment> changed=new ArrayList<>(manualSegments);changed.remove(existing);changed.add(edited);if(changed.size()>16){status.setText("Maximum 16 private segments per video.");return;}if(!PlayerSegmentStore.save(this,request.owner,request.videoId,duration,changed)){status.setText("Couldn't save. Please try again.");return;}manualSegments=changed;rebuildSegments();queueCloudSegments(duration);dialog.dismiss();});
        if(manualSegments.contains(existing))dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{List<PlayerSessionPolicy.Segment> changed=new ArrayList<>(manualSegments);changed.remove(existing);if(PlayerSegmentStore.save(this,request.owner,request.videoId,duration,changed)){manualSegments=changed;cloudSegments.removeIf(s->s.key().equals(existing.key()));queueCloudSegments(duration);dialog.dismiss();}});
        refresh.run();rows[0].requestFocus();
    }
    private boolean softwareDecoderAvailable(){if(player==null||player.getVideoFormat()==null)return false;try{for(androidx.media3.exoplayer.mediacodec.MediaCodecInfo codec:androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(player.getVideoFormat().sampleMimeType,false,false))if(codec.softwareOnly&&codec.isFormatSupported(this,player.getVideoFormat()))return true;}catch(Exception unavailable){}return false;}
    private void decoderMenu(){if(!softwareDecoderAvailable())return;final Request expected=request;final long menuTicket=transitions.capture(expected);showBottom(new AlertDialog.Builder(this).setTitle("Decoder preference").setSingleChoiceItems(new String[]{"Auto · hardware preferred","Prefer software · may be slower"},PlayerUiPreferences.enabled(this,"software_decoder",false)?1:0,(d,which)->{long action=transitions.begin(expected,menuTicket);if(action<0||request!=expected||player==null)return;PlayerUiPreferences.setEnabled(this,"software_decoder",which==1);d.dismiss();captureTrackLanguages();long at=player.getCurrentPosition();boolean play=player.getPlayWhenReady();release();position=at;wantsPlay=play;percentPending=false;failed=false;startPlayer();}).setNegativeButton("Back",null).create());}
    private boolean audioOutputAvailable(){if(!tv.noiro.player.audio.PlayerAudioOutput.devices(this).isEmpty())return true;android.media.MediaRouter router=(android.media.MediaRouter)getSystemService(MEDIA_ROUTER_SERVICE);return router!=null&&router.getRouteCount()>1||new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).resolveActivity(getPackageManager())!=null;}
    private void audioOutputMenu(){
        if(player==null)return;
        java.util.List<android.media.AudioDeviceInfo> devices=tv.noiro.player.audio.PlayerAudioOutput.devices(this);
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();labels.add("System default");
        int selected=0;for(int i=0;i<devices.size();i++){labels.add(tv.noiro.player.audio.PlayerAudioOutput.label(devices.get(i)));if(devices.get(i).getId()==preferredAudioDeviceId)selected=i+1;}
        showBottom(new AlertDialog.Builder(this).setTitle("Preferred audio output")
            .setSingleChoiceItems(labels.toArray(new String[0]),selected,(dialog,which)->{if(player==null)return;android.media.AudioDeviceInfo device=which==0?null:devices.get(which-1);player.setPreferredAudioDevice(device);preferredAudioDeviceId=device==null?0:device.getId();dialog.dismiss();})
            .setNeutralButton("Android routes",(dialog,which)->androidAudioRouteMenu()).setPositiveButton("Done",null).create());
    }
    private void androidAudioRouteMenu(){android.media.MediaRouter router=(android.media.MediaRouter)getSystemService(MEDIA_ROUTER_SERVICE);if(router!=null&&router.getRouteCount()>1){android.app.MediaRouteButton routes=new android.app.MediaRouteButton(this);routes.setRouteTypes(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO|android.media.MediaRouter.ROUTE_TYPE_LIVE_VIDEO);routes.setContentDescription("Select Android audio output");showBottom(new AlertDialog.Builder(this).setTitle("Android audio output").setView(routes).setPositiveButton("Done",null).create());routes.requestFocus();routes.performClick();}else{Intent settings=new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS);if(settings.resolveActivity(getPackageManager())!=null)startActivity(settings);}}
    private void toggleMute(){if(player==null)return;int current=Math.round(player.getVolume()*100);if(current>0){PlayerUiPreferences.setInteger(this,"previous_volume",current);PlayerUiPreferences.setInteger(this,"volume",0);player.setVolume(0);}else{int restore=Math.max(1,Math.min(100,PlayerUiPreferences.integer(this,"previous_volume",100)));PlayerUiPreferences.setInteger(this,"volume",restore);player.setVolume(restore/100f);}}
    private void volumeMenu(){if(player==null)return;LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);TextView value=TvUi.text(this,k,22,Ui.medium,Color.WHITE);body.addView(value);android.widget.SeekBar volume=new android.widget.SeekBar(this);volume.setMax(100);volume.setProgress(Math.round(player.getVolume()*100));body.addView(volume);value.setText("Volume · "+volume.getProgress()+"%");volume.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar bar){}public void onStopTrackingTouch(android.widget.SeekBar bar){}public void onProgressChanged(android.widget.SeekBar bar,int amount,boolean user){value.setText("Volume · "+amount+"%");if(user&&player!=null){player.setVolume(amount/100f);PlayerUiPreferences.setInteger(NoiroPlayerActivity.this,"volume",amount);}}});showBottom(new AlertDialog.Builder(this).setTitle("Player volume").setView(body).setNeutralButton(player.getVolume()==0?"Unmute":"Mute",(d,w)->toggleMute()).setPositiveButton("Done",null).create());volume.requestFocus();}
    private void seekStepMenu(){int[] values={5,10,15,30,60};String[] labels={"5 seconds","10 seconds","15 seconds","30 seconds","60 seconds"};showBottom(new AlertDialog.Builder(this).setTitle("Skip interval · next player open").setSingleChoiceItems(labels,java.util.Arrays.binarySearch(values,PlayerSessionPolicy.seekStep(PlayerUiPreferences.integer(this,"seek_step",10))),(d,which)->{PlayerUiPreferences.setInteger(this,"seek_step",values[which]);queueCloudPreference("seekStep",values[which]);d.dismiss();}).setNegativeButton("Back",null).create());}
    private void timelineStyleMenu(){showBottom(new AlertDialog.Builder(this).setTitle("Timeline appearance").setSingleChoiceItems(PlayerTimelineView.STYLES,PlayerUiPreferences.integer(this,"timeline_style",0),(d,which)->{PlayerUiPreferences.setInteger(this,"timeline_style",which);queueCloudPreference("timelineStyle",PlayerCloudState.STYLES[which]);updateChrome();d.dismiss();}).setNegativeButton("Back",null).create());}
    private String subtitleTimingKey(){return "subtitle_delay_"+PlayerSegmentStore.key(request.owner,request.videoId+"\n"+request.source.addon+"\n"+request.source.filename+"\n"+request.source.videoHash);}
    private void subtitleExtras(){
        if(player==null || subtitleSyncDialog!=null)return;
        syncEditing=true; video.hideController();
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(18*k); body.setPadding(pad,0,pad,pad);
        TextView help=TvUi.text(this,k,19,Ui.regular,TvUi.DIM);
        help.setText("Left / Right adjusts sync. OK changes the step.\nVideo and subtitles stay visible while you adjust."); body.addView(help);
        subtitleSyncHint=TvUi.text(this,k,18,Ui.regular,TvUi.DIM);body.addView(subtitleSyncHint);
        long[] step={100};
        TextView offset=TvUi.button(this,k,"",v->{});
        offset.setContentDescription("Subtitle sync. Left: earlier. Right: later. OK: change step.");
        TextView scale=TvUi.button(this,k,"",v->{});
        TextView font=TvUi.button(this,k,"",v->{});
        TextView playing=TvUi.button(this,k,"",v->{if(player!=null){if(player.getPlayWhenReady())player.pause();else player.play();}});
        Runnable refresh=()->{
            offset.setText(String.format(java.util.Locale.US,"Sync  %+.1f s   ·   Step %.1f s",subtitleDelayMs/1000f,step[0]/1000f));
            scale.setText("Size  "+Math.round(subtitlePreferences.fineScale*100)+"%   ·   Left / Right");
            font.setText("Font  ·  "+(subtitlePreferences.font==0?"Modern":"Classic"));
            playing.setText(player!=null&&player.getPlayWhenReady()?"Pause preview":"Play preview");
            applyCaptionAppearance();renderCaptions();
        };
        java.util.function.LongConsumer adjust=delta->{
            subtitleDelayMs=PlayerSessionPolicy.offset(subtitleDelayMs+delta);
            PlayerUiPreferences.setInteger(this,subtitleTimingKey(),(int)subtitleDelayMs);
            if(player!=null){
                if(selectedSubtitle!=null)addonCaption=TimedSubtitles.at(externalCues,PlayerSessionPolicy.subtitlePosition(player.getCurrentPosition(),subtitleDelayMs));
                else player.seekTo(player.getCurrentPosition());
            }
            refresh.run();
        };
        offset.setOnClickListener(v->{step[0]=step[0]==100?500:100;refresh.run();});
        offset.setOnKeyListener((v,key,event)->{
            if(key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN)adjust.accept(key==KeyEvent.KEYCODE_DPAD_LEFT?-step[0]:step[0]);
            return true;
        });
        scale.setOnKeyListener((v,key,event)->{
            if(key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN){subtitlePreferences.fineScale=PlayerSessionPolicy.scale(subtitlePreferences.fineScale+(key==KeyEvent.KEYCODE_DPAD_LEFT?-.1f:.1f));subtitlePreferences.save(this);queueCloudPreference("subtitleScale",Math.round(subtitlePreferences.fineScale*10)/10.0);refresh.run();}
            return true;
        });
        font.setOnClickListener(v->{subtitlePreferences.font=1-subtitlePreferences.font;subtitlePreferences.save(this);queueCloudPreference("subtitleFont",subtitlePreferences.font==0?"modern":"classic");refresh.run();});
        playing.setOnClickListener(v->{if(player!=null){if(player.getPlayWhenReady())player.pause();else player.play();refresh.run();}});
        TextView reset=TvUi.button(this,k,"Reset sync",v->adjust.accept(-subtitleDelayMs));
        for(TextView row:new TextView[]{offset,scale,font,playing,reset}){
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,23*k);
            row.setPadding(pad,Math.round(12*k),pad,Math.round(12*k));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=Math.round(6*k);body.addView(row,lp);
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Subtitle sync").setView(body).setPositiveButton("Done",null)
                .setOnDismissListener(d->{subtitleSyncDialog=null;subtitleSyncHint=null;syncEditing=false;applyCaptionAppearance();renderCaptions();video.requestFocus();}).create();
        subtitleSyncDialog=dialog;
        dialog.setOnKeyListener((d,key,event)->{
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE){
                if(event.getAction()==KeyEvent.ACTION_UP&&player!=null){if(key==KeyEvent.KEYCODE_MEDIA_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE&&player.getPlayWhenReady())player.pause();else player.play();refresh.run();}return true;
            }
            return false;
        });
        showBottom(dialog);
        android.view.Window window=dialog.getWindow();
        if(window!=null){window.setGravity(Gravity.TOP|Gravity.RIGHT);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(0);window.setLayout(Math.round(520*k),-2);WindowManager.LayoutParams at=window.getAttributes();at.x=Math.round(24*k);at.y=Math.round(24*k);window.setAttributes(at);}
        video.hideController();refresh.run();offset.requestFocus();
    }
    private org.json.JSONObject lastCloudMetadata;private long cloudMetadataAppliedDuration=-1;
    private PlayerCloudState cloud;private boolean applyingCloud,skipPolicyLoaded;private final java.util.Map<String,Boolean> allowedSkipKinds=new java.util.HashMap<>();private List<PlayerSessionPolicy.Segment> cloudSegments=new ArrayList<>();private long manualDurationLoaded=-1;
    private void cloudConflictMenu(){
        if(cloud==null)return;boolean marker=cloud.status().startsWith("Marker");
        showBottom(new AlertDialog.Builder(this).setTitle("Noiro.tv sync conflict").setMessage(marker?"These segments changed on another device. Your edits remain saved on this TV. Choose which version to keep.":"These settings changed on another device. Your edits remain saved on this TV. Choose which version to keep.")
            .setPositiveButton("Keep this TV",(d,w)->{if(cloud!=null){if(marker)cloud.resolveSegments(true);else cloud.resolvePreferences(true);}})
            .setNegativeButton("Use Noiro.tv",(d,w)->{if(cloud!=null){if(marker){manualSegments=new ArrayList<>();if(player!=null)PlayerSegmentStore.save(this,request.owner,request.videoId,player.getDuration(),manualSegments);cloud.resolveSegments(false);}else cloud.resolvePreferences(false);}}).setNeutralButton("Back",null).create());
    }
    private void queueCloudPreference(String name,Object value){if(cloud!=null&&!applyingCloud)cloud.preference(name,value);}
    private void queueCloudSegments(long duration){if(cloud!=null)cloud.segments(duration,new ArrayList<>(manualSegments));}
    private void applyCloudState(org.json.JSONObject state){
        if(player==null||request==null)return;applyingCloud=true;
        try{
            org.json.JSONObject preferences=state.optJSONObject("preferences");
            if(state.optLong("prefsRevision")>0&&preferences!=null){
                PlayerUiPreferences.noiro(this,"noiro".equals(preferences.optString("appearance","classic")));PlayerUiPreferences.speed(this,(float)preferences.optDouble("speed",1));String aspect=preferences.optString("aspect");PlayerUiPreferences.aspect(this,"fill".equals(aspect)?1:"stretch".equals(aspect)?2:0);
                PlayerUiPreferences.setInteger(this,"seek_step",PlayerSessionPolicy.seekStep(preferences.optInt("seekStep",10)));PlayerUiPreferences.setEnabled(this,"autoplay",preferences.optBoolean("autoplay",true));PlayerUiPreferences.setEnabled(this,"auto_skip",preferences.optBoolean("autoSkip",false));
                int style=java.util.Arrays.asList(PlayerCloudState.STYLES).indexOf(preferences.optString("timelineStyle","classic"));PlayerUiPreferences.setInteger(this,"timeline_style",Math.max(0,style));subtitlePreferences.font="classic".equals(preferences.optString("subtitleFont"))?1:0;subtitlePreferences.fineScale=PlayerSessionPolicy.scale((float)preferences.optDouble("subtitleScale",1));subtitlePreferences.save(this);
                player.setPlaybackSpeed(PlayerUiPreferences.speed(this));applyAspect();applyCaptionAppearance();renderCaptions();
            }
            org.json.JSONObject skip=state.optJSONObject("skipPreferences");if(skip!=null){String[] server={"skipIntro","skipRecap","skipOutro","skipPostCredits"},local={"intro","recap","credits","preview"};for(int i=0;i<local.length;i++)allowedSkipKinds.put(local[i],skip.optBoolean(server[i],true));skipPolicyLoaded=true;}
            applyWebsiteEpisodes(state);if(request.videoId.equals(state.optString("videoId"))){lastCloudMetadata=state;cloudMetadataAppliedDuration=-1;applyDeferredCloudMetadata();}
            updateChrome();
        }finally{applyingCloud=false;}
    }
    private void applyDeferredCloudMetadata(){
        if(player==null||lastCloudMetadata==null||!request.videoId.equals(lastCloudMetadata.optString("videoId")))return;
        long duration=player.getDuration();if(duration<=0||cloudMetadataAppliedDuration==duration)return;cloudMetadataAppliedDuration=duration;
        if(lastCloudMetadata.optBoolean("privateMarkersProjected")){
            manualSegments=Math.abs(duration-lastCloudMetadata.optLong("durationMs"))<=2000?parseSegments(lastCloudMetadata.optJSONArray("segments"),duration):new ArrayList<>();
            cloudSegments=new ArrayList<>();manualDurationLoaded=duration;
            // This is an offline fallback cache, never another overlay above the website.
            // Unsynced, deleted and conflicted local copies remain in the durable outbox.
            PlayerSegmentStore.save(this,request.owner,request.videoId,duration,manualSegments);
        }
        if(lastCloudMetadata.has("publicSegments"))publicSegments=parseSegments(lastCloudMetadata.optJSONArray("publicSegments"),duration);
    }
    private List<PlayerSessionPolicy.Segment> parseSegments(org.json.JSONArray values,long duration){List<PlayerSessionPolicy.Segment> out=new ArrayList<>();for(int i=0;values!=null&&i<Math.min(16,values.length());i++){org.json.JSONObject value=values.optJSONObject(i);if(value==null)continue;PlayerSessionPolicy.Segment s=new PlayerSessionPolicy.Segment(value.optString("kind"),value.optLong("startMs",-1),value.optLong("endMs",-1));if(PlayerSessionPolicy.valid(s,duration))out.add(s);}return out;}
    private void playerAppearance() {
        showBottom(new AlertDialog.Builder(this).setTitle("Player appearance")
                .setSingleChoiceItems(new String[]{"Classic", "Noiro · preview"}, PlayerUiPreferences.noiro(this) ? 1 : 0, (d, which) -> {
                    PlayerUiPreferences.noiro(this, which == 1);queueCloudPreference("appearance",which==1?"noiro":"classic");d.dismiss();
                    Toast.makeText(this, "Saved. Applies the next time the player opens.", Toast.LENGTH_LONG).show();
                }).setNegativeButton("Back", null).create());
    }
    private void playerSettings() {
        if(player==null)return;List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add("Player appearance · "+(PlayerUiPreferences.noiro(this)?"Noiro · preview":"Classic"));actions.add(this::playerAppearance);
        labels.add("Playback speed");actions.add(this::playbackSpeed);labels.add("Aspect ratio");actions.add(this::aspectRatio);labels.add("Subtitle settings");actions.add(this::subtitleSettings);labels.add("Subtitle sync, font & scale");actions.add(this::subtitleExtras);labels.add("Player volume");actions.add(this::volumeMenu);if(audioOutputAvailable()){labels.add("Audio output");actions.add(this::audioOutputMenu);}if(softwareDecoderAvailable()){labels.add("Decoder preference");actions.add(this::decoderMenu);}labels.add("Sleep timer");actions.add(this::sleepTimer);labels.add("Skip interval");actions.add(this::seekStepMenu);
        if(noiroChrome){labels.add("Timeline appearance");actions.add(this::timelineStyleMenu);}
        labels.add("Autoplay next episode · "+(PlayerUiPreferences.enabled(this,"autoplay",true)?"On":"Off"));actions.add(()->{boolean enabled=!PlayerUiPreferences.enabled(this,"autoplay",true);PlayerUiPreferences.setEnabled(this,"autoplay",enabled);if(!enabled)pendingEpisodeId="";queueCloudPreference("autoplay",enabled);});
        labels.add("Auto-skip allowed segments · "+(PlayerUiPreferences.enabled(this,"auto_skip",false)?"On":"Off"));actions.add(()->{boolean enabled=!PlayerUiPreferences.enabled(this,"auto_skip",false);PlayerUiPreferences.setEnabled(this,"auto_skip",enabled);queueCloudPreference("autoSkip",enabled);});
        if(!player.isCurrentMediaItemLive()&&player.getDuration()>0){labels.add("Edit skip segments");actions.add(this::segmentMenu);labels.add("Restart video");actions.add(this::restartVideo);}
        if(request.sources.size()>1){labels.add("Sources");actions.add(this::sourcesMenu);}if(qualitySources().size()>1||adaptiveQualityCount()>1){labels.add("Quality");actions.add(this::qualityMenu);}
        if(!player.isCurrentMediaItemLive()&&episodesAvailable()){labels.add("Episodes");actions.add(this::episodesMenu);}if(!player.isCurrentMediaItemLive()&&chapters.size()>1){labels.add("Chapters");actions.add(this::chaptersMenu);}if(rendered){labels.add("Grab frame");actions.add(this::grabFrame);}
        java.io.File[] saved=grabFolder(request.owner).listFiles();if(saved!=null&&saved.length>0){labels.add("Saved frames");actions.add(this::savedFrames);}
        labels.add("Playback information");actions.add(this::playbackInfo);
        labels.add("Open title in Stremio");actions.add(this::stremio);labels.add("Share title QR");actions.add(this::titleQr);
        if(cloud!=null&&cloud.status().contains("conflict")){labels.add("Review Noiro.tv sync conflict");actions.add(this::cloudConflictMenu);}
        if(cloud!=null){labels.add("Save player settings to Noiro.tv · "+cloud.status());actions.add(()->{try{org.json.JSONObject prefs=PlayerCloudState.snapshot(this);for(java.util.Iterator<String> keys=prefs.keys();keys.hasNext();){String key=keys.next();cloud.preference(key,prefs.get(key));}cloud.refresh();Toast.makeText(this,"Saved locally. Account sync will retry in the background.",Toast.LENGTH_LONG).show();}catch(Exception unavailable){ }});if(!manualSegments.isEmpty()){labels.add("Save my segments to Noiro.tv");actions.add(()->queueCloudSegments(player.getDuration()));}}
        showBottom(new AlertDialog.Builder(this).setTitle("Player settings").setItems(labels.toArray(new String[0]),(d,which)->actions.get(which).run()).setNegativeButton("Back",null).create());
    }
    private void playbackSpeed() {
        if (player == null) return;
        String[] labels = new String[PlayerChromePolicy.SPEEDS.length]; int current = 2;
        for (int i = 0; i < labels.length; i++) {
            float speed = PlayerChromePolicy.SPEEDS[i]; labels[i] = String.format(java.util.Locale.US, "%s×", speed);
            if (Math.abs(player.getPlaybackParameters().speed - speed) < .01f) current = i;
        }
        showBottom(new AlertDialog.Builder(this).setTitle("Playback speed")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    if (player == null) return;
                    float speed = PlayerChromePolicy.SPEEDS[which]; player.setPlaybackSpeed(speed); PlayerUiPreferences.speed(this, speed);
                    updateChrome(); d.dismiss(); video.requestFocus();
                }).setNegativeButton("Back", null).create());
    }
    private void applyAspect() {
        int mode = PlayerUiPreferences.aspect(this);
        video.setResizeMode(mode == 1 ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : mode == 2 ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }
    private void aspectRatio() {
        showBottom(new AlertDialog.Builder(this).setTitle("Aspect ratio")
                .setSingleChoiceItems(PlayerChromePolicy.ASPECTS, PlayerUiPreferences.aspect(this), (d, which) -> {
                    PlayerUiPreferences.aspect(this, which);queueCloudPreference("aspect",new String[]{"fit","fill","stretch"}[which]);applyAspect(); d.dismiss(); video.requestFocus();
                }).setNegativeButton("Back", null).create());
    }
    private void restartVideo() {
        if (player == null || !player.isCurrentMediaItemSeekable()) {
            Toast.makeText(this, "This stream cannot seek to the beginning.", Toast.LENGTH_LONG).show(); return;
        }
        showBottom(new AlertDialog.Builder(this).setTitle("Restart video?")
                .setMessage("Start this video again from the beginning?")
                .setPositiveButton("Restart", (d, which) -> { if (player != null) { player.seekTo(0); player.play(); video.requestFocus(); } })
                .setNegativeButton("Back", null).create());
    }
    private void sleepTimer() {
        int[] minutes={0,5,15,30,45,60,90,120};List<String> labels=new ArrayList<>();int selected=0;
        for(int i=0;i<minutes.length;i++){labels.add(minutes[i]==0?"Off":minutes[i]+" minutes");if(!sleepAtEpisodeEnd&&sleepMinutes==minutes[i])selected=i;}
        boolean endAvailable=adjacentEpisode(1)!=null&&player!=null&&!player.isCurrentMediaItemLive();if(endAvailable){labels.add("End of episode");if(sleepAtEpisodeEnd)selected=labels.size()-1;}
        String remaining=sleepDeadline>0?" · "+PlayerChromePolicy.time(Math.max(0,sleepDeadline-SystemClock.elapsedRealtime()))+" left":"";
        showBottom(new AlertDialog.Builder(this).setTitle("Sleep timer"+remaining).setSingleChoiceItems(labels.toArray(new String[0]),selected,(d,which)->{
            sleepAtEpisodeEnd=which==minutes.length;sleepMinutes=sleepAtEpisodeEnd?0:minutes[which];sleepDeadline=PlayerChromePolicy.sleepDeadline(SystemClock.elapsedRealtime(),sleepMinutes);if(sleepAtEpisodeEnd)pendingEpisodeId="";
            updateChrome();d.dismiss();video.requestFocus();Toast.makeText(this,sleepAtEpisodeEnd?"Playback will stop after this episode.":sleepMinutes==0?"Sleep timer off.":"Playback will pause in "+sleepMinutes+" minutes. Leaving the player cancels this timer.",Toast.LENGTH_LONG).show();
        }).setNegativeButton("Back",null).create());
    }
    private static String formatInfo(Format f, boolean audio) {
        if (f == null) return "Not available yet";
        StringBuilder value = new StringBuilder(PlayerChromePolicy.mime(f.sampleMimeType));
        if (audio) {
            if (f.channelCount > 0) value.append(" · ").append(f.channelCount).append(" channels");
            if (f.sampleRate > 0) value.append(" · ").append(f.sampleRate).append(" Hz");
            String language = PlayerChromePolicy.language(f.language);
            if (!language.isEmpty()) value.append(" · ").append(language);
        } else {
            if (f.width > 0 && f.height > 0) value.append(" · ").append(f.width).append('×').append(f.height);
            if (f.frameRate > 0) value.append(" · ").append(String.format(java.util.Locale.US, "%.2f fps", f.frameRate));
        }
        if (f.bitrate > 0) value.append(" · ").append(String.format(java.util.Locale.US, "%.1f Mb/s", f.bitrate / 1_000_000f));
        return value.toString();
    }
    private void playbackInfo() {
        if (player == null) return;
        String text = "Video\n" + formatInfo(player.getVideoFormat(), false)+ (videoDecoderName.isEmpty()?"":"\nDecoder · "+videoDecoderName) + "\n\nAudio\n" + formatInfo(player.getAudioFormat(), true)
                + "\n\nPlayback speed\n" + player.getPlaybackParameters().speed + "×\n\nBuffered ahead\n"
                + PlayerChromePolicy.time(Math.max(0, player.getBufferedPosition() - player.getCurrentPosition()));
        // Only technical Format fields are shown. Never display source URLs, headers or account identifiers.
        showBottom(new AlertDialog.Builder(this).setTitle("Playback information").setMessage(text).setPositiveButton("Done", null).create());
    }
    private void updateChrome() {
        if (!noiroChrome || player == null) return;
        Format vf = player.getVideoFormat(), af = player.getAudioFormat();
        String quality = vf != null && vf.height > 0 ? vf.height + "p" : "Loading video details";
        if(vf!=null&&vf.colorInfo!=null){if(vf.colorInfo.colorTransfer==C.COLOR_TRANSFER_ST2084)quality+=" · HDR10";else if(vf.colorInfo.colorTransfer==C.COLOR_TRANSFER_HLG)quality+=" · HLG";}
        String audio = af != null && !"Unknown format".equals(PlayerChromePolicy.mime(af.sampleMimeType))
                ? PlayerChromePolicy.mime(af.sampleMimeType).replace("audio/", "").toUpperCase(java.util.Locale.US) : "";
        ((TextView)video.findViewById(R.id.noiro_player_metadata)).setText(quality + (audio.isEmpty() ? "" : " · " + audio));
        long duration = player.getDuration(), current = player.getCurrentPosition();
        TextView ends = video.findViewById(R.id.noiro_player_ends);
        if (duration > 0) {
            long at = System.currentTimeMillis() + PlayerChromePolicy.wallRemaining(current, duration, player.getPlaybackParameters().speed);
            java.text.DateFormat clock = "24h".equals(Prefs.clockFormat()) ? new java.text.SimpleDateFormat("HH:mm", Ui.locale())
                    : "12h".equals(Prefs.clockFormat()) ? new java.text.SimpleDateFormat("h:mm a", Ui.locale()) : android.text.format.DateFormat.getTimeFormat(this);
            ends.setText(player.getPlayWhenReady() ? "Ends " + clock.format(new java.util.Date(at)) : "Paused");
        } else ends.setText(player.isCurrentMediaItemLive() ? "LIVE" : "");
        video.findViewById(R.id.noiro_player_restart).setEnabled(player.isCurrentMediaItemSeekable());
        TextView sleep = video.findViewById(R.id.noiro_player_sleep);
        sleep.setText(sleepAtEpisodeEnd?"Sleep · End":sleepDeadline > 0 ? "Sleep · " + ((Math.max(0, sleepDeadline - SystemClock.elapsedRealtime()) + 59_999) / 60_000) + "m" : "Sleep");
        boolean live=player.isCurrentMediaItemLive();
        int audioTracks=0;for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i))audioTracks++;video.findViewById(androidx.media3.ui.R.id.exo_audio_track).setVisibility(audioTracks>0?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_sources).setVisibility(request.sources.size()>1?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_quality).setVisibility(qualitySources().size()>1||adaptiveQualityCount()>1?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_episodes).setVisibility(!live&&episodesAvailable()?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_chapters).setVisibility(!live&&chapters.size()>1?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_grab).setVisibility(rendered?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_previous).setVisibility(!live&&adjacentEpisode(-1)!=null?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_next).setVisibility(!live&&adjacentEpisode(1)!=null?View.VISIBLE:View.GONE);
        video.findViewById(R.id.noiro_player_segments).setVisibility(!request.trailer()&&!live&&duration>0?View.VISIBLE:View.GONE);
        View timeline=video.findViewById(androidx.media3.ui.R.id.exo_progress);timeline.setVisibility(live?View.GONE:View.VISIBLE);timeline.setFocusable(!live);
        if(timeline instanceof PlayerTimelineView)((PlayerTimelineView)timeline).decorate(chapters,segments,editingSegment,PlayerUiPreferences.integer(this,"timeline_style",0),player.isPlaying());
    }
    private TextView addButton(String label, Runnable action) {
        TextView b = TvUi.button(this, k, label, v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2); lp.topMargin = 14;
        error.addView(b, lp); return b;
    }
    private void stremio() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/" + request.type + "/" + request.videoId.split(":")[0] + "/" + request.videoId)).setPackage("com.stremio.one")); finish();
        } catch (Exception missing) { fail("Stremio is not installed. Choose another stream."); }
    }
    private void tracks(int type) {
        if (type == C.TRACK_TYPE_TEXT) { subtitleMenu(); return; }
        trackChoices(type, java.util.Collections.emptyList());
    }
    private void trackChoices(int type, List<AddonSubtitles.Option> addons) {
        if (player == null) return;
        long resumeToken = dialogPlayback.capture(player.getPlayWhenReady()); player.pause();
        List<String> names = new ArrayList<>(); List<TrackSelectionOverride> choices = new ArrayList<>();
        names.add(type == C.TRACK_TYPE_TEXT ? "Subtitles off" : "Automatic"); choices.add(null);
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i)) {
                Format format = group.getTrackFormat(i);
                String name = format.label != null ? format.label : format.language != null ? format.language : "Track " + (i + 1);
                if (format.language != null && !format.language.equals("und")) {
                    String language = java.util.Locale.forLanguageTag(format.language).getDisplayLanguage(java.util.Locale.ENGLISH);
                    if (!language.isEmpty() && !name.equalsIgnoreCase(language)) name += " · " + language;
                }
                names.add((group.isTrackSelected(i) ? "✓ " : "") + name); choices.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
            }
        }
        List<AddonSubtitles.Option> addonSlots = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) addonSlots.add(null);
        List<AddonSubtitles.Option> sortedAddons = new ArrayList<>(addons);
        sortedAddons.sort(java.util.Comparator.comparingInt(option -> !option.aiEndpoint.isEmpty() ? 0 : option.label.startsWith("Noiro Subtitles ·") ? 1 : 2));
        int insert = 1;
        for (AddonSubtitles.Option option : sortedAddons) {
            boolean selected = selectedSubtitle != null && ((!option.aiEndpoint.isEmpty() && option.aiEndpoint.equals(selectedSubtitle.aiEndpoint))
                    || (!option.url.isEmpty() && option.url.equals(selectedSubtitle.url)));
            names.add(insert, (selected ? "✓ " : "") + option.label); choices.add(insert, null); addonSlots.add(insert, option); insert++;
        }
        boolean chooseTarget = type == C.TRACK_TYPE_TEXT && !request.trailer();
        if (chooseTarget) {
            String target = subtitlePreferences.translationTarget;
            names.add(1, "Translate with Noiro");
            choices.add(1, null);
            addonSlots.add(1, null);
            names.add(2, "Add-on translation language · " + (target.isEmpty() ? "Noiro.tv setting" : SubtitleSelection.name(target)));
            choices.add(2, null);
            addonSlots.add(2, null);
        }
        showBottom(new AlertDialog.Builder(this).setTitle(type == C.TRACK_TYPE_TEXT ? "Subtitles · embedded & add-ons" : "Audio")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (player == null) return;
                    if (chooseTarget && which == 1) { directSubtitleMenu(addons); return; }
                    if (chooseTarget && which == 2) { translationLanguage(); return; }
                    if (type == C.TRACK_TYPE_TEXT) {
                        stopSubtitleWorker(); selectedSubtitle = null; externalCues = java.util.Collections.emptyList(); addonCaption = ""; subtitleStatus.setVisibility(View.GONE);
                        manualSubtitleChoice=true;subtitlesOff=which==0;selectedSubtitleLanguage="";
                        selectedSubtitleLabel="";textTrackApplied=true;if(choices.get(which)!=null){Format chosen=choices.get(which).mediaTrackGroup.getFormat(choices.get(which).trackIndices.get(0));selectedSubtitleLanguage=PlayerChromePolicy.language(chosen.language);selectedSubtitleLabel=chosen.label==null?"":chosen.label;}
                        if (which == 0) embeddedCues = java.util.Collections.emptyList();
                        if (addonSlots.get(which) != null) { selectedSubtitleLanguage=PlayerChromePolicy.language(addonSlots.get(which).language);selectAddonSubtitle(addonSlots.get(which)); return; }
                    }
                    TrackSelectionParameters.Builder params = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && which == 0);
                    if (choices.get(which) != null) params.addOverride(choices.get(which));
                    if(type==C.TRACK_TYPE_AUDIO){manualAudioChoice=choices.get(which)!=null;selectedAudioLanguage="";selectedAudioLabel="";if(manualAudioChoice){Format chosen=choices.get(which).mediaTrackGroup.getFormat(choices.get(which).trackIndices.get(0));selectedAudioLanguage=PlayerChromePolicy.language(chosen.language);selectedAudioLabel=chosen.label==null?"":chosen.label;}audioTrackApplied=true;}
                    player.setTrackSelectionParameters(params.build());
                    renderCaptions();
                }).setOnDismissListener(d -> { if (player != null && dialogPlayback.shouldResume(resumeToken)) player.play(); video.requestFocus(); }).create());
    }
    private void directSubtitleMenu(List<AddonSubtitles.Option> addons) {
        if (fixtureMode() || request.trailer()) return;
        if (!NoiroAccount.ready()) {
            Toast.makeText(this, "AI translation uses optional Noiro cloud access. Existing subtitles remain available for free.", Toast.LENGTH_LONG).show();
            return;
        }
        cancelAutoSubtitles();
        autoSubtitleAttempted = true;
        final Request playback = request;
        final int run = ++subtitleMenuGeneration;
        AlertDialog pending = new AlertDialog.Builder(this).setMessage("Loading Noiro translation languages…")
                .setNegativeButton("Cancel", (dialog, which) -> subtitleMenuGeneration++).create();
        pending.setOnCancelListener(dialog -> subtitleMenuGeneration++);
        showBottom(pending);
        subtitleDiscovery = new Thread(() -> {
            try {
                List<String> options = DirectSubtitles.targets(playback.owner);
                main.post(() -> {
                    if (run != subtitleMenuGeneration || playback != request || player == null || isFinishing()) return;
                    pending.dismiss();
                    if (options.isEmpty()) return;
                    String[] labels = options.stream().map(SubtitleSelection::name).toArray(String[]::new);
                    showBottom(new AlertDialog.Builder(this).setTitle("Translate with Noiro")
                            .setSingleChoiceItems(labels, options.indexOf(subtitlePreferences.translationTarget), (dialog, which) -> {
                                dialog.dismiss();
                                String target = options.get(which);
                                subtitlePreferences.translationTarget = target;
                                subtitlePreferences.save(this);
                                translateDirectSubtitle(playback, addons, null, target);
                            }).setNegativeButton("Cancel", null).create());
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (run != subtitleMenuGeneration || playback != request || player == null || isFinishing()) return;
                    pending.dismiss();
                    subtitleStatus.setText(directSubtitleError(failure));
                    subtitleStatus.setVisibility(View.VISIBLE);
                });
            }
        }, "noiro-subtitle-languages");
        subtitleDiscovery.start();
    }

    private void chooseDirectSubtitle(Request playback, List<AddonSubtitles.Option> addons, String target, boolean unavailable) {
        List<AddonSubtitles.Option> sources = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (AddonSubtitles.Option option : addons) if (!option.url.isEmpty()) sources.add(option);
        sources.sort(java.util.Comparator.comparingInt(option -> {
            String language = DirectSubtitles.sourceLanguage(option.language);
            return "en".equals(language) ? 0 : target.equals(language) ? 1 : 2;
        }));
        for (AddonSubtitles.Option source : sources) labels.add(source.label);
        if (sources.isEmpty()) {
            subtitleStatus.setVisibility(View.VISIBLE);
            subtitleStatus.setText(unavailable ? "Could not read embedded subtitles. No add-on subtitle is available."
                    : "No text subtitle in the video or installed add-ons.");
            return;
        }
        subtitleStatus.setVisibility(View.GONE);
        showBottom(new AlertDialog.Builder(this).setTitle(unavailable
                        ? "Embedded subtitle unavailable · choose an add-on track" : "Choose an add-on subtitle")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (playback == request && player != null)
                        translateDirectSubtitle(playback, addons, sources.get(which), target);
                }).setNegativeButton("Cancel", null).create());
    }

    private static final class SubtitleProgress {
        List<TimedSubtitles.Cue> preview = java.util.Collections.emptyList();
        List<TimedSubtitles.Cue> translated = java.util.Collections.emptyList();
        boolean started, finished;
    }

    private void translateDirectSubtitle(Request playback, List<AddonSubtitles.Option> addons, AddonSubtitles.Option fallback, String target) {
        if (playback != request || player == null) return;
        stopSubtitleWorker();
        final int run = subtitleGeneration;
        final long durationMs = player.getDuration();
        final long positionMs = player.getCurrentPosition();
        SubtitleProgress display = new SubtitleProgress();
        subtitleStatus.setText(fallback == null ? "Reading embedded subtitles near playback…" : "Loading add-on subtitle…");
        subtitleStatus.setVisibility(View.VISIBLE);
        subtitleWorker = new Thread(() -> {
            java.util.concurrent.ExecutorService previews = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<List<TimedSubtitles.Cue>>> cache =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try {
                List<TimedSubtitles.Cue> source;
                String sourceLanguage;
                boolean fromCache = false;
                if (fallback == null) {
                    EmbeddedSubtitleReader.Result embedded = null;
                    boolean unavailable = false;
                    try {
                        embedded = EmbeddedSubtitleReader.read(playback.source, target, durationMs, positionMs,
                                (timing, count, kind) -> {
                                    java.util.concurrent.FutureTask<List<TimedSubtitles.Cue>> lookup =
                                            new java.util.concurrent.FutureTask<>(() -> {
                                                List<TimedSubtitles.Cue> saved = DirectSubtitles.cached(
                                                        playback.owner, playback.videoId, target, timing, count, kind);
                                                if (saved != null) main.post(() -> completeDirectSubtitles(run, target, display, saved, true));
                                                return saved;
                                            });
                                    cache.set(lookup);
                                    new Thread(lookup, "noiro-subtitle-cache").start();
                                    return lookup;
                                },
                                preview -> {
                                    previews.execute(() -> translateSubtitlePreview(playback, preview, target, run, display, cache.get()));
                                },
                                progress -> main.post(() -> {
                                    if (subtitleActive(run) && !display.started && !display.finished)
                                        subtitleStatus.setText("Reading embedded subtitles · " + progress + "%");
                                }));
                    } catch (java.io.IOException unreadable) {
                        if (Thread.currentThread().isInterrupted()) return;
                        unavailable = true;
                    }
                    List<TimedSubtitles.Cue> saved = embedded != null && embedded.translated ? embedded.cues : awaitSavedSubtitles(cache.get());
                    if (saved != null) {
                        source = saved;
                        sourceLanguage = target;
                        fromCache = true;
                    } else if (embedded != null) {
                        source = embedded.cues;
                        sourceLanguage = embedded.language;
                    } else {
                        findDirectSubtitleFallback(playback, addons, target, unavailable, run);
                        return;
                    }
                } else {
                    source = TimedSubtitles.parse(AddonSubtitles.text(fallback.url, fallback.language));
                    sourceLanguage = DirectSubtitles.sourceLanguage(fallback.language);
                }
                if (Thread.currentThread().isInterrupted()) return;
                List<TimedSubtitles.Cue> cues;
                if (fromCache) {
                    cues = source;
                } else if (target.equals(sourceLanguage)) {
                    cues = TimedSubtitles.parse(DirectSubtitles.srt(source));
                } else {
                    String srt = DirectSubtitles.srt(source);
                    main.post(() -> {
                        if (subtitleActive(run) && !display.started && !display.finished) subtitleStatus.setText("Sending subtitle to Noiro…");
                    });
                    cues = DirectSubtitles.translate(playback.owner, playback.videoId, srt, sourceLanguage, target,
                            progress -> main.post(() -> {
                                if (subtitleActive(run) && !display.started && !display.finished) subtitleStatus.setText(progress);
                            }), translated -> main.post(() -> showSubtitleProgress(run, target, display, translated, false)));
                }
                boolean reused = fromCache;
                main.post(() -> completeDirectSubtitles(run, target, display, cues, reused));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                main.post(() -> {
                    if (!subtitleActive(run) || display.finished) return;
                    display.finished = true;
                    subtitleStatus.setText(directSubtitleError(failure));
                    subtitleStatus.setVisibility(View.VISIBLE);
                });
            } finally {
                previews.shutdownNow();
                java.util.concurrent.Future<List<TimedSubtitles.Cue>> lookup = cache.get();
                if (lookup != null) lookup.cancel(true);
            }
        }, "noiro-subtitle-translation");
        subtitleWorker.start();
    }

    private static List<TimedSubtitles.Cue> awaitSavedSubtitles(java.util.concurrent.Future<List<TimedSubtitles.Cue>> lookup)
            throws InterruptedException {
        if (lookup == null) return null;
        try { return lookup.get(8, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                | java.util.concurrent.CancellationException unavailable) { return null; }
    }

    private void translateSubtitlePreview(Request playback, EmbeddedSubtitleReader.Result preview,
                                          String target, int run, SubtitleProgress display,
                                          java.util.concurrent.Future<List<TimedSubtitles.Cue>> lookup) {
        try {
            if (lookup != null && lookup.isDone() && awaitSavedSubtitles(lookup) != null) return;
            String srt = DirectSubtitles.srt(preview.cues);
            java.util.function.Consumer<List<TimedSubtitles.Cue>> publish =
                    cues -> main.post(() -> showSubtitleProgress(run, target, display, cues, true));
            if (target.equals(preview.language)) publish.accept(TimedSubtitles.parse(srt));
            else publish.accept(DirectSubtitles.translate(playback.owner, playback.videoId, srt, preview.language,
                    target, progress -> {}, publish));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            // The full track can still produce a translation.
        }
    }

    private void completeDirectSubtitles(int run, String target, SubtitleProgress display,
                                         List<TimedSubtitles.Cue> cues, boolean reused) {
        if (!subtitleActive(run) || display.finished) return;
        display.finished = true;
        applyDirectSubtitles(target, cues);
        subtitleStatus.setVisibility(View.VISIBLE);
        subtitleStatus.setText(reused ? "Saved Noiro subtitles ready" : "Noiro subtitles ready");
        main.postDelayed(() -> { if (subtitleActive(run)) subtitleStatus.setVisibility(View.GONE); }, 5000);
    }

    private void showSubtitleProgress(int run, String target, SubtitleProgress display,
                                      List<TimedSubtitles.Cue> cues, boolean preview) {
        if (!subtitleActive(run) || display.finished || cues.isEmpty()) return;
        if (preview) display.preview = TimedSubtitles.prefer(cues, display.preview);
        else display.translated = cues;
        List<TimedSubtitles.Cue> available = TimedSubtitles.prefer(display.translated, display.preview);
        if (!display.started) {
            long playback = PlayerSessionPolicy.subtitlePosition(player.getCurrentPosition(), subtitleDelayMs);
            if (available.stream().noneMatch(cue -> cue.end > playback && cue.start <= playback + 45_000)) return;
        }
        applyDirectSubtitles(target, available);
        if (display.started) return;
        display.started = true;
        subtitleStatus.setText("Noiro subtitles started");
        subtitleStatus.setVisibility(View.VISIBLE);
        main.postDelayed(() -> {
            if (subtitleActive(run) && !display.finished) subtitleStatus.setVisibility(View.GONE);
        }, 5_000);
    }

    private void findDirectSubtitleFallback(Request playback, List<AddonSubtitles.Option> addons,
                                            String target, boolean unavailable, int run) {
        List<AddonSubtitles.Option> found = addons;
        if (found == null) {
            main.post(() -> {
                if (!subtitleActive(run)) return;
                subtitleStatus.setText("Looking for subtitles in installed add-ons…");
                subtitleStatus.setVisibility(View.VISIBLE);
            });
            try {
                found = AddonSubtitles.discover(playback.owner, playback.type, playback.videoId, playback.source, target);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failed) {
                found = java.util.Collections.emptyList();
            }
        }
        List<AddonSubtitles.Option> options = found;
        main.post(() -> {
            if (subtitleActive(run)) chooseDirectSubtitle(playback, options, target, unavailable);
        });
    }

    private void applyDirectSubtitles(String target, List<TimedSubtitles.Cue> cues) {
        if (selectedSubtitle == null || !selectedSubtitle.url.isEmpty() || !target.equals(selectedSubtitleLanguage)) {
            selectedSubtitle = new AddonSubtitles.Option("Noiro AI", "", "", target);
            selectedSubtitleLanguage = target;
            selectedSubtitleLabel = "Noiro AI";
            manualSubtitleChoice = true;
            textTrackApplied = true;
            subtitlesOff = false;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        externalCues = cues;
        addonCaption = TimedSubtitles.at(cues, PlayerSessionPolicy.subtitlePosition(player.getCurrentPosition(), subtitleDelayMs));
        renderCaptions();
    }

    private static String directSubtitleError(Exception failure) {
        String code = failure instanceof AccountHttp.Failure ? ((AccountHttp.Failure) failure).code : "";
        switch (code) {
            case "authentication_required": case "account_link_required":
                return "Reconnect your Noiro account in Stremio & Noiro.";
            case "supporter_required": return "An active Noiro Supporter membership is required.";
            case "invalid_source_subtitle":
                return "The source subtitle could not be used.";
            case "subtitle_source_too_large": return "This subtitle is too large to translate.";
            case "umbrel_queue_full": case "rate_limited":
                return "Noiro subtitles is busy. Try again shortly.";
            case "translation_pending": return "The translation is still running. Select the same subtitle again to check it.";
            default: return "Noiro translation could not load. Reopen Subtitles to retry.";
        }
    }

    private void translationLanguage() {
        String[] names = new String[SubtitleSelection.NAMES.length + 1];
        names[0] = "Use Noiro.tv setting";
        System.arraycopy(SubtitleSelection.NAMES, 0, names, 1, SubtitleSelection.NAMES.length);
        int selected = java.util.Arrays.asList(SubtitleSelection.CODES).indexOf(subtitlePreferences.translationTarget) + 1;
        showBottom(new AlertDialog.Builder(this).setTitle("Translate to")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    subtitlePreferences.translationTarget = which == 0 ? "" : SubtitleSelection.CODES[which - 1];
                    subtitlePreferences.save(this);
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create());
    }
    private void enableAddonSubtitles() {
        View button = video.findViewById(R.id.noiro_subtitles);
        if (button != null) { button.setEnabled(true); button.setAlpha(1f); }
    }
    private void subtitleMenu() {
        if(fixtureMode() || request.trailer()){trackChoices(C.TRACK_TYPE_TEXT,java.util.Collections.emptyList());return;}
        cancelAutoSubtitles(); autoSubtitleAttempted = true;
        final int run = ++subtitleMenuGeneration;
        AlertDialog pending = new AlertDialog.Builder(this).setMessage("Loading subtitle add-ons…")
                .setNegativeButton("Cancel", (d, w) -> subtitleMenuGeneration++).create();
        pending.setOnCancelListener(d -> subtitleMenuGeneration++); showBottom(pending);
        String target = subtitlePreferences.translationTarget;
        subtitleDiscovery = new Thread(() -> {
            try {
                List<AddonSubtitles.Option> options = AddonSubtitles.discover(request.owner, request.type, request.videoId, request.source, target);
                main.post(() -> { if (run != subtitleMenuGeneration || player == null || isFinishing()) return; pending.dismiss(); trackChoices(C.TRACK_TYPE_TEXT, options); });
            } catch (Exception unavailable) {
                main.post(() -> { if (run != subtitleMenuGeneration || player == null || isFinishing()) return; pending.dismiss();
                    android.widget.Toast.makeText(this, "Subtitle add-ons couldn't be reached. Showing embedded tracks.", android.widget.Toast.LENGTH_LONG).show();
                    trackChoices(C.TRACK_TYPE_TEXT, java.util.Collections.emptyList()); });
            }
        }, "subtitle-addon-discovery"); subtitleDiscovery.start();
    }
    private boolean subtitleActive(int run) {
        return run == subtitleGeneration && player != null && !isFinishing() && !isDestroyed()
                && ownerCurrent(request.owner);
    }
    private void selectAddonSubtitle(AddonSubtitles.Option option) {
        if(fixtureMode())return;
        stopSubtitleWorker(); selectedSubtitle = option; addonCaption = ""; externalCues = java.util.Collections.emptyList(); renderCaptions(); final int run = subtitleGeneration;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        subtitleStatus.setText("Loading " + option.label); subtitleStatus.setVisibility(View.VISIBLE);
        subtitleWorker = new Thread(() -> {
            final String[] stage = {"Contacting the subtitle add-on"};
            try {
                String url = AddonSubtitles.start(option);
                boolean job = AddonSubtitles.noiroJob(url), resumedJob = false;
                while (subtitleActive(run)) {
                    String state = "complete", info = "Subtitles ready";
                    String download = url;
                    if (job) {
                        stage[0] = "Reading Noiro AI translation progress";
                        org.json.JSONObject status = AccountHttp.call(url.replace("complete.vtt", "status.json"), null, "");
                        state = status.optString("state");
                        if (state.equals("paused") && !resumedJob && !option.aiEndpoint.isEmpty()) {
                            option.jobUrl = null; url = AddonSubtitles.start(option); resumedJob = true; continue;
                        }
                        info = "Noiro AI · " + status.optInt("translated") + "/" + status.optInt("total");
                        if (state.equals("waiting") || state.equals("failed")) info += status.optString("errorCode").equals("provider_http_429") ? " · provider quota reached" : state.equals("failed") ? " · translation failed" : " · waiting for translation";
                        download = url.replace("complete.vtt", state.equals("complete") ? "complete.vtt" : "partial.vtt");
                    }
                    stage[0] = "Downloading subtitle text";
                    List<TimedSubtitles.Cue> cues = TimedSubtitles.parse(AddonSubtitles.text(download, option.language));
                    final String label = info; final boolean done = state.equals("complete");
                    main.post(() -> { if (!subtitleActive(run)) return; externalCues = cues; subtitleStatus.setText(label);
                        if (done) main.postDelayed(() -> { if (subtitleActive(run)) subtitleStatus.setVisibility(View.GONE); }, 5000); });
                    if (done || state.equals("failed")) break;
                    Thread.sleep(5000);
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (Exception unavailable) {
                main.post(() -> { if (subtitleActive(run)) { String reason = unavailable instanceof AccountHttp.Failure ? ((AccountHttp.Failure)unavailable).code : unavailable.getClass().getSimpleName();
                    subtitleStatus.setTag(reason);
                    subtitleStatus.setText(reason.equals("provider_login_required") ? "Noiro Subtitles needs OpenSubtitles sign-in refreshed at noiro.tv. Then retry AI translation here."
                            : reason.equals("provider_rate_limited") ? "Noiro Subtitles has reached the subtitle provider limit. Try again later or choose an existing subtitle."
                            : stage[0] + " failed. Reopen CC/Subtitles to retry; check Noiro addon settings at noiro.tv."); subtitleStatus.setVisibility(View.VISIBLE); } });
            }
        }, "addon-subtitles"); subtitleWorker.start();
    }
    private void stopSubtitleWorker() {
        subtitleGeneration++; if (subtitleWorker != null) subtitleWorker.interrupt();
    }
    private void renderCaptions() {
        List<Cue> current = selectedSubtitle == null ? embeddedCues : addonCaption.isEmpty()
                ? java.util.Collections.emptyList() : java.util.Collections.singletonList(new Cue.Builder().setText(addonCaption).build());
        List<Cue> styled = SubtitleAppearance.cues(current);
        if (positionEditing && styled.isEmpty()) styled = java.util.Collections.singletonList(
                new Cue.Builder().setText("Subtitle position · ČĆŽŠĐ čćžšđ").build());
        captions.setCues(styled); captions.setVisibility(styled.isEmpty() ? View.GONE : View.VISIBLE);
        if(subtitleSyncHint!=null)subtitleSyncHint.setText(styled.isEmpty()?"Waiting for the next subtitle line…":"Live subtitle preview");
    }
    private void applyCaptionAppearance() {
        SubtitleAppearance.apply(captions, subtitlePreferences, k);
        View bar = video.findViewById(androidx.media3.ui.R.id.exo_bottom_bar);
        View progress = video.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (!positionEditing && !syncEditing && video.isControllerFullyVisible() && bar != null && captions.getHeight() > 0) {
            float reserved = (bar.getHeight() + progress.getHeight() + 24 * k) / captions.getHeight();
            captions.setBottomPaddingFraction(Math.max(subtitlePreferences.bottomFraction(), reserved));
        }
    }
    private void showBottom(AlertDialog dialog) {
        final Request expected=request;final long modalTicket=transitions.capture(expected);
        dialog.show();openPanels++;video.setControllerShowTimeoutMs(0);
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){}public void onViewDetachedFromWindow(View view){openPanels=Math.max(0,openPanels-1);if(openPanels==0){video.setControllerShowTimeoutMs(noiroChrome?4000:5000);if(request==expected&&transitions.mayAdvance(expected,modalTicket)&&player!=null&&player.getPlaybackState()==Player.STATE_ENDED&&mayAutoAdvance()){PlayerSessionPolicy.Episode next=adjacentEpisode(1);if(next!=null)playEpisode(next);}}}});
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        window.setGravity(Gravity.BOTTOM); window.setDimAmount(0.25f);
        if (dialog.getListView() != null) {
            float rowHeight = 56 * getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams list = dialog.getListView().getLayoutParams();
            list.height = Math.round(Math.min(430 * k, dialog.getListView().getCount() * rowHeight));
            dialog.getListView().setLayoutParams(list);
            View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            View cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (save != null && save.getVisibility() == View.VISIBLE) {
                dialog.getListView().setNextFocusRightId(save.getId());
                save.setNextFocusUpId(dialog.getListView().getId());
            }
            if (cancel != null && cancel.getVisibility() == View.VISIBLE) {
                dialog.getListView().setNextFocusLeftId(cancel.getId());
                cancel.setNextFocusUpId(dialog.getListView().getId());
            }
        }
        window.setLayout(Math.round(1300 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
        NoiroMenus.apply(dialog,k);
    }
    private void subtitleOptions() {
        if (player == null) return;
        boolean canTranslate = !fixtureMode() && !request.trailer();
        List<String> actions = new ArrayList<>();
        actions.add("Select subtitles");
        actions.add("Subtitle sync");
        actions.add("Subtitle settings");
        if (canTranslate) actions.add(1, "Translate with Noiro");
        showBottom(new AlertDialog.Builder(this).setTitle("Subtitles")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    switch (actions.get(which)) {
                        case "Select subtitles": subtitleMenu(); break;
                        case "Translate with Noiro": directSubtitleMenu(null); break;
                        case "Subtitle sync": subtitleExtras(); break;
                        default: subtitleSettings();
                    }
                }).create());
    }
    private void audioOptions() {
        if (player == null) return;
        String defaultLabel = "Default audio language · Premium";
        if (Prefs.defaultAudioLanguageAvailable()) defaultLabel += " · "
                + (Prefs.defaultAudioLanguage().isEmpty() ? "Automatic" : SubtitleSelection.name(Prefs.defaultAudioLanguage()));
        showBottom(new AlertDialog.Builder(this).setTitle("Audio")
                .setItems(new String[]{"Select audio track", defaultLabel, "Preferred audio output"}, (d, which) -> {
                    if (which == 0) tracks(C.TRACK_TYPE_AUDIO); else if(which==1) defaultAudioLanguage(); else audioOutputMenu();
                }).create());
    }
    private void premiumAudioLanguage() {
        showBottom(new AlertDialog.Builder(this).setTitle("Default audio language · Premium")
                .setMessage("Automatically use your saved audio language for new films and episodes with Noiro cloud. You can select any available audio track manually for free.")
                .setPositiveButton("About Noiro cloud", (dialog, which) -> startActivity(new Intent(this, OnboardingActivity.class)
                        .putExtra("manage", true).putExtra("cloud_info", true)))
                .setNegativeButton("Back", null).create());
    }
    private void defaultAudioLanguage() {
        if (!Prefs.defaultAudioLanguageAvailable()) { premiumAudioLanguage(); return; }
        if (player == null || request == null) return;
        final Request expected = request;
        final int run = featureGeneration;
        String[] labels = new String[SubtitleSelection.NAMES.length + 1]; labels[0] = "Automatic";
        System.arraycopy(SubtitleSelection.NAMES, 0, labels, 1, SubtitleSelection.NAMES.length);
        int selected = java.util.Arrays.asList(SubtitleSelection.CODES).indexOf(Prefs.defaultAudioLanguage()) + 1;
        showBottom(new AlertDialog.Builder(this).setTitle("Default audio language")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    if (!featureCurrent(run, expected)) { d.dismiss(); return; }
                    String language = which == 0 ? "" : SubtitleSelection.CODES[which - 1];
                    if (!Prefs.setDefaultAudioLanguage(language)) { d.dismiss(); premiumAudioLanguage(); return; }
                    manualAudioChoice=false;selectedAudioLanguage="";selectedAudioLabel="";
                    String effective = Prefs.defaultAudioLanguage();
                    if (player != null) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(effective.isEmpty() ? null : effective).build());
                    d.dismiss();
                }).setNegativeButton("Cancel", null).create());
    }
    private void subtitleSettings() {
        if (player == null) return;
        long resumeToken = dialogPlayback.capture(player.getPlayWhenReady()); player.pause();
        settingsResumeToken = resumeToken;
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * k); body.setPadding(pad, 0, pad, 0);
        TextView hint = TvUi.text(this, k, 21, Ui.regular, TvUi.DIM);
        hint.setText("Saved for every film and episode. Applies to embedded text and all add-on subtitles."); body.addView(hint);
        StyledSubtitleView preview = new StyledSubtitleView(this); preview.setBackgroundColor(0xFF18212C);
        body.addView(preview, new LinearLayout.LayoutParams(-1, Math.round(130 * k)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout options = new LinearLayout(this); options.setOrientation(LinearLayout.VERTICAL); scroll.addView(options);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.round(410 * k)));
        String[] titles = {"Text size", "Text color", "Background", "Text edge", "Position", "Auto-select languages", "Allow Noiro AI translation"};
        String[][] choices = {SubtitlePreferences.SIZES, SubtitlePreferences.COLORS, SubtitlePreferences.BACKGROUNDS,
                SubtitlePreferences.EDGES, SubtitlePreferences.POSITIONS};
        TextView[] rows = new TextView[titles.length];
        Runnable refresh = () -> {
            int[] values = {subtitlePreferences.size, subtitlePreferences.color, subtitlePreferences.background, subtitlePreferences.edge, subtitlePreferences.position};
            for (int i = 0; i < 5; i++) rows[i].setText(titles[i] + "   ·   " + choices[i][values[i]]);
            rows[4].setText(titles[4] + "   ·   " + subtitlePreferences.positionLabel());
            List<String> names = new ArrayList<>(); for (String code : subtitlePreferences.languages) names.add(SubtitleSelection.name(code));
            rows[5].setText(titles[5] + "   ·   " + (names.isEmpty() ? "Off" : String.join(" → ", names)));
            rows[6].setText(titles[6] + "   ·   " + (subtitlePreferences.autoGemini ? "On" : "Off"));
            applyCaptionAppearance(); renderCaptions();
            SubtitleAppearance.apply(preview, subtitlePreferences, k);
            preview.setCues(java.util.Collections.singletonList(new Cue.Builder().setText("Subtitle preview · ČĆŽŠĐ čćžšđ").build()));
        };
        for (int i = 0; i < rows.length; i++) {
            final int field = i;
            rows[i] = TvUi.button(this, k, "", v -> {
                if (field == 5) { autoSubtitleLanguages(refresh); return; }
                if (field == 3) { subtitleEdgeSettings(refresh); return; }
                if (field == 4) { subtitlePosition(); return; }
                if (field == 6) {
                    if (!NoiroAccount.ready()) {
                        Toast.makeText(this, "AI translation uses optional Noiro cloud access. Existing subtitles remain available for free.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showBottom(new AlertDialog.Builder(this).setTitle("Allow Noiro AI translation")
                            .setMessage("When a preferred language has no existing subtitle, allow Noiro to translate. Uses the Translate to setting when selected and your Noiro Subtitles translation allowance.")
                            .setNegativeButton("Off", (d,w) -> { subtitlePreferences.autoGemini = false; subtitlePreferences.save(this); refresh.run(); })
                            .setPositiveButton("On", (d,w) -> { subtitlePreferences.autoGemini = true; subtitlePreferences.save(this); refresh.run(); }).create());
                    return;
                }
                int[] values = {subtitlePreferences.size, subtitlePreferences.color, subtitlePreferences.background, subtitlePreferences.edge, subtitlePreferences.position};
                showBottom(new AlertDialog.Builder(this).setTitle(titles[field]).setSingleChoiceItems(choices[field], values[field], (d, which) -> {
                    if (field == 0) subtitlePreferences.size = which;
                    else if (field == 1) subtitlePreferences.color = which;
                    else if (field == 2) subtitlePreferences.background = which;
                    else if (field == 3) subtitlePreferences.edge = which;
                    else subtitlePreferences.position = which;
                    subtitlePreferences.save(this); refresh.run(); d.dismiss();
                }).setNegativeButton("Cancel", null).create());
            });
            rows[i].setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2); row.topMargin = Math.round(8 * k);
            options.addView(rows[i], row);
        }
        TextView note = TvUi.text(this, k, 18, Ui.regular, TvUi.DIM);
        note.setText("Image subtitles keep their original font and colors. Language priorities apply to the next video; manual selection wins for this video.");
        body.addView(note);
        refresh.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Subtitle settings").setView(body).setOnCancelListener(d->{if(noiroChrome)main.post(this::subtitleOptions);})
                .setPositiveButton("Done", null).setNeutralButton("Reset appearance", null)
                .setOnDismissListener(d -> { if (!positionEditing && player != null && dialogPlayback.shouldResume(resumeToken)) player.play(); if (!positionEditing) video.requestFocus(); }).create();
        subtitleSettingsDialog = dialog;
        showBottom(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { subtitlePreferences.resetAppearance(); subtitlePreferences.save(this); refresh.run(); });
        rows[0].requestFocus();
    }
    private void autoSubtitleLanguages(Runnable refresh) {
        List<String> selected = new ArrayList<>(subtitlePreferences.languages);
        String[] names = new String[SubtitleSelection.NAMES.length];
        boolean[] checked = new boolean[names.length];
        Runnable labels = () -> { for (int i = 0; i < names.length; i++) {
            int rank = selected.indexOf(SubtitleSelection.CODES[i]);
            names[i] = (rank < 0 ? "" : (rank + 1) + ". ") + SubtitleSelection.NAMES[i];
        } };
        labels.run();
        for (int i = 0; i < checked.length; i++) checked[i] = selected.contains(SubtitleSelection.CODES[i]);
        showBottom(new AlertDialog.Builder(this).setTitle("Select up to 5 languages · choose in priority order")
                .setMultiChoiceItems(names, checked, (d, which, on) -> {
                    String code = SubtitleSelection.CODES[which];
                    if (on && selected.size() == 5) {
                        checked[which] = false;
                        android.widget.ListView list = ((AlertDialog)d).getListView();
                        list.post(() -> { list.setItemChecked(which, false); ((android.widget.BaseAdapter)list.getAdapter()).notifyDataSetChanged(); });
                        Toast.makeText(this, "Choose up to 5 languages. Uncheck one to add another.", Toast.LENGTH_SHORT).show(); return;
                    }
                    if (on) selected.add(code); else selected.remove(code);
                    labels.run();
                    ((android.widget.BaseAdapter)((AlertDialog)d).getListView().getAdapter()).notifyDataSetChanged();
                }).setPositiveButton("Save", (d,w) -> { subtitlePreferences.languages = selected; subtitlePreferences.save(this); refresh.run(); })
                .setNeutralButton("Turn off", (d,w) -> { subtitlePreferences.languages = new ArrayList<>(); subtitlePreferences.save(this); refresh.run(); })
                .setNegativeButton("Cancel", null).create());
    }
    private void subtitleEdgeSettings(Runnable refresh) {
        String[] fields = {"Style", "Border size", "Border color", "Shadow size", "Shadow color"};
        String[][] options = {SubtitlePreferences.EDGES, SubtitlePreferences.BORDER_SIZES, SubtitlePreferences.EDGE_COLORS,
                SubtitlePreferences.SHADOW_SIZES, SubtitlePreferences.EDGE_COLORS};
        int[] values = {subtitlePreferences.edge, subtitlePreferences.borderSize, subtitlePreferences.borderColor,
                subtitlePreferences.shadowSize, subtitlePreferences.shadowColor};
        String[] labels = new String[fields.length];
        for (int i = 0; i < fields.length; i++) labels[i] = fields[i] + " · " + options[i][values[i]];
        showBottom(new AlertDialog.Builder(this).setTitle("Text edge").setItems(labels, (parent, field) ->
                showBottom(new AlertDialog.Builder(this).setTitle(fields[field]).setSingleChoiceItems(options[field], values[field], (d, which) -> {
                    if (field == 0) subtitlePreferences.edge = which;
                    else if (field == 1) subtitlePreferences.borderSize = which;
                    else if (field == 2) subtitlePreferences.borderColor = which;
                    else if (field == 3) subtitlePreferences.shadowSize = which;
                    else subtitlePreferences.shadowColor = which;
                    subtitlePreferences.save(this); refresh.run(); d.dismiss();
                }).setNegativeButton("Cancel", null).create())).create());
    }
    private void subtitlePosition() {
        if (player == null) return;
        long resumeToken = settingsResumeToken;
        positionEditing = true;
        if (subtitleSettingsDialog != null) subtitleSettingsDialog.dismiss();
        video.hideController();
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * k); body.setPadding(pad, 0, pad, 0);
        TextView value = TvUi.text(this, k, 22, Ui.medium, Color.WHITE); value.setGravity(Gravity.CENTER); body.addView(value);
        TextView help = TvUi.text(this, k, 18, Ui.regular, TvUi.DIM);
        help.setText("− / Left: lower\n+ / Right: higher"); help.setGravity(Gravity.CENTER); body.addView(help);
        Runnable refresh = () -> {
            float height = captions.getHeight() > 0 ? captions.getHeight() : 1080 * k;
            value.setText(Math.round(subtitlePreferences.bottomFraction() * height) + " px above bottom");
            applyCaptionAppearance(); renderCaptions();
        };
        java.util.function.IntConsumer move = delta -> { subtitlePreferences.move(delta); subtitlePreferences.save(this); refresh.run(); };
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); body.addView(buttons);
        TextView lower = TvUi.button(this, k, "− Down", v -> move.accept(-4));
        TextView higher = TvUi.button(this, k, "+ Up", v -> move.accept(4));
        for (TextView button : new TextView[]{lower, higher}) {
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * k);
            button.setPadding(Math.round(12 * k), Math.round(12 * k), Math.round(12 * k), Math.round(12 * k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(4, 10, 4, 0); buttons.addView(button, lp);
        }
        TextView presets = TvUi.button(this, k, "Presets", v -> showBottom(new AlertDialog.Builder(this).setTitle("Subtitle position")
                .setSingleChoiceItems(SubtitlePreferences.POSITIONS, subtitlePreferences.positionMargin < 0 ? subtitlePreferences.position : -1, (d, which) -> {
                    subtitlePreferences.position = which; subtitlePreferences.positionMargin = -1;
                    subtitlePreferences.save(this); refresh.run(); d.dismiss();
                }).setNegativeButton("Cancel", null).create()));
        presets.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22 * k); presets.setPadding(10, 8, 10, 8); body.addView(presets);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Subtitle position").setView(body)
                .setPositiveButton("Done", null).setNeutralButton("Reset", (d,w) -> {
                    subtitlePreferences.position = 1; subtitlePreferences.positionMargin = -1; subtitlePreferences.save(this);
                }).setOnDismissListener(d -> {
                    positionEditing = false; applyCaptionAppearance(); renderCaptions();
                    if (player != null && dialogPlayback.shouldResume(resumeToken)) player.play(); video.requestFocus();
                }).create();
        dialog.setOnKeyListener((d, key, event) -> {
            View focused = dialog.getCurrentFocus();
            boolean footer = focused != null && (focused.getId() == android.R.id.button1 || focused.getId() == android.R.id.button3);
            if (!footer && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    (key == KeyEvent.KEYCODE_DPAD_LEFT ? lower : higher).requestFocus();
                    move.accept(key == KeyEvent.KEYCODE_DPAD_LEFT ? -4 : 4);
                }
                return true;
            }
            return false;
        });
        showBottom(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.BOTTOM | Gravity.RIGHT); dialog.getWindow().setDimAmount(0);
            dialog.getWindow().setLayout(Math.round(420 * k), -2);
        }
        refresh.run(); lower.requestFocus();
    }
    private static final class EmbeddedChoice {
        final TrackSelectionOverride track; final int rank;
        EmbeddedChoice(TrackSelectionOverride track, int rank) { this.track = track; this.rank = rank; }
    }
    private EmbeddedChoice embeddedChoice(List<String> preferred) {
        EmbeddedChoice best = null;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i)) {
                int rank = SubtitleSelection.rank(preferred, group.getTrackFormat(i).language);
                if (rank != Integer.MAX_VALUE && (best == null || rank < best.rank)) best = new EmbeddedChoice(new TrackSelectionOverride(group.getMediaTrackGroup(), i), rank);
            }
        }
        return best;
    }
    private void chooseEmbedded(EmbeddedChoice choice) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).addOverride(choice.track).build());
    }
    private void maybeAutoSelectSubtitles() {
        if (fixtureMode() || request.trailer() || autoSubtitleAttempted || player == null || player.getCurrentTracks().getGroups().isEmpty()) return;
        autoSubtitleAttempted = true;
        List<String> preferred = new ArrayList<>(subtitlePreferences.languages);
        String target = subtitlePreferences.translationTarget;
        if (subtitlePreferences.autoGemini && NoiroAccount.ready() && !target.isEmpty()) {
            preferred.remove(target);
            preferred.add(0, target);
        }
        if (preferred.isEmpty()) return;
        EmbeddedChoice embedded = embeddedChoice(preferred);
        if (embedded != null) { chooseEmbedded(embedded); if (embedded.rank == 0) return; }
        else player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        final int run = ++autoSubtitleGeneration; boolean allowGemini = subtitlePreferences.autoGemini && NoiroAccount.ready();
        subtitleStatus.setText("Searching preferred subtitles…"); subtitleStatus.setVisibility(View.VISIBLE);
        autoSubtitleDiscovery = new Thread(() -> {
            try {
                List<AddonSubtitles.Option> options = AddonSubtitles.discover(request.owner, request.type, request.videoId, request.source, target);
                List<String> languages = new ArrayList<>(); List<Boolean> noiro = new ArrayList<>(), ai = new ArrayList<>();
                for (AddonSubtitles.Option option : options) { languages.add(option.language); noiro.add(option.label.startsWith("Noiro Subtitles ·")); ai.add(!option.aiEndpoint.isEmpty()); }
                int index = SubtitleSelection.addon(preferred, languages, noiro, ai, allowGemini);
                main.post(() -> {
                    if (run != autoSubtitleGeneration || player == null || isFinishing()
                            || !ownerCurrent(request.owner)) return;
                    subtitleStatus.setVisibility(View.GONE);
                    EmbeddedChoice current = embeddedChoice(preferred);
                    if (index >= 0 && (current == null || SubtitleSelection.rank(preferred, options.get(index).language) < current.rank)) selectAddonSubtitle(options.get(index));
                    else if (current != null) chooseEmbedded(current);
                });
            } catch (Exception unavailable) {
                main.post(() -> { if (run == autoSubtitleGeneration && player != null) subtitleStatus.setVisibility(View.GONE); });
            }
        }, "preferred-subtitles"); autoSubtitleDiscovery.start();
    }
    private void cancelAutoSubtitles() {
        autoSubtitleGeneration++; if (autoSubtitleDiscovery != null) autoSubtitleDiscovery.interrupt();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean trailer = request != null && request.trailer();
        if (trailer) {
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MEDIA_STOP) {
                if (event.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_INFO) return true;
            if (player != null && !failed && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (player.getPlayWhenReady()) player.pause(); else player.play();
                }
                return true;
            }
        }
        if (player != null && !failed && (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                else if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { if (player.getPlayWhenReady()) player.pause(); else player.play(); }
                else if (player.isCurrentMediaItemSeekable()) player.seekTo(PlaybackPolicy.seek(player.getCurrentPosition(), key == KeyEvent.KEYCODE_MEDIA_REWIND ? -10000 : 10000, player.getDuration()));
                if (!trailer) video.showController();
            }
            return true;
        }
        if(key==KeyEvent.KEYCODE_BACK&&playbackPrompt!=null&&playbackPrompt.getVisibility()==View.VISIBLE&&!video.isControllerFullyVisible()){
            if(event.getAction()==KeyEvent.ACTION_UP){PlayerSessionPolicy.Segment active=activeSegment();if(active!=null)dismissedSegments.add(active.key());upNextDismissed=true;pendingEpisodeId="";playbackPrompt.setVisibility(View.GONE);video.requestFocus();}return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP && player != null)
                showBottom(new AlertDialog.Builder(this).setItems(new String[]{"Audio", "Subtitles", "Subtitle sync", "Subtitle settings", "Player settings"}, (d, which) -> {
                    if (which == 4) playerSettings(); else if (which == 3) subtitleSettings(); else if (which == 2) subtitleExtras(); else if (which == 0) audioOptions(); else subtitleMenu();
                }).create());
            return true;
        }
        if(noiroChrome&&player!=null&&!failed&&key==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_UP){long now=SystemClock.elapsedRealtime();if(backExitArmedAt>0&&now-backExitArmedAt<=2500){backExitArmedAt=0;finish();}else{backExitArmedAt=now;video.hideController();Toast.makeText(this,"Press Back again to exit playback.",Toast.LENGTH_SHORT).show();}}return true;
        }
        if(key!=KeyEvent.KEYCODE_BACK)backExitArmedAt=0;
        if(noiroChrome&&event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){View timeline=video.findViewById(androidx.media3.ui.R.id.exo_progress);if(timeline.hasFocus())((androidx.media3.ui.DefaultTimeBar)timeline).setKeyTimeIncrement(PlayerSessionPolicy.seekStep(PlayerUiPreferences.integer(this,"seek_step",10))*1000L*(event.getRepeatCount()>20?4:event.getRepeatCount()>8?2:1));}
        if (player != null && !failed && !video.isControllerFullyVisible()
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && player.isCurrentMediaItemSeekable())
                player.seekTo(PlaybackPolicy.seek(player.getCurrentPosition(), event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000, player.getDuration()));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void release() {
        transitions.reset(null);if(subtitleSyncDialog!=null){subtitleSyncDialog.dismiss();subtitleSyncDialog=null;}featureGeneration++;episodeRequestGeneration++;episodeLoading=false;if(pendingChapterLoad!=null){main.removeCallbacks(pendingChapterLoad);pendingChapterLoad=null;}pendingEpisodeId="";for(Thread worker:featureWorkers)worker.interrupt();featureWorkers.clear();if(chapterReader!=null){chapterReader.close();chapterReader=null;}if(cloud!=null){cloud.close();cloud=null;}if(frameCache!=null){frameCache.close();frameCache=null;}if(scrubPreview!=null){scrubPreview.setVisibility(View.GONE);scrubPreview.setImageDrawable(null);}if(playbackPrompt!=null)playbackPrompt.setVisibility(View.GONE);
        dialogPlayback.revoke();
        checkpoint(player != null && player.getPlaybackState() == Player.STATE_ENDED);
        main.removeCallbacks(progressTick);
        main.removeCallbacks(watchdog); main.removeCallbacks(captionTick); main.removeCallbacks(chromeTick); stopSubtitleWorker();
        cancelAutoSubtitles();
        subtitleMenuGeneration++; if (subtitleDiscovery != null) subtitleDiscovery.interrupt();
        if (player != null) { position = player.getCurrentPosition(); wantsPlay = player.getPlayWhenReady(); video.setPlayer(null); player.release(); player = null; }
        embeddedCues = java.util.Collections.emptyList(); addonCaption = "";
        if (captions != null) { captions.setCues(java.util.Collections.emptyList()); captions.setVisibility(View.GONE); }
    }
    @Override protected void onSaveInstanceState(Bundle out) { if (player != null) position = player.getCurrentPosition(); out.putLong("position", position); out.putBoolean("playing", wantsPlay); super.onSaveInstanceState(out); }
    @Override protected void onStop() { sleepDeadline = 0; sleepMinutes = 0;sleepAtEpisodeEnd=false; release(); super.onStop(); }
    @Override protected void onDestroy() { release(); super.onDestroy(); }
}
