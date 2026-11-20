package tv.noiro.launcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Bounded metadata-only chapter reader. No media decoding, credentials or network policy here.
 * Matroska elements follow RFC9559; MP4 chpl timestamps are 100ns (Nero convention).
 * Ordered/external Matroska editions and QuickTime chapter text tracks are deliberately excluded.
 */
final class PlayerChapters {
    interface Reader { byte[] read(long offset, int maxBytes) throws Exception; }
    static final class Chapter {
        final String title; final long startMs;
        Chapter(String title, long startMs) { this.title = title; this.startMs = startMs; }
    }
    private static final long SEGMENT=0x18538067L, CHAPTERS=0x1043A770L, SEEK_HEAD=0x114D9B74L;
    private static final int WINDOW=65536, MAX_BYTES=2*1024*1024, MAX_REQUESTS=48, MAX_NODES=10000;
    static List<Chapter> read(Reader source) {
        try {
            Input in=new Input(source);
            List<Chapter> out=in.u(0,4)==0x1A45DFA3L ? mkv(in) : mp4(in);
            TreeMap<Long,Chapter> sorted=new TreeMap<>();
            for (Chapter c:out) if(c.startMs>=0 && c.startMs<7L*24*3600*1000)
                if(!sorted.containsKey(c.startMs))sorted.put(c.startMs,c);
            return new ArrayList<>(sorted.values());
        } catch (Exception malformedOrUnavailable) { return new ArrayList<>(); }
    }
    private static final class Input {
        final Reader reader; byte[] cache=new byte[0]; long base=-1; int bytes,requests,nodes;
        Input(Reader reader){this.reader=reader;}
        byte[] bytes(long offset,int count)throws Exception {
            if(offset<0||count<0||count>WINDOW||offset>Long.MAX_VALUE-count)throw new Exception("bounds");
            if(offset<base || offset+count>base+cache.length){
                if(++requests>MAX_REQUESTS || bytes+WINDOW>MAX_BYTES)throw new Exception("budget");
                cache=reader.read(offset,WINDOW);base=offset;
                if(cache==null||cache.length>WINDOW)throw new Exception("reader bounds");
                bytes+=cache.length;
            }
            if(offset<base||offset+count>base+cache.length)throw new Exception("truncated");
            return java.util.Arrays.copyOfRange(cache,(int)(offset-base),(int)(offset-base)+count);
        }
        long u(long at,int n)throws Exception {
            if(n<1||n>8)throw new Exception("integer width");
            byte[] b=bytes(at,n);long v=0;
            for(byte x:b){if(v>(Long.MAX_VALUE-(x&255))/256)throw new Exception("integer overflow");v=v*256+(x&255);}
            return v;
        }
        void node()throws Exception{if(++nodes>MAX_NODES)throw new Exception("node budget");}
    }
    private static final class Element {
        long id,data,end; boolean unknown;
        Element(long id,long data,long end,boolean unknown){this.id=id;this.data=data;this.end=end;this.unknown=unknown;}
    }
    private static long[] vint(Input in,long at,boolean id)throws Exception{
        int first=(int)in.u(at,1),n=1,mask=128;
        while(n<=8&&(first&mask)==0){mask>>=1;n++;}
        if(n>8||(id&&n>4))throw new Exception("invalid vint");
        long value=id?first:first&(mask-1);
        for(int i=1;i<n;i++)value=(value<<8)|in.u(at+i,1);
        boolean unknown=!id&&value==((1L<<(7*n))-1);
        return new long[]{value,n,unknown?1:0};
    }
    private static Element element(Input in,long at,long limit)throws Exception{
        in.node();long[] id=vint(in,at,true),size=vint(in,at+id[1],false);
        long data=at+id[1]+size[1];
        if(data<at||data>limit)throw new Exception("element header bounds");
        long end=size[2]==1?limit:data+size[0];
        if(end<data||end>limit)throw new Exception("element bounds");
        return new Element(id[0],data,end,size[2]==1);
    }
    private static List<Chapter> mkv(Input in)throws Exception{
        Element root=element(in,0,Long.MAX_VALUE);
        long p=root.end;
        for(int i=0;i<8;i++){
            Element segment=element(in,p,Long.MAX_VALUE);
            if(segment.id!=SEGMENT){if(segment.unknown)return new ArrayList<>();p=segment.end;continue;}
            long seek=-1; p=segment.data;
            for(int j=0;j<48&&p<segment.end;j++){
                Element e=element(in,p,segment.end);
                if(e.id==CHAPTERS)return editions(in,e);
                if(e.id==SEEK_HEAD){long found=chapterSeek(in,e,segment.data);if(found>=0)seek=found;}
                if(e.id==0x1F43B675L || e.unknown)break; // never walk movie clusters
                p=e.end;
            }
            if(seek>=segment.data&&seek<segment.end){Element e=element(in,seek,segment.end);if(e.id==CHAPTERS)return editions(in,e);}
            return new ArrayList<>();
        }
        return new ArrayList<>();
    }
    private static long chapterSeek(Input in,Element head,long segmentData)throws Exception{
        for(long p=head.data;p<head.end;){
            Element seek=element(in,p,head.end);p=seek.end;
            if(seek.id!=0x4DBBL)continue;
            long id=-1,position=-1;
            for(long q=seek.data;q<seek.end;){
                Element part=element(in,q,seek.end);q=part.end;
                if(part.id==0x53ABL)id=in.u(part.data,(int)(part.end-part.data));
                if(part.id==0x53ACL)position=in.u(part.data,(int)(part.end-part.data));
            }
            if(id==CHAPTERS&&position>=0&&position<=Long.MAX_VALUE-segmentData)return segmentData+position;
        }
        return -1;
    }
    private static List<Chapter> editions(Input in,Element chapters)throws Exception{
        List<Chapter> first=new ArrayList<>();
        for(long p=chapters.data;p<chapters.end;){
            Element edition=element(in,p,chapters.end);p=edition.end;
            if(edition.id!=0x45B9L)continue;
            boolean ordered=false,hidden=false,isDefault=false;
            List<Chapter> rows=new ArrayList<>();
            for(long q=edition.data;q<edition.end;){
                Element part=element(in,q,edition.end);q=part.end;
                if(part.id==0x45DDL)ordered=in.u(part.data,(int)(part.end-part.data))!=0;
                if(part.id==0x45BDL)hidden=in.u(part.data,(int)(part.end-part.data))!=0;
                if(part.id==0x45DBL)isDefault=in.u(part.data,(int)(part.end-part.data))!=0;
                if(part.id==0xB6L)atom(in,part,rows,0);
            }
            if(ordered||hidden)continue;
            if(isDefault)return rows;
            if(first.isEmpty())first=rows;
        }
        return first;
    }
    private static void atom(Input in,Element chapter,List<Chapter> out,int depth)throws Exception{
        if(depth>12||out.size()>1024)throw new Exception("chapter budget");
        long start=-1;String title="";boolean excluded=false;
        List<Element> children=new ArrayList<>();
        for(long p=chapter.data;p<chapter.end;){
            Element e=element(in,p,chapter.end);p=e.end;
            if(e.id==0x91L)start=in.u(e.data,(int)(e.end-e.data))/1_000_000;
            if(e.id==0x98L && in.u(e.data,(int)(e.end-e.data))!=0)excluded=true;
            if(e.id==0x4598L && in.u(e.data,(int)(e.end-e.data))==0)excluded=true;
            if(e.id==0x6E67L)excluded=true; // external segment cannot map to this file
            if(e.id==0xB6L)children.add(e);
            if(e.id==0x80L)for(long q=e.data;q<e.end;){
                Element display=element(in,q,e.end);q=display.end;
                if(display.id==0x85L&&title.isEmpty()&&display.end-display.data<=2048)
                    title=text(in.bytes(display.data,(int)(display.end-display.data)));
            }
        }
        if(!excluded){if(start>=0)out.add(new Chapter(title.isEmpty()?"Chapter "+(out.size()+1):title,start));
            for(Element child:children)atom(in,child,out,depth+1);}
    }
    private static List<Chapter> mp4(Input in)throws Exception{
        List<Chapter> out=new ArrayList<>();boxes(in,0,Long.MAX_VALUE,0,out);return out;
    }
    private static void boxes(Input in,long start,long limit,int depth,List<Chapter> out)throws Exception{
        if(depth>8)throw new Exception("box nesting");
        for(long p=start;p<limit;){
            in.node();long size;try{size=in.u(p,4);}catch(Exception eof){if(depth==0)return;throw eof;}
            String type=new String(in.bytes(p+4,4),StandardCharsets.US_ASCII);long data=p+8;
            if(size==1){size=in.u(p+8,8);data=p+16;}
            else if(size==0){if(limit==Long.MAX_VALUE)return;size=limit-p;}
            if(size<data-p||p>Long.MAX_VALUE-size||p+size>limit)throw new Exception("box bounds");
            long end=p+size;
            if(type.equals("moov")||type.equals("udta"))boxes(in,data,end,depth+1,out);
            else if(type.equals("chpl"))chpl(in,data,end,out);
            if(!out.isEmpty()&&depth==0)return;
            p=end;
        }
    }
    private static void chpl(Input in,long p,long end,List<Chapter> out)throws Exception{
        if(end-p<5)throw new Exception("short chpl");
        long version=in.u(p,1);p+=4;
        if(version!=0)p+=4;
        if(p>=end)throw new Exception("short chpl header");
        int count=(int)in.u(p++,1);
        List<Chapter> parsed=new ArrayList<>();
        for(int i=0;i<count;i++){
            if(end-p<9)throw new Exception("short chapter");
            long start=in.u(p,8)/10_000;p+=8;int length=(int)in.u(p++,1);
            if(length>end-p)throw new Exception("short chapter title");
            String title=text(in.bytes(p,length));p+=length;
            parsed.add(new Chapter(title.isEmpty()?"Chapter "+(i+1):title,start));
        }
        out.addAll(parsed);
    }
    private static String text(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}]"," ").trim();}
}
