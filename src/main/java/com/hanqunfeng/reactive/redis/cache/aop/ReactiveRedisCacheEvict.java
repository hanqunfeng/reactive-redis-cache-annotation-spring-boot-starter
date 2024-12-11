package com.hanqunfeng.reactive.redis.cache.aop;

import java.lang.annotation.*;

/**
 * <h1>redis清除缓存注解</h1>
 * Created by hanqf on 2020/11/21 22:26.
 */

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReactiveRedisCacheEvict {
    /**
     * 缓存key，如果cacheName不为空，则key为cacheName+":"+key
     * 支持EL表达式
     */
    String key() default "";

    /**
     * 缓存key数组，如果cacheName不为空，则key为cacheName+":"+key
     * 与 key 和 allEntries 互斥，优先级更高，即如果设置了 keys，则不会使用 key 和 allEntries
     * 支持EL表达式，支持模糊匹配
     * 通配符说明
     * *：匹配零个或多个字符。
     * 示例：user* 匹配 user1, user2, userName 等。
     * ?：匹配一个字符。
     * 示例：user? 匹配 user1, userA，但不匹配 user123。
     * [abc]：匹配括号中的任意一个字符。
     * 示例：user[12] 匹配 user1, user2，但不匹配 user3。
     * \：转义特殊字符。
     * 示例：\* 匹配键名中的实际星号 *。
     */
    String[] keys() default {};

    /**
     * 缓存key分组，会做为缓存key的前缀+":"
     * 支持EL表达式
     */
    String cacheName() default "";

    /**
     * 是否删除cacheName下全部缓存数据，
     * true时cacheName不能为空，此时即便指定了key值，也会删除cacheName下全部缓存
     * false时key值不能为空
     */
    boolean allEntries() default false;

    /**
     * 调用清除缓存的时机，true:执行方法前，false:执行方法后
     * 如果是false，则方法执行过程中发生异常，则不会清除缓存
     */
    boolean beforeInvocation() default false;
}
