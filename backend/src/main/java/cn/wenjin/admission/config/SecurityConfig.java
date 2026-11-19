package cn.wenjin.admission.config;

import cn.wenjin.admission.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean SecurityFilterChain security(HttpSecurity http,SessionAuthFilter auth,TraceFilter trace) throws Exception {return http.csrf(csrf->csrf.disable()).cors(cors->{}).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).exceptionHandling(e->e.authenticationEntryPoint((request,response,exception)->response.sendError(HttpServletResponse.SC_UNAUTHORIZED))).authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/v3/api-docs/**","/swagger-ui.html","/swagger-ui/**").permitAll().requestMatchers(HttpMethod.POST,"/api/v1/auth/**").permitAll().requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated()).addFilterBefore(trace,UsernamePasswordAuthenticationFilter.class).addFilterAfter(auth,TraceFilter.class).build();}
    @Bean CorsConfigurationSource cors(AppProperties p){CorsConfiguration c=new CorsConfiguration();c.setAllowedOrigins(p.cors().allowedOrigins());c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("Content-Type","Idempotency-Key","Last-Event-ID","X-Trace-Id"));c.setExposedHeaders(List.of("X-Trace-Id"));c.setAllowCredentials(true);c.setMaxAge(3600L);UrlBasedCorsConfigurationSource s=new UrlBasedCorsConfigurationSource();s.registerCorsConfiguration("/**",c);return s;}

    @Component public static class TraceFilter extends OncePerRequestFilter {protected void doFilterInternal(HttpServletRequest r,HttpServletResponse p,FilterChain c)throws ServletException,IOException{String id=r.getHeader("X-Trace-Id");if(id==null||!id.matches("[A-Za-z0-9-]{8,64}"))id=UUID.randomUUID().toString();MDC.put("traceId",id);p.setHeader("X-Trace-Id",id);try{c.doFilter(r,p);}finally{MDC.remove("traceId");}}}
    @Component public static class SessionAuthFilter extends OncePerRequestFilter {private final AuthService auth;public SessionAuthFilter(AuthService auth){this.auth=auth;}@Override protected boolean shouldNotFilterAsyncDispatch(){return false;}protected void doFilterInternal(HttpServletRequest r,HttpServletResponse p,FilterChain c)throws ServletException,IOException{String token=Arrays.stream(r.getCookies()==null?new Cookie[0]:r.getCookies()).filter(x->"WENJIN_SESSION".equals(x.getName())).map(Cookie::getValue).findFirst().orElse(null);auth.authenticate(token).ifPresent(u->SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("ROLE_"+u.role())))));c.doFilter(r,p);}}
}
