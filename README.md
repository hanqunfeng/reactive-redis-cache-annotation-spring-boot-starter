# reactive-redis-cache-annotation-spring-boot-starter
reactive-redis-cache-annotation-spring-boot-starter

# reactive-redis-cache-annotation-spring-boot-starter----响应式Redis方法缓存注解，starter
## 说明
本项目是为webflux项目提供方法缓存的支持，只支持响应式方法，即方法返回类型必须是Mono或者Flux。

## 使用方法
### 加入依赖
```maven
<dependency>
  <groupId>com.hanqunfeng</groupId>
  <artifactId>reactive-redis-cache-annotation-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```gradel
implementation 'com.hanqunfeng:reactive-redis-cache-annotation-spring-boot-starter:1.0.0'
```


### 其它依赖
```gradle
    //webflux
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    
    //Spring Boot Redis 依赖，或者spring-boot-starter-data-redis-reactive
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'

    //aop 面向方面编程 支持@Aspect
    implementation 'org.springframework.boot:spring-boot-starter-aop'
```

### 如果使用时没有创建RedisTemplate,项目中提供了一个,基于jackson序列化，支持jdk8的LocalDate和LocalDateTime
```java
    @Bean
    @ConditionalOnMissingBean(value = RedisTemplate.class)
    public RedisTemplate<String, Object> redisTemplate() {

        log.debug("ReactiveRedisConfig RedisTemplate");
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        //objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
        objectMapper.activateDefaultTyping(objectMapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.WRAPPER_ARRAY);

        //LocalDateTime系列序列化和反序列化模块，继承自jsr310，我们在这里修改了日期格式
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        //序列化
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(
                DateTimeFormatter.ofPattern(DEFAULT_DATE_TIME_FORMAT)));
        javaTimeModule.addSerializer(LocalDate.class,
                new LocalDateSerializer(DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT)));
        javaTimeModule.addSerializer(LocalTime.class,
                new LocalTimeSerializer(DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT)));
        //反序列化
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(
                DateTimeFormatter.ofPattern(DEFAULT_DATE_TIME_FORMAT)));
        javaTimeModule.addDeserializer(LocalDate.class,
                new LocalDateDeserializer(DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT)));
        javaTimeModule.addDeserializer(LocalTime.class,
                new LocalTimeDeserializer(DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT)));

        //注册模块
        objectMapper.registerModule(javaTimeModule);

        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        serializer.setObjectMapper(objectMapper);

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(serializer);
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(serializer);
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }

```
### 示例
使用方式与springboot提供的Cacheable，CachePut，CacheEvict和Caching类似。
```java
@Service
public class SysUserService{
    @Autowired
    SysUserRepository sysUserRepository;
    
    //方法缓存，key支持EL表达式
    @ReactiveRedisCacheable(cacheName = "sysuser", key = "'find_' + #username")
    public Mono<SysUser> findUserByUsername(String username) {
        return sysUserRepository.findByUsername(username);
    }

    @ReactiveRedisCacheable(cacheName = "sysuser", key = "all")
    public Flux<SysUser> findAll() {
        return sysUserRepository.findAll();
    }

    //清除以"sysuser_"开头的全部key
    @ReactiveRedisCacheEvict(cacheName = "sysuser", allEntries = true)
    public Mono<SysUser> add(SysUser sysUser) {
        return sysUserRepository.addSysUser(sysUser.getId(), sysUser.getUsername(), sysUser.getPassword(), sysUser.getEnable()).flatMap(data -> sysUserRepository.findById(sysUser.getId()));
    }

    //组合
    @ReactiveRedisCaching(
            evict = {@ReactiveRedisCacheEvict(cacheName = "sysuser", key = "all")},
            put = {@ReactiveRedisCachePut(cacheName = "sysuser", key = "'find_' + #sysUser.username")}
    )
    public Mono<SysUser> update(SysUser sysUser) {
        Mono<SysUser> save = sysUserRepository.save(sysUser);
        return save;
    }

    @ReactiveRedisCacheEvict(cacheName = "sysuser", allEntries = true)
    @Transactional(rollbackFor = {Throwable.class})
    public Mono<Boolean> deleteByUserName(String username) {
        return sysUserRepository.deleteByUsername(username);
    }
}
```



