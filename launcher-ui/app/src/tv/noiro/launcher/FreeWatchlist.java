package tv.noiro.launcher;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local IMDb bookmarks. Anonymous browsing and each real Stremio account have distinct storage. */
final class FreeWatchlist {
    private static final int LIMIT = 500;
    private FreeWatchlist() {}

    private static String scope() {
        AccountState owner = NoiroAccount.current();
        return owner.signedIn() ? "user:" + owner.uid : "guest";
    }

    static synchronized List<StudioHome.Card> cards(Context context) {
        List<StudioHome.Card> cards = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(context.getSharedPreferences("local-watchlist", Context.MODE_PRIVATE)
                    .getString(scope(), "[]"));
            for (int i = 0; i < saved.length() && cards.size() < LIMIT; i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                StudioHome.Card card = FreeCatalog.card(item, item.optString("type"));
                if (card != null) cards.add(card);
            }
        } catch (Exception invalid) { /* A corrupt bookmark cannot prevent browsing. */ }
        return cards;
    }

    static synchronized boolean contains(Context context, StudioHome.Card card) {
        for (StudioHome.Card saved : cards(context)) if (saved.key().equals(card.key())) return true;
        return false;
    }

    static synchronized void toggle(Context context, StudioHome.Card card) {
        if (card == null || !card.title() || !card.id.matches("tt[0-9]{5,12}")) return;
        String key = scope();
        List<StudioHome.Card> saved = cards(context);
        boolean removed = saved.removeIf(item -> item.key().equals(card.key()));
        if (!removed) saved.add(0, card);
        JSONArray data = new JSONArray();
        for (StudioHome.Card item : saved) {
            if (data.length() >= LIMIT) break;
            try {
                // Persist public artwork and title fields only, never stream URLs or credentials.
                JSONObject value = new JSONObject().put("id", item.id).put("type", item.type)
                        .put("name", item.name).put("poster", item.image).put("background", item.background)
                        .put("logo", item.logo).put("description", item.summary);
                JSONObject meta = item.metadata;
                if (meta != null) {
                    for (String field : new String[]{"releaseInfo", "imdbRating", "runtime"})
                        value.put(field, meta.optString(field));
                    if (meta.optJSONArray("genres") != null) value.put("genres", meta.optJSONArray("genres"));
                }
                data.put(value);
            } catch (Exception invalid) { /* Keep the other bookmarks. */ }
        }
        if (key.equals(scope())) context.getSharedPreferences("local-watchlist", Context.MODE_PRIVATE)
                .edit().putString(key, data.toString()).apply();
    }
}
