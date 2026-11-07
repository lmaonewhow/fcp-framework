package fcp.exchange;

import fcp.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Explicit synthetic Campus-v1 test API. Host-owned literal loopback URI, no redirects,
 * credentials or client-selected URL. Production vendor connectors require a separate adapter. */
public final class LoopbackConnector implements ExchangeService.Connector {
    private final URI endpoint;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public LoopbackConnector(URI configuredEndpoint){
        if(!"http".equals(configuredEndpoint.getScheme())||!Set.of("127.0.0.1","[::1]").contains(configuredEndpoint.getHost())||configuredEndpoint.getUserInfo()!=null||configuredEndpoint.getQuery()!=null||configuredEndpoint.getFragment()!=null)throw new IllegalArgumentException("Literal loopback fixture endpoint required");
        endpoint=configuredEndpoint;
    }
    public ExchangeService.Page fetch(String cursor)throws IOException {
        if(cursor.length()>256)throw ExchangeService.fail("CURSOR_LIMIT");
        URI uri=URI.create(endpoint+"?cursor="+URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8));
        try{var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=response.body()){if(response.statusCode()!=200)throw ExchangeService.fail("CONNECTOR_FAILED");var m=Schema.map(Json.parse(ExchangeService.read(in)));if(!m.keySet().equals(Set.of("content","nextCursor")))throw ExchangeService.fail("CONNECTOR_SCHEMA");return new ExchangeService.Page(Schema.str(m.get("content")),m.get("nextCursor")==null?null:Schema.str(m.get("nextCursor")));}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Connector interrupted",e);}
    }
}
