# reactive-redis-cache-annotation-spring-boot-starter----响应式Redis方法缓存注解，starter
## 摘要
* 通过本文，你将知道如何在WebFlux项目中通过redis注解缓存方法的返回值
* 本项目最新的2.0.0版本是基于springboot:2.17.15，jdk1.8，并使用Maven构建，可以在springboot2.17+(包含3.x+)的项目中使用。
* 代码地址:[https://github.com/hanqunfeng/reactive-redis-cache-annotation-spring-boot-starter](https://github.com/hanqunfeng/reactive-redis-cache-annotation-spring-boot-starter)

<!--more-->
## 前言
最近在使用WebFlux时发现，SpringBoot提供的@Cacheable，@CachePut，@CacheEvict和@Caching注解不支持响应式方法，SpringBoot官方也没有提供响应式方法的缓存注解，看到网上的一些解决方案都是直接在方法代码中加入缓存数据的代码逻辑，这样虽然可以解决问题，但是代码侵入并不优雅，于是萌生自己写一个基于redis的响应式方法缓存注解的想法，本项目参考SpringBoot提供的@Cacheable，@CachePut，@CacheEvict和@Caching注解声明，但是只是实现了一些基本功能，可以满足绝大部分使用场景的要求，因为SpringBoot早晚会给出官方解决方案，在此之前，不妨一试。

## 使用示例
* 本项目已经发布到maven中央仓库，直接在项目中添加依赖即可。
* 1.1.0及以下版本是基于springboot:2.4.0构建，可以在springboot2.0+的项目中使用。
* 2.x.x及以上版本是基于springboot:2.17.15构建，可以在springboot2.17+(包含3.x+)的项目中使用。
* maven依赖
```xml
<dependency>
  <groupId>com.hanqunfeng</groupId>
  <artifactId>reactive-redis-cache-annotation-spring-boot-starter</artifactId>
  <version>{latest-version}</version>
</dependency>
```

* gradle依赖
```groovy
implementation 'com.hanqunfeng:reactive-redis-cache-annotation-spring-boot-starter:{latest-version}'
```

* 此时项目中可能还要添加其它依赖，以gradle举例
```groovy
//webflux，非必须，主要是面向响应式编程的，所以使用springboot大概率会使用webflux
implementation 'org.springframework.boot:spring-boot-starter-webflux'

//Spring Boot Redis 依赖，或者spring-boot-starter-data-redis-reactive，任选其一即可，注意要在配置文件中加入redis的配置
implementation 'org.springframework.boot:spring-boot-starter-data-redis'

//redis lettuce连接池依赖，也可以使用jedis连接池，非必须，正式环境建议开启连接池
implementation 'org.apache.commons:commons-pool2'

//aop 面向方面编程 支持@Aspect，非必须
implementation 'org.springframework.boot:spring-boot-starter-aop'
```


* 方法返回值必须是Mono或者Flux类型，使用方式与springboot提供的Cacheable等注解类似
```java
    /**
    * 缓存 cacheName和key支持EL表达式，实际key的名称是"cacheName:key"
    * 缓存结果
    * key:sys-user:find_lisi
    * value:
    * [
    * "com.example.model.SysUser"
    * {
    *    id:"5c74a4e4-c4f2-4570-8735-761d7a570d36"
    *    username:"lisi"
    *    password:"$2a$10$PXoGXLwg05.5YO.QtZa46ONypBmiK59yfefvO1OGO8kYFwzOB.Os6"
    *    enable:true
    * }
    * ]
    */
    @ReactiveRedisCacheable(cacheName = "sys-user", key = "'find_' + #username")
    public Mono<SysUser> findUserByUsername(String username) {
        return sysUserRepository.findByUsername(username);
    }

    /**
     * 2.0.6 版本新增了cacheNull参数，是否缓存空值，默认 true，此时可以缓存Mono中为null和Flux中为empty的值
     * 2.0.6 版本新增了cacheNullTimeout参数，缓存空值的过期时间，单位秒，默认600秒，0或负数时使用 timeout 的设置时间
     */
    @ReactiveRedisCacheable(cacheName = "sys-user", key = "'find_' + #username", cacheNull = true, cacheNullTimeout = 300)
    public Mono<SysUser> findUserByUsername(String username) {
        return sysUserRepository.findByUsername(username);
    }

    /**
     * 2.0.6 版本新增了timeout参数，缓存过期时间，单位秒，默认24小时，0或负数表示不过期
     */
    @ReactiveRedisCacheable(cacheName = "sys-user", key = "all", timeout = -1)
    public Flux<SysUser> findAll() {
        return sysUserRepository.findAll();
    }
    

    /**
    * 删除缓存，allEntries = true 表示删除全部以"cacheName:"开头的缓存
    * allEntries 默认false，此时需要指定key的值，表示删除指定的"cacheName:key"
    */
    @ReactiveRedisCacheEvict(cacheName = "sys-user", allEntries = true)
    public Mono<SysUser> add(SysUser sysUser) {
        return sysUserRepository.addSysUser(sysUser.getId(), sysUser.getUsername(), sysUser.getPassword(), sysUser.getEnable()).flatMap(data -> sysUserRepository.findById(sysUser.getId()));
    }

    /**
    * 组合注解，用法与@Caching类似
    * 规则：
    * 1.cacheables不能与cacheEvicts或者cachePuts同时存在，因为后者一定会执行方法主体，达不到调用缓存的目的，所以当cacheables存在时，后者即便指定也不执行
    * 2.先执行cacheEvicts，再执行cachePuts
    */
    @ReactiveRedisCaching(
            evict = {@ReactiveRedisCacheEvict(cacheName = "sys-user", key = "all")},
            put = {@ReactiveRedisCachePut(cacheName = "sys-user", key = "'find_' + #sysUser.username")}
    )
    public Mono<SysUser> update(SysUser sysUser) {
        Mono<SysUser> save = sysUserRepository.save(sysUser);
        return save;
    }

    /**
    * 删除指定的"cacheName:key"
    */
    @ReactiveRedisCacheEvict(cacheName = "sys-user", key="'find_' + #username")
    public Mono<Boolean> deleteByUserName(String username) {
        return sysUserRepository.deleteByUsername(username);
    }

```

### RedisTemplate
* 如果使用时没有创建RedisTemplate，本项目中提供了一个默认的RedisTemplate<String, Object>，基于jackson序列化，支持jdk8的LocalDate和LocalDateTime
* 同时默认提供了一个ReactiveRedisTemplate<String, Object>，基于jackson序列化，支持jdk8的LocalDate和LocalDateTime
* 具体请参考源码`com.hanqunfeng.reactive.redis.cache.config.ReactiveRedisConfig`
```java
@Bean
@ConditionalOnMissingBean(value = ReactiveRedisTemplate.class)
public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(ReactiveRedisConnectionFactory redisConnectionFactory) {
    log.debug("开启 ReactiveRedisTemplate<String, Object>");
    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
    jackson2JsonRedisSerializer.setObjectMapper(jsonMapper());

    RedisSerializationContext<String, Object> context = RedisSerializationContext.<String, Object>newSerializationContext()
            .key(stringSerializer)
            .value(jackson2JsonRedisSerializer)
            .hashKey(stringSerializer)
            .hashValue(jackson2JsonRedisSerializer)
            .build();

    return new ReactiveRedisTemplate<>(redisConnectionFactory, context);
}

@Bean
@ConditionalOnMissingBean(value = RedisTemplate.class)
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {

    log.debug("开启 RedisTemplate<String, Object>");

    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
    jackson2JsonRedisSerializer.setObjectMapper(jsonMapper());

    RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
    redisTemplate.setConnectionFactory(redisConnectionFactory);
    redisTemplate.setKeySerializer(stringSerializer);
    redisTemplate.setValueSerializer(jackson2JsonRedisSerializer);
    redisTemplate.setHashKeySerializer(stringSerializer);
    redisTemplate.setHashValueSerializer(jackson2JsonRedisSerializer);
    redisTemplate.afterPropertiesSet();

    return redisTemplate;
}

```

### 实例代码：
* [接近实战的WebFlux+MySQL+Redis实战](https://github.com/hanqunfeng/springbootchapter/tree/master/springboot3-demo/web-flux-mysql-redis-demo)
* [极简示例](https://github.com/hanqunfeng/springbootchapter/tree/master/springboot3-demo/reactive-redis-cache-annotation-demo)
### 开启debug日志
```yaml
logging:
  level:
    com.hanqunfeng.reactive.redis.cache: DEBUG
```



