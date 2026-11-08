package fcp.release.routing;

import fcp.*;
import fcp.host.TrustedContext;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

/** Calls only deployer-configured exact endpoints. Host performs operation authorization before calling.
 * No user-supplied URLs/headers, redirects, transparent application retries, or host bearer-token forwarding.
 */
public final class ReleaseHttpTransport {
    public record Endpoint(String releaseId, URI origin, Set<String> paths) {
        public Endpoint {
            releaseId=ReleasePolicy.valid(releaseId);Objects.requireNonNull(origin);paths=Set.copyOf(paths);
            String host=origin.getHost();
            boolean loopback=Set.of("127.0.0.1","[::1]","::1").contains(host==null?"":host);
            if(host==null || origin.getUserInfo()!=null || origin.getFragment()!=null || origin.getQuery()!=null || !(origin.getPath().isEmpty()||origin.getPath().equals("/")) || !(origin.getScheme().equals("https") || origin.getScheme().equals("http")&&loopback))throw new IllegalArgumentException("Endpoint must be configured HTTPS origin or numeric loopback HTTP");
            for(String path:paths)if(!path.matches("/[A-Za-z0-9/_-]*") || path.contains("//"))throw new IllegalArgumentException("Invalid endpoint path");
        }
    }
    public record Response(int status, byte[] body, DurableReleaseRouter.Decision decision) {
        public Response {body=body.clone();}
        @Override public byte[] body(){return body.clone();}
    }
    public record Observation(long requests,long successes,long errors,long businessFailures,long unknown,long totalNanos) {}
    private static final class Counters {final LongAdder requests=new LongAdder(),successes=new LongAdder(),errors=new LongAdder(),business=new LongAdder(),unknown=new LongAdder(),nanos=new LongAdder();}
    private final Map<String,Endpoint> endpoints;
    private final DurableReleaseRouter router;
    private final byte[] identityKey;
    private final Clock clock;
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String,Counters> metrics=new ConcurrentHashMap<>();
    public ReleaseHttpTransport(DurableReleaseRouter router,Collection<Endpoint> endpoints,byte[] identityKey,Clock clock) {
        this.router=Objects.requireNonNull(router);this.identityKey=RoutingCrypto.key(identityKey);this.clock=Objects.requireNonNull(clock);
        Map<String,Endpoint> map=new HashMap<>();for(Endpoint endpoint:endpoints)if(map.putIfAbsent(endpoint.releaseId(),endpoint)!=null)throw new IllegalArgumentException("Duplicate release endpoint");this.endpoints=Map.copyOf(map);
    }
    public Response invoke(TrustedContext context,String affinity,String method,String path,byte[] body) throws IOException,InterruptedException {
        if(!Set.of("GET","POST","PUT","DELETE","PATCH").contains(method) || body==null || body.length>1024*1024 || method.equals("GET")&&body.length!=0)throw new IllegalArgumentException("Invalid request");
        body=body.clone();
        DurableReleaseRouter.Decision decision=affinity==null?router.decide(context):router.resume(context,affinity);
        Endpoint endpoint=endpoints.get(decision.releaseId());if(endpoint==null || !endpoint.paths().contains(path))throw new FcpException("RELEASE_ENDPOINT_UNAVAILABLE","No registered endpoint for this release operation");
        long millis=Math.min(10000,Duration.between(clock.instant(),context.deadline()).toMillis());if(millis<=0)throw new FcpException("CONTEXT_EXPIRED","Trusted context expired");
        Map<String,Object> identity=new LinkedHashMap<>();identity.put("domain","backend-v1");identity.put("release",decision.releaseId());identity.put("tenant",context.tenantId());identity.put("principal",context.principal().id());identity.put("kind",context.principal().kind().name());identity.put("authorization",context.authorizationRevision());identity.put("scopes",new TreeSet<>(context.dataScopes()));identity.put("request",context.requestId());identity.put("trace",context.traceId());identity.put("method",method);identity.put("path",path);identity.put("digest",RoutingCrypto.digest(body));identity.put("expiry",Math.min(context.deadline().toEpochMilli(),clock.instant().plusSeconds(15).toEpochMilli()));
        HttpRequest request=HttpRequest.newBuilder(endpoint.origin().resolve(path)).timeout(Duration.ofMillis(millis)).header("Content-Type","application/json").header("X-FCP-Release-Context",RoutingCrypto.sign(identityKey,Json.write(identity))).method(method,HttpRequest.BodyPublishers.ofByteArray(body)).build();
        Counters counter=metrics.computeIfAbsent(decision.releaseId(),k->new Counters());counter.requests.increment();long start=System.nanoTime();
        try {
            CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request, info->new BoundedBody());
            HttpResponse<byte[]> response;
            try { response=pending.get(millis,TimeUnit.MILLISECONDS); }
            catch(TimeoutException e){pending.cancel(true);throw new IOException("Backend response deadline exceeded",e);}
            catch(ExecutionException e){throw new IOException("Backend request failed",e.getCause());}
            catch(InterruptedException e){pending.cancel(true);throw e;}
            byte[] result=response.body();
            if(result.length>1024*1024 || !response.headers().firstValue("X-FCP-Release").orElse("").equals(decision.releaseId()))throw new IOException("Backend release identity or response limit mismatch");
            if(response.statusCode()>=300 && response.statusCode()<400)throw new IOException("Redirect refused");
            boolean businessFailure=response.headers().firstValue("X-FCP-Business-Outcome").orElse("").equals("failure");
            if(businessFailure)counter.business.increment();
            if(response.statusCode()>=200&&response.statusCode()<300&&!businessFailure)counter.successes.increment();else counter.errors.increment();
            return new Response(response.statusCode(),result,decision);
        }catch(IOException|InterruptedException e){counter.errors.increment();if(!method.equals("GET"))counter.unknown.increment();throw e;}finally{counter.nanos.add(System.nanoTime()-start);}
    }
    /** Receiver verifies signed identity AND binds it to the actual method/path/body and release.
     * Call host authorization and application idempotency after verification; this signature is not authorization.
     */
    public static TrustedContext verifyIdentity(byte[] key,Clock clock,String expectedRelease,String token,String method,String path,byte[] body) {
        Map<String,Object> m=Schema.map(Json.parse(RoutingCrypto.verify(RoutingCrypto.key(key),token)));
        if(!"backend-v1".equals(m.get("domain")) || !expectedRelease.equals(m.get("release")) || !method.equals(m.get("method")) || !path.equals(m.get("path")) || !RoutingCrypto.digest(body).equals(m.get("digest")))throw new FcpException("INVALID_RELEASE_IDENTITY","Backend request identity mismatch");
        Instant expiry=Instant.ofEpochMilli(Schema.number(m.get("expiry")));
        if(!clock.instant().isBefore(expiry)||expiry.isAfter(clock.instant().plusSeconds(15)))throw new FcpException("INVALID_RELEASE_IDENTITY","Backend identity expired");
        Set<String> scopes=new HashSet<>();for(Object s:(List<?>)m.get("scopes"))scopes.add(Schema.str(s));
        return new TrustedContext(Schema.str(m.get("request")),Schema.str(m.get("trace")),Schema.str(m.get("tenant")),new TrustedContext.Principal(TrustedContext.Kind.valueOf(Schema.str(m.get("kind"))),Schema.str(m.get("principal"))),Schema.number(m.get("authorization")),scopes,expiry);
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){
                if(buffer.remaining()>1024*1024-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("Backend response limit exceeded"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    public Map<String,Observation> observations() {
        Map<String,Observation> result=new TreeMap<>();metrics.forEach((id,c)->result.put(id,new Observation(c.requests.sum(),c.successes.sum(),c.errors.sum(),c.business.sum(),c.unknown.sum(),c.nanos.sum())));return Map.copyOf(result);
    }
}
