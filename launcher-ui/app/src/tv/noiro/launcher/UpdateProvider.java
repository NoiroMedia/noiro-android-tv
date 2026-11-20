package tv.noiro.launcher;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;

/** Only the verified update APK can be shared, read-only, via an explicit URI grant. */
public class UpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private void check(Uri uri) throws FileNotFoundException {
        if (!"/launcher-update.apk".equals(uri.getPath()) || !LauncherUpdates.ready(getContext())) throw new FileNotFoundException();
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        check(uri); if(!"r".equals(mode))throw new FileNotFoundException();
        return ParcelFileDescriptor.open(LauncherUpdates.apk(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try { check(uri); } catch(FileNotFoundException e) { return null; }
        String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(cols); Object[] row=new Object[cols.length];
        for(int i=0;i<cols.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?(AppIdentity.standalone(getContext())?"Noiro.apk":"NoiroTV-Launcher.apk"):OpenableColumns.SIZE.equals(cols[i])?LauncherUpdates.apk(getContext()).length():null;
        cursor.addRow(row); return cursor;
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
}
