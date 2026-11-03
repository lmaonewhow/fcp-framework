package org.dromara.fcp.school;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.PathResourceResolver;
import java.io.IOException;
import java.util.List;

/** Only UI prefixes get a SPA fallback. Missing assets and /fcp APIs never fall back to HTML. */
@Configuration
public class FcpWebConfiguration implements WebMvcConfigurer {
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        // ResourceHttpRequestHandler rejects an empty path before consulting resolvers.
        for(String shell:new String[]{"admin","teacher"}) {
            registry.addViewController("/"+shell).setViewName("forward:/"+shell+"/index.html");
            registry.addViewController("/"+shell+"/").setViewName("forward:/"+shell+"/index.html");
        }
    }
    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        // The pinned host's generic advice otherwise turns missing Spring 6 resources into 500.
        resolvers.add(0,(request,response,handler,failure)->{
            String path=request.getRequestURI();
            if(failure instanceof NoResourceFoundException && (path.startsWith("/admin/") || path.startsWith("/teacher/"))) {
                response.setStatus(404);return new ModelAndView();
            }
            return null;
        });
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String shell : new String[]{"admin","teacher"}) {
            registry.addResourceHandler("/"+shell+"/**")
                .addResourceLocations("classpath:/static/"+shell+"/")
                .setCachePeriod(0).resourceChain(true)
                .addResolver(new SpaResolver());
        }
    }
    static final class SpaResolver extends PathResourceResolver {
        @Override protected Resource getResource(String path, Resource location) throws IOException {
            if (path.contains("..") || path.contains("\\") || path.contains("%") || path.startsWith("/")) return null;
            Resource resource = super.getResource(path,location);
            if (resource != null && resource.isReadable() && !path.isEmpty() && !path.endsWith("/")) return resource;
            // A dot signals an asset request. Missing JS/CSS must return 404, not index.html.
            if (!path.contains(".") && !path.startsWith("assets/")) return super.getResource("index.html",location);
            return null;
        }
    }
}
