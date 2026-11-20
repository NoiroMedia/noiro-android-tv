package tv.noiro.launcher;

import java.net.*;
import java.io.*;
import java.util.function.BooleanSupplier;

/** Bounded same-source range reads; never follows redirects or forwards credentials elsewhere. */
final class PlayerChapterReader implements PlayerChapters.Reader,AutoCloseable {
    private final StremioStreams.Source source;private final BooleanSupplier current;
    private volatile HttpURLConnection active;
    PlayerChapterReader(StremioStreams.Source source,BooleanSupplier current){this.source=source;this.current=current;}
    @Override public byte[] read(long offset,int limit) throws Exception {
        if(offset<0||limit<1||limit>65536||offset>Long.MAX_VALUE-limit||!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException();
        URL url=new URL(source.url);if(!StreamPolicy.web(source.url,true))throw new IOException("unsupported_source");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();active=connection;
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(4000);connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent","NoiroTV-Launcher/"+NoiroAccount.APP_VERSION);
            for(java.util.Map.Entry<String,String> header:source.headers.entrySet())if(PlaybackPolicy.allowedHeader(header.getKey(),header.getValue()))connection.setRequestProperty(header.getKey(),header.getValue());
            connection.setRequestProperty("Range","bytes="+offset+"-"+(offset+limit-1));connection.setRequestProperty("Accept-Encoding","identity");
            int status=connection.getResponseCode();
            if(status==416)return new byte[0];
            if(status!=206&&(status!=200||offset!=0))throw new IOException("range_unavailable");
            if(status==206){String range=connection.getHeaderField("Content-Range");java.util.regex.Matcher match=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/(?:[0-9]+|\\*)").matcher(range==null?"":range);if(!match.matches())throw new IOException("range_mismatch");long start=Long.parseLong(match.group(1)),end=Long.parseLong(match.group(2));if(start!=offset||end<start||end-start>=limit)throw new IOException("range_mismatch");}
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[Math.min(8192,limit)];int count;
                while(output.size()<limit&&(count=input.read(buffer,0,Math.min(buffer.length,limit-output.size())))>0){if(!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException();output.write(buffer,0,count);}
                if(!current.getAsBoolean())throw new InterruptedException();return output.toByteArray();
            }
        }finally{active=null;connection.disconnect();}
    }
    @Override public void close(){HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
}
