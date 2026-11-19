package cn.wenjin.admission.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice public class ApiExceptionHandler {
 private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
 @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})ResponseEntity<?> bad(Exception e,HttpServletRequest r){String m=e instanceof MethodArgumentNotValidException v?v.getBindingResult().getFieldErrors().stream().findFirst().map(x->x.getField()+" "+x.getDefaultMessage()).orElse("请求参数错误"):e.getMessage();return response(HttpStatus.BAD_REQUEST,"BAD_REQUEST",m,r);}
 @ExceptionHandler(Exception.class)ResponseEntity<?> error(Exception e,HttpServletRequest r){log.error("Unhandled API error path={} traceId={}",r.getRequestURI(),MDC.get("traceId"),e);return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","服务暂时不可用，请稍后重试",r);}
 private ResponseEntity<?> response(HttpStatus s,String code,String message,HttpServletRequest r){return ResponseEntity.status(s).body(Map.of("timestamp",Instant.now(),"status",s.value(),"code",code,"message",message==null?"请求失败":message,"path",r.getRequestURI(),"traceId",MDC.get("traceId")));}
}
