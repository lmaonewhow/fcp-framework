package fcp.release.control;

import fcp.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.time.Duration;
import java.util.*;

/** Adapter for FCP Deployment Executor v1, NOT a generic webhook or arbitrary URL proxy.
 * Endpoint and client are deployment-owned. The client must use NEVER redirects and suitable TLS/auth.
 * No user-supplied destinations, commands or credentials are accepted by this adapter.
 */
public final class HttpDeploymentProvider implements DeploymentProvider {
    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;
    private final Set<Action> supported;
    private final boolean safeRecovery, fencedContainment;
    public HttpDeploymentProvider(URI endpoint,HttpClient client,Duration timeout,Set<Action> supported,boolean allowLoopbackHttp) {
        this(endpoint,client,timeout,supported,allowLoopbackHttp,false,false);
    }
    public HttpDeploymentProvider(URI endpoint,HttpClient client,Duration timeout,Set<Action> supported,boolean allowLoopbackHttp,boolean safeRecovery,boolean fencedContainment) {
        this.safeRecovery=safeRecovery;this.fencedContainment=fencedContainment;
        Objects.requireNonNull(endpoint);this.client=Objects.requireNonNull(client);this.timeout=Objects.requireNonNull(timeout);
        boolean loopback=Set.of("127.0.0.1","localhost","[::1]").contains(endpoint.getHost());
        if(endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !endpoint.getPath().endsWith("/") ||
           !("https".equals(endpoint.getScheme()) || (allowLoopbackHttp&&loopback&&"http".equals(endpoint.getScheme()))) || client.followRedirects()!=HttpClient.Redirect.NEVER || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("Unsafe deployment executor configuration");
        this.endpoint=endpoint;this.supported=Set.copyOf(supported);
    }
    @Override public Set<Action> capabilities(){return supported;}
    @Override public boolean supportsRecovery(){return safeRecovery;}
    @Override public boolean supportsFencedContainment(){return fencedContainment;}
    @Override public RecoveryDisposition recovery(Command c)throws IOException {
        if(!safeRecovery)return RecoveryDisposition.UNKNOWN;
        Response r=send(HttpRequest.newBuilder(endpoint.resolve("operations/"+c.operationId()+"/recovery?environment="+c.plan().environment()+"&commandDigest="+c.digest())).timeout(timeout).GET().build());
        if(r.status()==404)return RecoveryDisposition.UNKNOWN;
        if(r.status()!=200)throw new IOException("Executor recovery HTTP "+r.status());
        Map<String,Object> m=parse(r.body());
        if(!m.keySet().equals(Set.of("operationId","commandDigest","disposition")) || !c.operationId().equals(m.get("operationId")) || !c.digest().equals(m.get("commandDigest")))throw new IOException("Recovery identity mismatch");
        try{return RecoveryDisposition.valueOf(Schema.str(m.get("disposition")));}catch(RuntimeException e){throw new IOException("Invalid recovery disposition",e);}
    }
    @Override public void resumeNotAccepted(Command c)throws IOException {if(!safeRecovery)throw new IOException("SAFE_RECOVERY_UNSUPPORTED");submit(c,true);}
    @Override public void submit(Command command)throws IOException {submit(command,false);}
    private void submit(Command command,boolean recovering)throws IOException {
        if(!supported.contains(command.action()))throw new IOException("Unsupported deployment capability");
        HttpRequest request=HttpRequest.newBuilder(endpoint.resolve("operations/"+command.operationId()+"?environment="+command.plan().environment())).timeout(timeout)
                .header("Content-Type","application/json").header("Accept","application/json")
                .header("FCP-Command-Digest",command.digest()).header("FCP-Conditional-Claim",recovering?"not-accepted":"idempotent")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(command.json()),StandardCharsets.UTF_8)).build();
        Response r=send(request);if(r.status()!=200&&r.status()!=201&&r.status()!=202)throw new IOException("Executor submit HTTP "+r.status());
        Map<String,Object> receipt=parse(r.body());if(!receipt.keySet().equals(Set.of("operationId","accepted")) || !command.operationId().equals(receipt.get("operationId")) || !Boolean.TRUE.equals(receipt.get("accepted")))throw new IOException("Invalid executor receipt");
    }
    @Override public Optional<Observation> observe(String environment,String operationId)throws IOException {
        if(!environment.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||!operationId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid operation identity");
        Response r=send(HttpRequest.newBuilder(endpoint.resolve("operations/"+operationId+"?environment="+environment)).timeout(timeout).header("Accept","application/json").GET().build());
        if(r.status()==404)return Optional.empty();if(r.status()!=200)throw new IOException("Executor observation HTTP "+r.status());
        Map<String,Object> m=parse(r.body());if(!m.keySet().equals(Set.of("operationId","complete","healthy","candidateRelease","candidateDigest","basisPoints","generation","inFlight","detail","routingMode","outcome")) || !(m.get("complete") instanceof Boolean)||!(m.get("healthy") instanceof Boolean))throw new IOException("Invalid executor observation schema");
        try{return Optional.of(new Observation(Schema.str(m.get("operationId")),(Boolean)m.get("complete"),(Boolean)m.get("healthy"),Schema.str(m.get("candidateRelease")),Schema.str(m.get("candidateDigest")),Math.toIntExact(Schema.number(m.get("basisPoints"))),Schema.number(m.get("generation")),Schema.number(m.get("inFlight")),Schema.str(m.get("detail")),RoutingMode.valueOf(Schema.str(m.get("routingMode"))),ExecutionOutcome.valueOf(Schema.str(m.get("outcome")))));}catch(RuntimeException e){throw new IOException("Invalid executor observation",e);}
    }
    private record Response(int status,String body){}
    private Response send(HttpRequest request)throws IOException {
        CompletableFuture<HttpResponse<byte[]>> future=client.sendAsync(request,info->new LimitedBody());
        try{HttpResponse<byte[]> r=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            return new Response(r.statusCode(),new String(r.body(),StandardCharsets.UTF_8));
        }catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new IOException("Executor interrupted; outcome may be unknown",e);
        }catch(TimeoutException e){future.cancel(true);throw new IOException("Executor timeout; outcome may be unknown",e);
        }catch(ExecutionException e){throw new IOException("Executor I/O; outcome may be unknown",e.getCause());}
    }
    /** Enforces the byte bound during reception, rather than buffering an unbounded response first. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer b:buffers){if(b.remaining()>65536-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("Executor response limit"));return;}
                byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private Map<String,Object> parse(String text)throws IOException{try{return Schema.map(Json.parse(text));}catch(RuntimeException e){throw new IOException("Invalid executor JSON",e);}}
}
