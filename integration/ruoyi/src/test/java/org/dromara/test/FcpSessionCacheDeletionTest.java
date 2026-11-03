package org.dromara.test;

import cn.dev33.satoken.dao.SaTokenDao;
import org.dromara.DromaraApplication;
import org.dromara.common.satoken.core.dao.PlusSaTokenDao;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RedissonClient;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.dromara.test.fixture.PinnedOriginalSessionDao;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual pinned RuoYi Spring context and namespaced Redis. No Redis or cache doubles. */
@Tag("prod")
@ActiveProfiles({"prod","fcp"})
@SpringBootTest(classes=DromaraApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FcpSessionCacheDeletionTest {
    @Autowired SaTokenDao dao;
    @Autowired RedissonClient redis;
    @Value("${FCP_REDIS_PREFIX}") String prefix;
    private String key(){
        assertTrue(prefix.matches("fcp_accept_[a-f0-9]{32}"),"Fresh task namespace required");
        String key="fcp:session-delete-fixture:"+UUID.randomUUID();
        assertTrue(redis.getConfig().useSingleServer().getNameMapper().map(key).startsWith(prefix),"Fixture must use the actual task key mapper");
        return key;
    }
    @Test void deletedStringCannotBeReadFromLocalCache() {
        assertInstanceOf(PlusSaTokenDao.class,dao);
        String key=key();
        try {
            dao.set(key,"synthetic-value",30);
            assertEquals("synthetic-value",dao.get(key));
            dao.delete(key);
            assertNull(dao.get(key),"A completed native deletion must immediately invalidate its cached value");
        } finally {dao.delete(key);}
    }
    @Test void deletedObjectCannotBeReadFromLocalCache() {
        String key=key();Map<String,String> value=new HashMap<>();value.put("fixture","synthetic-object");
        try {
            dao.setObject(key,value,30);
            assertEquals(value,dao.getObject(key));
            dao.deleteObject(key);
            assertNull(dao.getObject(key),"A completed native object deletion must immediately invalidate its cached value");
        } finally {dao.deleteObject(key);}
    }
    @Test void typedReadAlsoObservesCompletedObjectDeletion() {
        String key=key();Map<String,String> value=new HashMap<>();value.put("fixture","synthetic-typed-object");
        try {
            dao.setObject(key,value,30);
            assertEquals(value,dao.getObject(key,Map.class));
            dao.deleteObject(key);
            assertNull(dao.getObject(key,Map.class),"Typed session readers must not retain a revoked object");
        } finally {dao.deleteObject(key);}
    }
    @Test void lostDeletionAcknowledgementCannotLeaveStaleLocalSession() {
        String key=key();Map<String,String> value=new HashMap<>();value.put("fixture","synthetic-lost-ack");
        try {
            dao.setObject(key,value,30);assertEquals(value,dao.getObject(key));
            // Actual Redis deletion, then an explicit acknowledgement-loss double.
            // Only this test thread's single delete call is intercepted.
            try (MockedStatic<RedisUtils> fault=mockStatic(RedisUtils.class)) {
                fault.when(()->RedisUtils.deleteObject(anyString())).thenAnswer(call->{
                    assertTrue(redis.getBucket(call.getArgument(0,String.class)).delete());
                    throw new IllegalStateException("Synthetic deletion acknowledgement lost");
                });
                assertThrows(IllegalStateException.class,()->dao.deleteObject(key));
            }
            assertNull(dao.getObject(key),"Deletion failure must propagate and evict stale local state");
        } finally {dao.deleteObject(key);}
    }
    private Object concurrentRefill(SaTokenDao tested)throws Exception {
        String key=key();Map<String,String> value=new HashMap<>();value.put("fixture","actual-redis-late-refill");
        CountDownLatch readCaptured=new CountDownLatch(1),resumeRead=new CountDownLatch(1),remoteDeleted=new CountDownLatch(1);
        AtomicReference<String> rawKey=new AtomicReference<>();ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            tested.setObject(key,value,30);
            Future<Object> read=workers.submit(()->{
                try(MockedStatic<RedisUtils> timing=mockStatic(RedisUtils.class)) {
                    timing.when(()->RedisUtils.getCacheObject(anyString())).thenAnswer(call->{
                        String actual=call.getArgument(0,String.class);rawKey.set(actual);
                        Object captured=redis.getBucket(actual).get();assertEquals(value,captured);
                        readCaptured.countDown();assertTrue(resumeRead.await(10,TimeUnit.SECONDS));return captured;
                    });
                    return tested.getObject(key);
                }
            });
            assertTrue(readCaptured.await(10,TimeUnit.SECONDS));
            Future<?> deletion=workers.submit(()->{
                try(MockedStatic<RedisUtils> timing=mockStatic(RedisUtils.class)) {
                    timing.when(()->RedisUtils.deleteObject(anyString())).thenAnswer(call->{
                        String actual=call.getArgument(0,String.class);assertEquals(rawKey.get(),actual);
                        boolean removed=redis.getBucket(actual).delete();assertTrue(removed);
                        remoteDeleted.countDown();return removed;
                    });
                    tested.deleteObject(key);
                }
            });
            assertTrue(remoteDeleted.await(10,TimeUnit.SECONDS));
            assertFalse(redis.getBucket(rawKey.get()).isExists(),"Actual Redis deletion must precede delayed local refill");
            resumeRead.countDown();assertEquals(value,read.get(10,TimeUnit.SECONDS));deletion.get(10,TimeUnit.SECONDS);
            return tested.getObject(key);
        } finally {
            resumeRead.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));tested.deleteObject(key);
        }
    }
    @Test void completedDeletionWinsAgainstConcurrentLateCacheRefill()throws Exception {
        assertNotNull(concurrentRefill(new PinnedOriginalSessionDao()),"Pinned old implementation must reproduce stale refill");
        assertNull(concurrentRefill(dao),"Fixed native deletion must leave no stale cached session after both operations finish");
    }
}
