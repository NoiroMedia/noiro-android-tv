package tv.noiro.launcher;

import java.util.Locale;

/** Native provider scales; scores are never relabeled or derived from another service. */
final class MediaRating {
    final String source;
    final double value;
    final int max;
    final long votes;
    private MediaRating(String source, double value, int max, long votes) { this.source=source; this.value=value; this.max=max; this.votes=votes; }
    static MediaRating of(String source, double value) { return of(source,value,-1); }
    static MediaRating of(String source, double value, long votes) {
        int max;
        switch(source) {
            case "letterboxd": max=5; break;
            case "rogerebert": max=4; break;
            case "imdb": case "tmdb": case "metacriticuser": case "myanimelist": max=10; break;
            case "tomatoes": case "popcorn": case "metacritic": case "trakt": max=100; break;
            default: return null;
        }
        return !Double.isFinite(value) || value<0 || value>max ? null : new MediaRating(source,value,max,votes>=0&&votes<=10000000000L?votes:-1);
    }
    String text() {
        return max==100 ? String.format(Locale.US,"%.0f%s",value,source.equals("tomatoes")||source.equals("popcorn")?"%":"")
                : String.format(Locale.US,"%.1f",value);
    }
    String caption() {
        if((source.equals("imdb")||source.equals("tmdb"))&&votes>0) {
            String count=votes>=1000000?String.format(Locale.US,"%.1fM",votes/1000000d)
                    :votes>=1000?String.format(Locale.US,"%.1fK",votes/1000d):String.valueOf(votes);
            return count+" votes";
        }
        switch(source){case "tomatoes":return "Tomatometer®";case "popcorn":return "Popcornmeter®";case "metacritic":return "Metascore";default:return label();}
    }
    String label() {
        switch(source) {
            case "imdb": return "IMDb";
            case "letterboxd": return "Letterboxd";
            case "tomatoes": return "Rotten Tomatoes critics";
            case "popcorn": return "Rotten Tomatoes audience";
            case "metacritic": return "Metacritic";
            case "metacriticuser": return "Metacritic users";
            case "trakt": return "Trakt";
            case "tmdb": return "TMDB";
            case "rogerebert": return "Roger Ebert";
            default: return "MyAnimeList";
        }
    }
}
