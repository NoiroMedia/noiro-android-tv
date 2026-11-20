package tv.noiro.launcher;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Subtitle add-on discovery and explicit Noiro AI jobs; no provider API key is needed on TV. */
final class AddonSubtitles {
    static final class Option {
        final String label, url, aiEndpoint, language;
        volatile String jobUrl;
        Option(String label,String url,String aiEndpoint,String language){this.label=label;this.url=url;this.aiEndpoint=aiEndpoint;this.language=language;}
    }
    private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}
    static String language(String lang){String value=Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH);return value.isEmpty()?lang:value;}
    static List<Option> discover(AccountState owner,String type,String id,StremioStreams.Source source,String requestedTarget)throws Exception {
        JSONObject result=AccountHttp.call(StremioLogin.API+"addonCollectionGet",new JSONObject().put("authKey",owner.authKey),"").optJSONObject("result");
        if(result==null)throw new AccountHttp.Failure("authentication_required");
        JSONArray addons=result.optJSONArray("addons");
        ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<List<Option>>> calls=new ArrayList<>();List<Option> all=new ArrayList<>();
        try{
            for(int i=0;addons!=null && i<Math.min(addons.length(),64);i++){
                JSONObject entry=addons.optJSONObject(i);if(entry==null)continue;JSONObject manifest=entry.optJSONObject("manifest");
                if(!StremioStreams.supportsResource(manifest,"subtitles",type,id))continue;
                final String transport=entry.optString("transportUrl"),endpoint;
                try{endpoint=StreamPolicy.resourceEndpoint(transport,"subtitles",type,id);}catch(Exception invalid){continue;}
                final String name=manifest.optString("name","Subtitles");
                final boolean noiro="org.noiro.subtitle-translator".equals(manifest.optString("id")) && "noiro.tv".equals(URI.create(transport).getHost());
                calls.add(pool.submit(()->{
                    List<Option> options=new ArrayList<>();String extras="";
                    if(!source.filename.isEmpty())extras="filename="+enc(source.filename);
                    if(source.videoSize.matches("[0-9]{1,18}"))extras+=(extras.isEmpty()?"":"&")+"videoSize="+source.videoSize;
                    if(source.videoHash.matches("[a-fA-F0-9]{16}"))extras+=(extras.isEmpty()?"":"&")+"videoHash="+source.videoHash;
                    if(noiro){
                        String targetCode="";
                        boolean canChooseTarget=false;
                        try{
                            JSONObject policy=AccountHttp.call(transport.replace("manifest.json","policy.json"),null,"");
                            targetCode=policy.optString("target","");
                            canChooseTarget=policy.optBoolean("clientTarget",false);
                        }catch(Exception ignored){}
                        String targetParam="";
                        if(!requestedTarget.isEmpty() && canChooseTarget){
                            targetCode=requestedTarget;
                            targetParam="&noiroTarget="+enc(requestedTarget);
                        }
                        if(requestedTarget.isEmpty() || canChooseTarget){
                            String target=targetCode.isEmpty()?"configured language":language(targetCode);
                            String progress=endpoint.substring(0,endpoint.length()-5)+"/"+(extras.isEmpty()?"":extras+"&")+"noiroMode=progress"+targetParam;
                            options.add(new Option("Noiro Subtitles · AI translation → "+target,"",progress,targetCode));
                        }
                        extras+=(extras.isEmpty()?"":"&")+"noiroMode=search"+targetParam;
                    }
                    String url=extras.isEmpty()?endpoint:endpoint.substring(0,endpoint.length()-5)+"/"+extras+".json";
                    try{
                        JSONArray subtitles=AccountHttp.call(url,null,"").optJSONArray("subtitles");
                        for(int n=0;subtitles!=null && n<Math.min(subtitles.length(),150);n++){
                            JSONObject subtitle=subtitles.optJSONObject(n);if(subtitle==null||!StreamPolicy.web(subtitle.optString("url"),true))continue;
                            String label=name+" · "+language(subtitle.optString("lang","und"));
                            String detail=subtitle.optString("label",subtitle.optString("id"));if(!detail.isEmpty())label+=" · "+detail;
                            options.add(new Option(label,subtitle.getString("url"),"",subtitle.optString("lang","und")));
                        }
                    }catch(Exception unavailable){ /* A Noiro AI entry can still be selected when original search is unavailable. */ }
                    return options;
                }));
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(28);
            for(Future<List<Option>> call:calls)try{all.addAll(call.get(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS));}catch(InterruptedException stopped){throw stopped;}catch(Exception ignored){call.cancel(true);}
            return all;
        }finally{pool.shutdownNow();}
    }
    static String start(Option option)throws Exception {
        if(option.aiEndpoint.isEmpty())return option.url;
        if(option.jobUrl!=null)return option.jobUrl;
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);StringBuilder playback=new StringBuilder();for(byte b:random)playback.append(String.format(Locale.ROOT,"%02x",b&255));
        JSONArray results=AccountHttp.call(option.aiEndpoint+"&noiroPlayback="+playback+".json",null,"",60000).optJSONArray("subtitles");
        for(int i=0;results!=null&&i<results.length();i++){
            JSONObject sub=results.optJSONObject(i);
            if(sub==null || !StreamPolicy.web(sub.optString("url"),true))continue;
            if (option.aiEndpoint.contains("&noiroTarget=")
                    && !option.language.equals(SubtitleSelection.normalize(sub.optString("lang")))) continue;
            option.jobUrl=sub.getString("url");
            return option.jobUrl;
        }
        throw new AccountHttp.Failure("no_subtitles_found");
    }
    static boolean noiroJob(String url){
        try{return "noiro.tv".equals(URI.create(url).getHost()) && URI.create(url).getPath().matches("/addons/subtitles/[a-f0-9]{64}/jobs/[a-f0-9]{64}/complete\\.vtt");}catch(Exception invalid){return false;}
    }
    static String text(String url,String language)throws Exception {
        for(int redirect=0;redirect<5;redirect++){
            if(!StreamPolicy.web(url,true))throw new IOException("unsupported_subtitle_url");
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);
            try{
                int status=c.getResponseCode();if(status>=300&&status<400){url=new URL(new URL(url),c.getHeaderField("Location")).toString();continue;}
                if(status!=200)throw new IOException("subtitle_unavailable");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(out.size()+count>2*1024*1024)throw new IOException("subtitle_too_large");out.write(buffer,0,count);}
                    return SubtitleText.decode(out.toByteArray(), c.getContentType(), language);
                }
            }finally{c.disconnect();}
        }throw new IOException("subtitle_redirect_limit");
    }
}
