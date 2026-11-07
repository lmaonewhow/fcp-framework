package org.dromara.fcp.observability;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

@Configuration(proxyBeanMethods=false)
public class FcpTraceConfiguration {
    @Bean public org.springframework.boot.web.servlet.ServletListenerRegistrationBean<FcpTraceFilter.CompletionListener> fcpTraceCompletion() {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(new FcpTraceFilter.CompletionListener());
    }
    @Bean public FilterRegistrationBean<FcpTraceFilter> fcpTraceFilter(Environment environment) {
        Set<String> peers=new HashSet<>();
        String configured=environment.getProperty("fcp.trace.trusted-peers","");
        if(!configured.isBlank())for(String value:configured.split(",")) {
            String peer=value.trim();
            if(!peer.matches("[0-9a-fA-F:.]{2,45}"))throw new IllegalArgumentException("Trace peers must be numeric socket addresses");
            peers.add(peer);
        }
        var bean=new FilterRegistrationBean<>(new FcpTraceFilter(peers));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);bean.addUrlPatterns("/*");bean.setAsyncSupported(true);
        bean.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR,DispatcherType.FORWARD);
        return bean;
    }
    // The pinned RuoYi sets spring.task.execution.mode=force. Boot owns the reserved
    // applicationTaskExecutor and its AsyncConfigurer, including pool/virtual-thread settings.
    // Decorate those existing executors below; never declare aliases or replace their sizing.
    @Bean public static BeanPostProcessor fcpTraceExecutors() {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean,String name) {
                if(bean instanceof ThreadPoolTaskExecutor executor)executor.setTaskDecorator(NativeTrace::wrap);
                else if(bean instanceof SimpleAsyncTaskExecutor executor)executor.setTaskDecorator(NativeTrace::wrap);
                return bean;
            }
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(!name.equals("scheduledExecutorService") || !(bean instanceof ScheduledExecutorService))return bean;
                return Proxy.newProxyInstance(bean.getClass().getClassLoader(),new Class<?>[]{ScheduledExecutorService.class},(proxy,method,args)-> {
                    Object[] captured=args==null?null:args.clone();
                    if(captured!=null && captured.length>0) {
                        if(captured[0] instanceof Runnable work) {
                            boolean recurring=method.getName().equals("scheduleAtFixedRate") || method.getName().equals("scheduleWithFixedDelay");
                            captured[0]=recurring?NativeTrace.recurring(work):NativeTrace.wrap(work);
                        } else if(captured[0] instanceof Callable<?> work) captured[0]=NativeTrace.wrap(work);
                        else if(captured[0] instanceof Collection<?> jobs &&
                                (method.getName().equals("invokeAll") || method.getName().equals("invokeAny"))) {
                            var wrapped=new ArrayList<Callable<?>>();
                            for(Object job:jobs)wrapped.add(NativeTrace.wrap((Callable<?>)job));
                            captured[0]=wrapped;
                        }
                    }
                    try { return method.invoke(bean,captured); } catch(InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
    }
}
