package com.hanqunfeng.reactive.redis.cache.aop;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <h1>redis缓存aop</h1>
 * Created by hanqf on 2020/11/21 16:16.
 */

@Component
//标识是一个Aspect代理类
@Aspect
//如果有多个切面拦截相同的切点，可以用@Order指定执行顺序
//@Order(1)
@Slf4j
public class ReactiveRedisCacheAspect {

    @Autowired
    private RedisTemplate redisTemplate;

    @Pointcut("@annotation(com.hanqunfeng.reactive.redis.cache.aop.ReactiveRedisCacheable)")
    public void cacheablePointCut() {
    }

    @Pointcut("@annotation(com.hanqunfeng.reactive.redis.cache.aop.ReactiveRedisCacheEvict)")
    public void cacheEvictPointCut() {
    }

    @Pointcut("@annotation(com.hanqunfeng.reactive.redis.cache.aop.ReactiveRedisCachePut)")
    public void cachePutPointCut() {
    }

    @Pointcut("@annotation(com.hanqunfeng.reactive.redis.cache.aop.ReactiveRedisCaching)")
    public void cachingPointCut() {
    }

    /**
     * 根据key获取缓存数据
     */
    private Object getObjectByKey(String returnTypeName, String redis_key) {
        Object o = redisTemplate.opsForValue().get(redis_key);
        log.debug("The key[{}] exists,method body not executed", redis_key);

        if (returnTypeName.equals("Flux")) {
            return Flux.fromIterable((List) o);
        } else if (returnTypeName.equals("Mono")) {
            return Mono.justOrEmpty(o);
        } else {
            return o;
        }
    }

    /**
     * 缓存list
     */
    private void cacheFlux(List list, String redis_key, long timeout, boolean cacheNull, long cacheNullTimeout) {
        if (list.size() == 0) {
            if (cacheNull) {
                if (cacheNullTimeout > 0) {
                    redisTemplate.opsForValue().set(redis_key, list, cacheNullTimeout, TimeUnit.SECONDS);
                } else {
                    if (timeout > 0) {
                        redisTemplate.opsForValue().set(redis_key, list, timeout, TimeUnit.SECONDS);
                    } else {
                        redisTemplate.opsForValue().set(redis_key, list); // 永不过期
                    }
                }
            }
        } else {
            if (timeout > 0) {
                redisTemplate.opsForValue().set(redis_key, list, timeout, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(redis_key, list); // 永不过期
            }
        }

        log.debug("The key[{}] has been cached", redis_key);
    }

    /**
     * 缓存单个对象
     */
    private void cacheMono(Object obj, String redis_key, long timeout, boolean cacheNull, long cacheNullTimeout) {
        if (obj == null) {
            if (cacheNull) {
                if (cacheNullTimeout > 0) {
                    redisTemplate.opsForValue().set(redis_key, obj, cacheNullTimeout, TimeUnit.SECONDS);
                } else {
                    if (timeout > 0) {
                        redisTemplate.opsForValue().set(redis_key, obj, timeout, TimeUnit.SECONDS);
                    } else {
                        redisTemplate.opsForValue().set(redis_key, obj); // 永不过期
                    }
                }
            }
        } else {
            if (timeout > 0) {
                redisTemplate.opsForValue().set(redis_key, obj, timeout, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(redis_key, obj);
            }
        }
        log.debug("The key[{}] has been cached", redis_key);
    }

    /**
     * 返回对象
     */
    private Object returnObject(Object proceed, String returnTypeName, String redis_key, long timeout, boolean cacheNull, long cacheNullTimeout) {
        if (returnTypeName.equals("Flux")) {
            return ((Flux) proceed).collectList().doOnSuccess(list -> cacheFlux((List) list, redis_key, timeout, cacheNull, cacheNullTimeout)).flatMapMany(list -> Flux.fromIterable((List) list));
        } else if (returnTypeName.equals("Mono")) {
            return ((Mono) proceed).doOnSuccess(obj -> cacheMono(obj, redis_key, timeout, cacheNull, cacheNullTimeout));
        } else {
            return proceed;
        }
    }

    //环绕通知,一般不建议使用，可以通过@Before和@AfterReturning实现
    //但是响应式方法只能通过环绕通知实现aop，因为其它通知会导致不再同一个线程执行
    @Around("cacheablePointCut()")
    public Object cacheableAround(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        log.debug("ReactiveRedisCacheAspect cacheableAround....");

        MethodSignature methodSignature = (MethodSignature) proceedingJoinPoint.getSignature();
        Method method = methodSignature.getMethod();
        String returnTypeName = method.getReturnType().getSimpleName();


        ReactiveRedisCacheable annotation = method.getAnnotation(ReactiveRedisCacheable.class);
        String cacheName = annotation.cacheName();
        String key = annotation.key();
        long timeout = annotation.timeout();
        boolean cacheNull = annotation.cacheNull();
        long cacheNullTimeout = annotation.cacheNullTimeout();

        //转换EL表达式
        cacheName = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, cacheName);
        key = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, key);

        String redis_key = redisKey(cacheName, key);

        boolean hasKey = redisTemplate.hasKey(redis_key);
        if (hasKey) {
            return getObjectByKey(returnTypeName, redis_key);
        } else {
            // 加锁：防止缓存击穿
            synchronized (redis_key.intern()) {
                hasKey = redisTemplate.hasKey(redis_key);
                if (hasKey) {
                    return getObjectByKey(returnTypeName, redis_key);
                } else {
                    log.debug("The key[{}] does not exist,method body executed", redis_key);
                    //实际执行的方法
                    Object proceed = proceedingJoinPoint.proceed();
                    return returnObject(proceed, returnTypeName, redis_key, timeout, cacheNull, cacheNullTimeout);
                }
            }
        }

    }


    @Around("cacheEvictPointCut()")
    public Object cacheEvictAround(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        log.debug("ReactiveRedisCacheAspect cacheEvictAround....");

        MethodSignature methodSignature = (MethodSignature) proceedingJoinPoint.getSignature();
        Method method = methodSignature.getMethod();
        String returnTypeName = method.getReturnType().getSimpleName();

        ReactiveRedisCacheEvict annotation = method.getAnnotation(ReactiveRedisCacheEvict.class);
        String cacheName = annotation.cacheName();
        String key = annotation.key();
        boolean allEntries = annotation.allEntries();
        boolean beforeInvocation = annotation.beforeInvocation();
        String[] keys = annotation.keys();

        //转换EL表达式
        cacheName = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, cacheName);
        key = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, key);
        getKeys(keys, proceedingJoinPoint);

        //执行方法前清除缓存
        if (beforeInvocation) {
            if (keys.length > 0) {
                deleteRedisCache(cacheName, keys);
            } else {
                deleteRedisCache(cacheName, key, allEntries);
            }

            //实际执行的方法
            log.debug("beforeInvocation=[{}],Method body executed", beforeInvocation);
            Object proceed = proceedingJoinPoint.proceed();
            return proceed;
        } else {//成功执行方法后清除缓存

            //实际执行的方法
            Object proceed = proceedingJoinPoint.proceed();
            log.debug("beforeInvocation=[{}],Method body executed", beforeInvocation);
            final String cacheNameTemp = cacheName;
            final String keyTemp = key;

            if (returnTypeName.equals("Flux")) {
                return ((Flux) proceed).collectList().doOnSuccess(list -> {
                    //清除全部缓存
                    if (keys != null && keys.length > 0) {
                        deleteRedisCache(cacheNameTemp, keys);
                    } else {
                        deleteRedisCache(cacheNameTemp, keyTemp, allEntries);
                    }
                }).flatMapMany(list -> Flux.fromIterable((List) list));
            } else if (returnTypeName.equals("Mono")) {
                return ((Mono) proceed).doOnSuccess(obj -> {
                    //清除全部缓存
                    if (keys != null && keys.length > 0) {
                        deleteRedisCache(cacheNameTemp, keys);
                    } else {
                        deleteRedisCache(cacheNameTemp, keyTemp, allEntries);
                    }
                });
            } else {
                return proceed;
            }

        }
    }


    @Around("cachePutPointCut()")
    public Object cachePutAround(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        log.debug("ReactiveRedisCacheAspect cachePutAround....");

        MethodSignature methodSignature = (MethodSignature) proceedingJoinPoint.getSignature();
        Method method = methodSignature.getMethod();
        String returnTypeName = method.getReturnType().getSimpleName();

        ReactiveRedisCachePut annotation = method.getAnnotation(ReactiveRedisCachePut.class);
        String cacheName = annotation.cacheName();
        String key = annotation.key();
        long timeout = annotation.timeout();
        boolean cacheNull = annotation.cacheNull();
        long cacheNullTimeout = annotation.cacheNullTimeout();

        //转换EL表达式
        cacheName = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, cacheName);
        key = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, key);

        String redis_key = redisKey(cacheName, key);

        boolean hasKey = redisTemplate.hasKey(redis_key);
        if (hasKey) {
            deleteRedisCache(redis_key, false);
        }

        //实际执行的方法
        Object proceed = proceedingJoinPoint.proceed();
        return returnObject(proceed, returnTypeName, redis_key, timeout, cacheNull, cacheNullTimeout);
    }

    private boolean isAllKeyHas(List<String> key_list) {
        AtomicBoolean isAllKeyHas = new AtomicBoolean(true);
        key_list.forEach(key -> {
            if (!redisTemplate.hasKey(key)) {
                isAllKeyHas.set(false);
            }
        });
        return isAllKeyHas.get();
    }

    /**
     * 缓存多个key
     */
    private Object cacheables(ReactiveRedisCacheable[] cacheables, String returnTypeName, ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        Map<String, Long> key_map = new HashMap<>();
        Map<String, Boolean> key_cache_null_map = new HashMap<>();
        Map<String, Long> key_cache_null_timeout_map = new HashMap<>();
        List<String> key_list = new ArrayList<>();
        Arrays.stream(cacheables).forEach(cacheable -> {
            String cacheName = cacheable.cacheName();
            String key = cacheable.key();
            long timeout = cacheable.timeout();
            boolean cacheNull = cacheable.cacheNull();
            long cacheNullTimeout = cacheable.cacheNullTimeout();

            //转换EL表达式
            cacheName = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, cacheName);
            key = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, key);

            String redisKey = redisKey(cacheName, key);

            key_map.put(redisKey, timeout);
            key_cache_null_map.put(redisKey, cacheNull);
            key_cache_null_timeout_map.put(redisKey, cacheNullTimeout);
            key_list.add(redisKey);
        });


        //全部key都有值，则直接返回缓存
        String redisKey = key_list.get(0);
        if (isAllKeyHas(key_list)) {
            return getObjectByKey(returnTypeName, redisKey);
        } else {
            // 加锁：防止缓存击穿
            String redis_key_all = redisKey + "_all";
            synchronized (redis_key_all.intern()) {
                if (isAllKeyHas(key_list)) {
                    return getObjectByKey(returnTypeName, redisKey);
                }
                //实际执行的方法
                Object proceed = proceedingJoinPoint.proceed();
                log.debug("The key[{}] does not exist,method body executed", key_list.get(0));

                if (returnTypeName.equals("Flux")) {
                    return ((Flux) proceed).collectList()
                            .doOnSuccess(list -> key_map.forEach((key, val) -> cacheFlux((List) list, key, val, key_cache_null_map.get(key), key_cache_null_timeout_map.get(key))))
                            .flatMapMany(list -> Flux.fromIterable((List) list));
                } else if (returnTypeName.equals("Mono")) {
                    return ((Mono) proceed)
                            .doOnSuccess(obj -> key_map.forEach((key, val) -> cacheMono(obj, key, val, key_cache_null_map.get(key), key_cache_null_timeout_map.get(key))));
                } else {
                    return proceed;
                }
            }
        }
    }

    /**
     * 缓存清除
     */
    private void cacheEvicts(ReactiveRedisCacheEvict[] cacheEvicts, Map<String, Boolean> map, ProceedingJoinPoint proceedingJoinPoint, List<Pair<String, String[]>> list) {
        Arrays.stream(cacheEvicts).forEach(cacheEvict -> {
            String cacheName = cacheEvict.cacheName();
            String key = cacheEvict.key();
            boolean allEntries = cacheEvict.allEntries();
            boolean beforeInvocation = cacheEvict.beforeInvocation();
            String[] keys = cacheEvict.keys();

            //转换EL表达式
            cacheName = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, cacheName);
            key = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, key);
            getKeys(keys, proceedingJoinPoint);

            if (beforeInvocation) { //执行方法前清除缓存
                //清除全部缓存
                if (keys.length > 0) {
                    deleteRedisCache(cacheName, keys);
                } else {
                    deleteRedisCache(cacheName, key, allEntries);
                }
            } else { //成功执行方法后清除缓存，先保存到map中
                if (keys.length > 0) {
                    list.add(Pair.of(cacheName, keys));
                } else {
                    //清除全部缓存
                    if (allEntries) {
                        map.put(cacheName, true);
                    } else {
                        map.put(redisKey(cacheName, key), false);
                    }
                }
            }
        });
    }

    private Object cachePuts(ReactiveRedisCachePut[] cachePuts, String returnTypeName, Object proceed, Map<String, Boolean> map, ProceedingJoinPoint proceedingJoinPoint, List<Pair<String, String[]>> listKeys) {
        Map<String, Long> key_map = new HashMap<>();
        Map<String, Boolean> key_cache_null_map = new HashMap<>();
        Map<String, Long> key_cache_null_timeout_map = new HashMap<>();
        Arrays.stream(cachePuts).forEach(cachePut -> {
            String cacheName = cachePut.cacheName();
            String key = cachePut.key();
            long timeout = cachePut.timeout();
            boolean cacheNull = cachePut.cacheNull();
            long cacheNullTimeout = cachePut.cacheNullTimeout();

            //转换EL表达式
            cacheName = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, cacheName);
            key = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, key);

            String redisKey = redisKey(cacheName, key);

            key_map.put(redisKey, timeout);
            key_cache_null_map.put(redisKey, cacheNull);
            key_cache_null_timeout_map.put(redisKey, cacheNullTimeout);

            boolean hasKey = redisTemplate.hasKey(redisKey);
            if (hasKey) {
                deleteRedisCache(redisKey, false);
            }
        });

        if (returnTypeName.equals("Flux")) {
            return ((Flux) proceed).collectList()
                    .doOnSuccess(list -> {
                        //执行方法后清除缓存
                        if (listKeys.size() > 0) {
                            for (Pair<String, String[]> pair : listKeys) {
                                deleteRedisCache(pair.getLeft(), pair.getRight());
                            }
                        } else {
                            if (map.size() > 0) {
                                map.forEach((key, val) -> deleteRedisCache(key, val));
                            }
                        }
                        key_map.forEach((key, val) -> cacheFlux((List) list, key, val, key_cache_null_map.get(key), key_cache_null_timeout_map.get(key)));
                    })
                    .flatMapMany(list -> Flux.fromIterable((List) list));
        } else if (returnTypeName.equals("Mono")) {
            return ((Mono) proceed)
                    .doOnSuccess(obj -> {
                        //执行方法后清除缓存
                        if (listKeys.size() > 0) {
                            for (Pair<String, String[]> pair : listKeys) {
                                deleteRedisCache(pair.getLeft(), pair.getRight());
                            }
                        } else {
                            if (map.size() > 0) {
                                map.forEach((key, val) -> deleteRedisCache(key, val));
                            }
                        }
                        key_map.forEach((key, val) -> cacheMono(obj, key, val, key_cache_null_map.get(key), key_cache_null_timeout_map.get(key)));
                    });
        } else {
            return proceed;
        }
    }

    @Around("cachingPointCut()")
    public Object cachingAround(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        log.debug("ReactiveRedisCacheAspect cachingAround....");

        MethodSignature methodSignature = (MethodSignature) proceedingJoinPoint.getSignature();
        Method method = methodSignature.getMethod();
        String returnTypeName = method.getReturnType().getSimpleName();

        ReactiveRedisCaching annotation = method.getAnnotation(ReactiveRedisCaching.class);

        ReactiveRedisCacheEvict[] cacheEvicts = annotation.evict();
        ReactiveRedisCachePut[] cachePuts = annotation.put();
        ReactiveRedisCacheable[] cacheables = annotation.cacheable();

        //规则：
        //1.cacheables不能与cacheEvicts或者cachePuts同时存在，因为后者一定会执行方法主体，达不到调用缓存的目的，所以当cacheables存在时，后者即便指定也不执行
        //2.先执行cacheEvicts，再执行cachePuts

        if (cacheables.length > 0) {
            return cacheables(cacheables, returnTypeName, proceedingJoinPoint);
        } else {
            Map<String, Boolean> map = new HashMap<>();
            List<Pair<String, String[]>> listKeys = new ArrayList<>();
            if (cacheEvicts.length > 0) {
                cacheEvicts(cacheEvicts, map, proceedingJoinPoint, listKeys);
            }

            //实际执行的方法
            Object proceed = proceedingJoinPoint.proceed();
            log.debug("Method body executed");

            if (cachePuts.length > 0) {
                return cachePuts(cachePuts, returnTypeName, proceed, map, proceedingJoinPoint, listKeys);
            } else {
                if (returnTypeName.equals("Flux")) {
                    return ((Flux) proceed).collectList().doOnSuccess(list -> {
                        //执行方法后清除缓存
                        if (listKeys.size() > 0) {
                            for (Pair<String, String[]> pair : listKeys) {
                                deleteRedisCache(pair.getLeft(), pair.getRight());
                            }
                        } else {
                            if (map.size() > 0) {
                                map.forEach((key, val) -> deleteRedisCache(key, val));
                            }
                        }
                    }).flatMapMany(list -> Flux.fromIterable((List) list));
                } else if (returnTypeName.equals("Mono")) {
                    return ((Mono) proceed).doOnSuccess(obj -> {
                        //执行方法后清除缓存
                        if (listKeys.size() > 0) {
                            for (Pair<String, String[]> pair : listKeys) {
                                deleteRedisCache(pair.getLeft(), pair.getRight());
                            }
                        } else {
                            if (map.size() > 0) {
                                map.forEach((key, val) -> deleteRedisCache(key, val));
                            }
                        }
                    });
                } else {
                    return proceed;
                }
            }
        }
    }

    private void deleteRedisCache(String key, boolean clearAll) {
        if (clearAll) {
            Set keys = redisTemplate.keys(key + ":*");
            if (!keys.isEmpty()) {
                log.debug("The key[{}:*] has been cleared", key);
                redisTemplate.delete(keys);
            } else {
                log.debug("The key[{}:*] does not exist", key);
            }
        } else {
            if (redisTemplate.hasKey(key)) {
                log.debug("The key[{}] has been cleared", key);
                redisTemplate.delete(key);
            } else {
                log.debug("The key[{}] does not exist", key);
            }
        }
    }

    private void deleteRedisCache(String cacheName, String key, boolean clearAll) {
        String redisKey;
        if (clearAll) {
            redisKey = cacheName;
        } else {
            redisKey = redisKey(cacheName, key);
        }
        deleteRedisCache(redisKey, clearAll);
    }

    private void deleteRedisCache(String cacheName, String[] keys) {
        try {
            for (String k : keys) {
                String redisKey = redisKey(cacheName, k);
                final Set<String> ks = redisTemplate.keys(redisKey);
                for (String keyName : ks) {
                    redisTemplate.delete(keyName);
                }
            }
        } catch (Exception e) {
            log.error("批量清除缓存失败！", e);
        }
    }

    private void getKeys(String[] keys, ProceedingJoinPoint proceedingJoinPoint) {
        for (int i = 0; i < keys.length; i++) {
            keys[i] = (String) AspectSupportUtils.getKeyValue(proceedingJoinPoint, keys[i]);
        }
    }

    /**
     * 拼接缓存key
     */
    private String redisKey(String cacheName, String key) {
        if (!StringUtils.hasText(cacheName)) {
            return key;
        }
        return cacheName + ":" + key;
    }

}
