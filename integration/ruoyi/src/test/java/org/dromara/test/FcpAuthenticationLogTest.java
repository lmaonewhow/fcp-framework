package org.dromara.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotLoginException;
import org.dromara.common.satoken.handler.SaTokenExceptionHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

@Tag("prod")
class FcpAuthenticationLogTest {
    @Test
    void frozenSessionMessageCannotLeakTokenAndStillReturns401() {
        String sentinel="synthetic-private-session-canary";
        var exception=new NotLoginException("Frozen token: "+sentinel,"login",NotLoginException.TOKEN_FREEZE);
        assertTrue(exception.getMessage().contains(sentinel),"Actual framework exception must contain the canary before testing redaction");
        Logger logger=(Logger)LoggerFactory.getLogger(SaTokenExceptionHandler.class);
        boolean additive=logger.isAdditive();Level level=logger.getLevel();
        ListAppender<ILoggingEvent> capture=new ListAppender<>();capture.setContext(logger.getLoggerContext());capture.start();
        logger.setAdditive(false);logger.setLevel(Level.TRACE);logger.addAppender(capture);
        try {
            var response=new SaTokenExceptionHandler().handleNotLoginException(exception,new MockHttpServletRequest("GET","/fcp/school/bootstrap"));
            assertEquals(401,response.getCode());
            assertFalse(response.toString().contains(sentinel));
            assertEquals(1,capture.list.size());
            var event=capture.list.get(0);
            assertFalse(event.getFormattedMessage().contains(sentinel));
            assertTrue(event.getFormattedMessage().contains("NotLoginException"));
            assertNull(event.getThrowableProxy(),"Do not attach the exception as a throwable");
        } finally {
            logger.detachAppender(capture);logger.setLevel(level);logger.setAdditive(additive);capture.stop();
        }
    }
}
