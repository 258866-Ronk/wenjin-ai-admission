package cn.wenjin.admission.web;

import org.springframework.stereotype.Component;
import java.time.Instant;import java.util.concurrent.ConcurrentHashMap;
@Component public class RateLimiter {private final ConcurrentHashMap<String,Window>w=new ConcurrentHashMap<>();public void check(String key,int limit,long seconds){long now=Instant.now().getEpochSecond();w.compute(key,(k,v)->v==null||now-v.started>=seconds?new Window(now,1):v.count>=limit?fail():new Window(v.started,v.count+1));}private static Window fail(){throw new IllegalArgumentException("请求过于频繁，请稍后再试");}private record Window(long started,int count){}}

