package org.dromara.fcp.education;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Covers native login/permission failures that occur before the roster controller executes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RosterNoStoreFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        boolean roster=path.equals("/fcp/education/roster")||path.startsWith("/fcp/education/roster/");
        if(roster)response.setHeader("Cache-Control","no-store");
        try{chain.doFilter(request,response);}
        finally{if(roster&&!response.isCommitted())response.setHeader("Cache-Control","no-store");}
    }
}
