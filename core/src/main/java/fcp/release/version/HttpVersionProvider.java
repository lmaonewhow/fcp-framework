package fcp.release.version;

import fcp.FcpException;
import fcp.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;

/** FCP Version Provider HTTP v1 client. Endpoint is trusted deployment configuration, never user input. */
public final class HttpVersionProvider implements VersionProvider {
    private static final int LIMIT=1024*1024;
    private final String id;private final URI base;private final boolean writable;private final Duration timeout;
    private final HttpClient client;
    public HttpVersionProvider(String id,URI base,boolean writable,Duration timeout,boolean allowLoopbackHttp){
        VersionCodec.id(id);Objects.requireNonNull(base);Objects.requireNonNull(timeout);
        String host=base.getHost();
        if(host==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!base.getPath().endsWith("/")||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(60))>0)throw new IllegalArgumentException("Invalid provider endpoint or timeout");
        boolean loopback=Set.of("127.0.0.1","[::1]","::1").contains(host);
        if(!"https".equals(base.getScheme())&&!(allowLoopbackHttp&&loopback&&"http".equals(base.getScheme())))throw new IllegalArgumentException("Provider requires HTTPS");
        this.id=id;this.base=base;this.writable=writable;this.timeout=timeout;
        client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public String id(){return id;}
    public Set<Capability> capabilities(){return writable?Set.of(Capability.READ,Capability.REGISTER_IMMUTABLE):Set.of(Capability.READ);}
    public SystemArtifact registerArtifact(SystemArtifact a){SystemArtifact result=decodeArtifact(put("artifacts/"+a.id(),VersionCodec.artifact(a)));if(!result.equals(a))throw protocol();return result;}
    public SystemRelease registerRelease(SystemRelease r){SystemRelease result=decodeRelease(put("releases/"+r.id(),VersionCodec.release(r)));if(!result.equals(r))throw protocol();return result;}
    public Optional<SystemArtifact> artifact(String id){VersionCodec.id(id);return request("GET","artifacts/"+id,null,true).map(HttpVersionProvider::decodeArtifact).map(a->{if(!a.id().equals(id))throw protocol();return a;});}
    public Optional<SystemRelease> release(String id){VersionCodec.id(id);return request("GET","releases/"+id,null,true).map(HttpVersionProvider::decodeRelease).map(r->{if(!r.id().equals(id))throw protocol();return r;});}
    public List<SystemArtifact> artifacts(){return list("artifacts").stream().map(HttpVersionProvider::decodeArtifact).toList();}
    public List<SystemRelease> releases(){return list("releases").stream().map(HttpVersionProvider::decodeRelease).toList();}
    private Object put(String path,Object value){if(!writable)throw new FcpException("VERSION_UNSUPPORTED","Provider is read only");return request("PUT",path,value,false).orElseThrow();}
    private List<?> list(String path){Object value=request("GET",path,null,false).orElseThrow();if(!(value instanceof List<?> l)||l.size()>10000)throw protocol();return l;}
    private Optional<Object> request(String method,String path,Object body,boolean missing){
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Accept","application/json").header("X-FCP-Version-Protocol","1");
        if(body==null)builder.GET();else{byte[] bytes=VersionCodec.canonical(body).getBytes(StandardCharsets.UTF_8);if(bytes.length>LIMIT)throw protocol();builder.header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));}
        CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(builder.build(),info->new BoundedBody());
        try {
            HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            int status=response.statusCode();
            if(status==404&&missing)return Optional.empty();
            if(status==409)throw new FcpException("VERSION_CONFLICT","External immutable version conflict");
            if(status!=200&&!(method.equals("PUT")&&status==201))throw new FcpException("VERSION_REMOTE_STATUS","External version provider rejected request: "+status);
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw protocol();
            Object value=Json.parse(new String(response.body(),StandardCharsets.UTF_8));if(value==null)throw protocol();return Optional.of(value);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new FcpException("VERSION_REMOTE_UNCERTAIN","External operation interrupted; reconcile before retry",e);}
        catch(TimeoutException|ExecutionException e){throw new FcpException("VERSION_REMOTE_UNCERTAIN","External operation failed; reconcile before retry",e);}
        catch(IllegalArgumentException e){throw new FcpException("VERSION_REMOTE_PROTOCOL","Invalid external version document",e);}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static SystemArtifact decodeArtifact(Object value){try{return VersionCodec.artifact(value);}catch(IllegalArgumentException e){throw protocol();}}
    private static SystemRelease decodeRelease(Object value){try{return VersionCodec.release(value);}catch(IllegalArgumentException e){throw protocol();}}
    private static FcpException protocol(){return new FcpException("VERSION_REMOTE_PROTOCOL","Invalid external version response");}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream out=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer buffer:buffers){if(buffer.remaining()>LIMIT-out.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Response size limit"));return;}byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);out.writeBytes(bytes);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(out.toByteArray());}
    }
}
