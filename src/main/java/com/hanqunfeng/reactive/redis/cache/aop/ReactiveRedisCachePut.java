package com.hanqunfeng.reactive.redis.cache.aop;

import java.lang.annotation.*;

/**
 * <h1>执行完方法更新缓存</h1>
 * Created by hanqf on 2020/11/21 23:15.
 */

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReactiveRedisCachePut {

    /**
     * 缓存key，key为cacheName+":"+key
     * 支持EL表达式
     */
    String key();

    /**
     * 缓存key分组，会做为缓存key的前缀+":"
     * 支持EL表达式
     */
    String cacheName() default "";

    /**
     * 缓存过期时间，单位秒，默认24小时，0或负数表示不过期
     */
    long timeout() default 24 * 3600L;

    /**
     * 是否缓存空值，默认 true
     * Mono判断是否为Null
     * Flux判断是否为Empty
     */
    boolean cacheNull() default true;

    /**
     * 缓存空值过期时间，单位秒，默认10分钟，0或负数时使用 timeout 的设置时间
     *
     */
    long cacheNullTimeout() default 600L;
}
