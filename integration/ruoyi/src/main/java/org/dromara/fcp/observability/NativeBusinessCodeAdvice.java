package org.dromara.fcp.observability;

import org.dromara.common.core.domain.R;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Reads only native R's integer status before serialization/encryption. No body buffering,
 * message/data access, response mutation, or guessing arbitrary DTO/Map semantics.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class NativeBusinessCodeAdvice implements ResponseBodyAdvice<Object> {
    public static final String ATTRIBUTE=NativeBusinessCodeAdvice.class.getName()+".code";
    @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter) {
        return true; // Runtime type also covers ResponseEntity<R<?>> and exception-handler responses.
    }
    @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType mediaType,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        if(request instanceof ServletServerHttpRequest servlet) {
            if(body instanceof R<?> nativeResponse)servlet.getServletRequest().setAttribute(ATTRIBUTE,nativeResponse.getCode());
            else servlet.getServletRequest().removeAttribute(ATTRIBUTE);
        }
        return body;
    }
}
