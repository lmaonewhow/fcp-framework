package org.dromara.test;

import org.dromara.common.core.domain.R;
import org.dromara.fcp.observability.NativeBusinessCodeAdvice;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@Tag("prod")
class FcpTraceBusinessCodeTest {
    @Test void readsOnlyNativeIntegerAndDoesNotTransformResponseOrInspectMessageData() {
        var advice=new NativeBusinessCodeAdvice();var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        var input=new ServletServerHttpRequest(request);var output=new ServletServerHttpResponse(response);
        // Throwing getters prove that diagnostic extraction never touches message/data fields.
        R<Object> nativeError=new R<>() {
            @Override public String getMsg() { throw new AssertionError("Message must not be read"); }
            @Override public Object getData() { throw new AssertionError("Data must not be read"); }
        };
        nativeError.setCode(500);
        assertSame(nativeError,advice.beforeBodyWrite(nativeError,null,MediaType.APPLICATION_JSON,null,input,output));
        assertEquals(500,request.getAttribute(NativeBusinessCodeAdvice.ATTRIBUTE));assertEquals(200,response.getStatus());
        nativeError.setCode(601);
        assertSame(nativeError,advice.beforeBodyWrite(nativeError,null,MediaType.APPLICATION_JSON,null,input,output));
        assertEquals(601,request.getAttribute(NativeBusinessCodeAdvice.ATTRIBUTE));
        var arbitrary=Map.of("code",401,"msg","synthetic-private-canary");
        assertSame(arbitrary,advice.beforeBodyWrite(arbitrary,null,MediaType.APPLICATION_JSON,null,input,output));
        assertNull(request.getAttribute(NativeBusinessCodeAdvice.ATTRIBUTE),"Do not guess non-R or retain stale prior dispatch codes");
        assertNull(advice.beforeBodyWrite(null,null,MediaType.APPLICATION_JSON,null,input,output));
        assertNull(request.getAttribute(NativeBusinessCodeAdvice.ATTRIBUTE));
    }
}
