package cn.wenjin.admission.web;

import cn.wenjin.admission.auth.AuthService;import cn.wenjin.admission.config.AppProperties;import jakarta.servlet.http.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.http.ResponseCookie;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.time.Duration;import java.util.Map;
@RestController @RequestMapping("/api/v1/auth") public class AuthController {private final AuthService auth;private final AppProperties p;private final RateLimiter rate;public AuthController(AuthService a,AppProperties p,RateLimiter r){auth=a;this.p=p;rate=r;}
 @PostMapping("/anonymous")Object anon(HttpServletResponse res,HttpServletRequest req){rate.check("auth:"+req.getRemoteAddr(),20,60);return send(auth.anonymous(),res);}
 @PostMapping("/register")Object register(@Valid @RequestBody Register x,HttpServletResponse res,HttpServletRequest req){rate.check("auth:"+req.getRemoteAddr(),10,60);return send(auth.register(x.email,x.password,x.displayName),res);}
 @PostMapping("/login")Object login(@Valid @RequestBody Login x,HttpServletResponse res,HttpServletRequest req){rate.check("auth:"+req.getRemoteAddr(),8,60);return send(auth.login(x.email,x.password),res);}
 @PostMapping("/logout")Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res){auth.logout(cookie(req));res.addHeader("Set-Cookie",ResponseCookie.from("WENJIN_SESSION","").httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());return Map.of("ok",true);}
 @GetMapping("/me")Object me(Authentication a){return WebSupport.user(a).publicView();}
 @DeleteMapping("/me")Map<String,Object> delete(Authentication a){auth.deleteAccount(WebSupport.user(a).id());return Map.of("deleted",true);}
 private Object send(AuthService.SessionResult x,HttpServletResponse res){res.addHeader("Set-Cookie",ResponseCookie.from("WENJIN_SESSION",x.rawToken()).httpOnly(true).secure(p.auth().secureCookie()).sameSite("Lax").path("/").maxAge(Duration.ofHours(p.auth().sessionHours())).build().toString());return Map.of("user",x.user().publicView(),"expiresAt",x.expiresAt());}
 private static String cookie(HttpServletRequest r){if(r.getCookies()!=null)for(Cookie c:r.getCookies())if(c.getName().equals("WENJIN_SESSION"))return c.getValue();return null;}
 record Register(@Email String email,@Size(min=8,max=72)String password,@NotBlank @Size(max=80)String displayName){}record Login(@Email String email,@NotBlank String password){}
}
