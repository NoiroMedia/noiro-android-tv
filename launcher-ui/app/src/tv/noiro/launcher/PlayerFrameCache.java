package tv.noiro.launcher;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Real rendered frames only; private, bounded disk cache, never uploaded. All IO stays off UI. */
final class PlayerFrameCache {
    private final File directory;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main;
    private final java.util.concurrent.atomic.AtomicInteger generation=new java.util.concurrent.atomic.AtomicInteger();
    PlayerFrameCache(Context c,AccountState owner,String video,Handler main){directory=new File(new File(c.getCacheDir(),"player-frames"),PlayerSegmentStore.key(owner,video));this.main=main;}
    void save(long position,Bitmap bitmap){
        final int run=generation.get();io.execute(()->{
            try {
                if(run!=generation.get())return;directory.mkdirs();File target=new File(directory,(position/10_000*10_000)+".jpg");
                try(FileOutputStream output=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.JPEG,75,output);}
                File[] files=directory.listFiles((d,n)->n.matches("[0-9]+\\.jpg"));
                if(files!=null&&files.length>120){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-120;i++)files[i].delete();}
                trim(directory.getParentFile());
            }catch(Exception unavailable){ }finally{bitmap.recycle();}
        });
    }
    void show(long position,java.util.function.Consumer<Bitmap> callback){
        final int run=generation.incrementAndGet();io.execute(()->{
            Bitmap image=null;
            try {
                File[] files=directory.listFiles((d,n)->n.matches("[0-9]+\\.jpg"));File nearest=null;long difference=15_001;
                if(files!=null)for(File f:files){long at=Long.parseLong(f.getName().replace(".jpg","")),gap=Math.abs(at-position);if(gap<difference){nearest=f;difference=gap;}}
                if(nearest!=null&&difference<=15_000)image=BitmapFactory.decodeFile(nearest.getAbsolutePath());
            }catch(Exception unavailable){ }
            final Bitmap result=image;main.post(()->{if(run==generation.get())callback.accept(result);else if(result!=null)result.recycle();});
        });
    }
    void clearPreview(){generation.incrementAndGet();}
    void close(){generation.incrementAndGet();io.shutdown();}
    private static void trim(File root){
        List<File> files=new ArrayList<>();long total=0;File[] dirs=root.listFiles();
        if(dirs!=null)for(File dir:dirs){File[] children=dir.listFiles((d,n)->n.matches("[0-9]+\\.jpg"));if(children!=null)for(File file:children){files.add(file);total+=file.length();}}
        files.sort(Comparator.comparingLong(File::lastModified));for(File file:files){if(total<=24*1024*1024)return;long size=file.length();if(file.delete())total-=size;}
    }
}
