package tv.noiro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Original unmodified rating artwork shared with the Noiro Kodi skin. Notices are bundled offline. */
final class RatingMarks {
    private final Map<String,Bitmap> images=new HashMap<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF rect=new RectF();
    RatingMarks(Context context){
        for(String name:new String[]{"imdb","letterboxd","rtfresh","rtrotten","popcorn","popcorn_spilt","metacritic","tmdb","trakt"})
            try(InputStream in=context.getAssets().open("ratings/"+name+".png")){
                Bitmap image=BitmapFactory.decodeStream(in);if(image!=null)images.put(name,image);
            }catch(Exception ignored){ }
    }
    static int order(String source){
        switch(source){case "tomatoes":return 0;case "popcorn":return 1;case "metacritic":return 2;case "imdb":return 3;case "tmdb":return 4;case "letterboxd":return 5;case "trakt":return 6;default:return 99;}
    }
    static boolean supports(String source){return order(source)<99;}
    private Bitmap image(MediaRating rating){
        String name=rating.source;
        if(name.equals("tomatoes"))name=rating.value<60?"rtrotten":"rtfresh";
        else if(name.equals("popcorn"))name=rating.value<60?"popcorn_spilt":"popcorn";
        return images.get(name);
    }
    float width(MediaRating rating,float height){Bitmap image=image(rating);return image==null?height:height*image.getWidth()/image.getHeight();}
    void draw(Canvas canvas,MediaRating rating,float left,float top,float height){
        Bitmap image=image(rating);if(image==null)return;
        rect.set(left,top,left+width(rating,height),top+height);canvas.drawBitmap(image,null,rect,paint);
    }
}
