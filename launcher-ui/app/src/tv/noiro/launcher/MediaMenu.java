package tv.noiro.launcher;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Shared card actions; preserves the launcher's existing popover and title identity. */
final class MediaMenu {
    static boolean supported(String kind) { return "movie".equals(kind) || "tv".equals(kind); }
    static void attach(Activity activity, View card, Tmdb.Title title) {
        if (!supported(title.kind) || title.id <= 0) return;
        card.setOnLongClickListener(v -> { show(activity, v, anchor(v), title, null, false, true, () -> TvUi.refreshSeen(card)); return true; });
    }
    static Rect anchor(View view) {
        int[] at = new int[2]; view.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }
    static void show(Activity activity, View parent, Rect anchor, Tmdb.Title title, UpNext.Item continuing,
                     boolean light, boolean held, Runnable changed) {
        show(activity, parent, anchor, title, continuing, light, held, changed, null);
    }
    static void show(Activity activity, View parent, Rect anchor, Tmdb.Title title, UpNext.Item continuing,
                     boolean light, boolean held, Runnable changed, StreamsActivity.Hero hero) {
        if (!supported(title.kind)) return;
        new Session(activity, parent, new Rect(anchor), title, continuing, light, changed, hero).show(held);
    }
    private static final class Session {
        final Activity activity; final View parent; final Rect anchor;
        final UpNext.Item continuing; final boolean light; final Runnable changed;
        final StreamsActivity.Hero hero;
        final AccountState owner = NoiroAccount.current();
        Tmdb.Title title;
        Session(Activity activity, View parent, Rect anchor, Tmdb.Title title, UpNext.Item continuing, boolean light,
                Runnable changed, StreamsActivity.Hero hero) {
            this.activity=activity;this.parent=parent;this.anchor=anchor;this.title=title;this.continuing=continuing;this.light=light;this.changed=changed;
            this.hero = hero;
        }
        boolean current() {
            return !activity.isFinishing() && !activity.isDestroyed() && parent.isAttachedToWindow()
                    && parent.getWindowVisibility()==View.VISIBLE && NoiroAccount.current().owns(owner.authKey, owner.uid);
        }
        void toast(String text) { if(current())Toast.makeText(activity,text,Toast.LENGTH_SHORT).show(); }
        void show(boolean held) {
            if(!current())return;
            List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
            if (continuing != null) {
                labels.add(continuing.action() + " · " + continuing.detail());
                actions.add(() -> StreamsActivity.resume(activity, continuing, title.name, hero));
            }
            labels.add("Open details");actions.add(()->resolve(()->DetailActivity.open(activity,title.kind,title.id)));
            if (continuing == null && "movie".equals(title.kind)) {
                labels.add("Choose streams");
                actions.add(() -> resolve(this::openStreams));
            }
            labels.add("Play trailer");actions.add(this::trailer);
            labels.add("My list");actions.add(this::list);
            // Seen on this TV is local. Up Next has an IMDb pointer, often before TMDB artwork resolves.
            if(title.id>0 || continuing!=null){
                boolean seen = title.id>0 && Tmdb.isSeen(activity,title.kind,title.id)
                        || continuing!=null && UpNext.isHidden(activity,owner,continuing);
                labels.add(seen?"Mark as unseen on this TV":"Mark as seen on this TV");
                actions.add(()->{
                    boolean mark = !(title.id>0 && Tmdb.isSeen(activity,title.kind,title.id)
                            || continuing!=null && UpNext.isHidden(activity,owner,continuing));
                    if(continuing!=null && !UpNext.setHidden(activity,owner,continuing,title.id,mark)){
                        toast("Couldn't save this TV's watch status.");return;
                    }
                    if(title.id>0) Tmdb.setSeen(activity,title,mark);
                    changed.run();
                    // A newly arrived Up Next item may not have TMDB metadata yet.
                    // Resolve after the immediate local dismissal so the detail-screen Undo still works.
                    if(mark && continuing!=null && title.id<=0) new Thread(()->{
                        try{
                            ensureTitle();
                            if(NoiroAccount.current().owns(owner.authKey,owner.uid)){
                                Tmdb.setSeen(activity,title,true);
                                UpNext.setHidden(activity,owner,continuing,title.id,true);
                            }
                        }catch(Exception ignored){ /* IMDb-based dismissal remains valid offline. */ }
                    },"up-next-seen-lookup").start();
                });
            }
            TvUi.popover(activity,parent,anchor,title.name,labels.toArray(new String[0]),which->{if(current())actions.get(which).run();},light,held);
        }
        interface Work { void run() throws Exception; }
        private void openStreams() {
            StreamsActivity.Hero selected = hero == null ? new StreamsActivity.Hero(title.name, Ui.tr("Filme", "Movie"),
                    KodiHomeView.titleArtwork(title.backdrop, Tmdb.BACKDROP), "", title.year, "", title.overview) : hero;
            StreamsActivity.open(activity, "movie", title.id, 0, 0, title.name, selected);
        }
        void background(Work work, String error) {
            toast("Please wait…");
            new Thread(()->{try{if(current())work.run();}catch(Exception unavailable){activity.runOnUiThread(()->toast(error));}},"media-action").start();
        }
        void resolve(Runnable action) {
            if(title.id>0){if(current())action.run();return;}
            background(()->{ensureTitle();activity.runOnUiThread(()->{if(current())action.run();});},"Couldn't load this title. Try again.");
        }
        void ensureTitle() throws Exception {
            if(title.id>0)return;
            if(continuing==null)throw new IllegalArgumentException();
            JSONObject data=Tmdb.get("/find/"+continuing.id+"?external_source=imdb_id");
            JSONArray matches=data.optJSONArray("tv".equals(title.kind)?"tv_results":"movie_results");
            JSONObject hit=matches==null||matches.length()==0?null:matches.optJSONObject(0);
            if(hit==null||hit.optInt("id")<=0)throw new IllegalArgumentException();
            title=new Tmdb.Title(title.kind,hit.optInt("id"),title.name,hit.optString("poster_path",""),hit.optString("backdrop_path",""),"",hit.optDouble("vote_average",0));
        }
        void list() {
            if(!current())return;
            TvUi.popover(activity,parent,anchor,"My list · "+title.name,new String[]{"Add to My list","Remove from My list"},which->background(()->{
                ensureTitle();if(!current())return;Tmdb.setWatchlist(activity,title,which==0);
                activity.runOnUiThread(()->{if(current()){changed.run();toast(which==0?"Added to My list":"Removed from My list");}});
            },"Couldn't update My list. Try again."),light);
        }
        void trailer() {
            background(()->{
                ensureTitle();if(!current())return;
                JSONObject data=Tmdb.get("/"+title.kind+"/"+title.id+"?append_to_response=videos,external_ids");
                String key=TrailerResolver.youtubeKey(data);if(key==null)throw new IllegalArgumentException();
                JSONObject external=data.optJSONObject("external_ids");
                String imdb=data.optString("imdb_id",external==null?"":external.optString("imdb_id"));
                TrailerResolver.Result result=TrailerResolver.resolve(key,imdb);
                activity.runOnUiThread(()->{if(current())NoiroPlayerActivity.openTrailer(activity,result,key,title.name+" · Trailer");});
            },"Couldn't load this trailer. Please try again later.");
        }
    }
}
