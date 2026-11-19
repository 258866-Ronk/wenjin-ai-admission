package cn.wenjin.admission.web;

import cn.wenjin.admission.auth.AuthService.User;
import org.springframework.security.core.Authentication;

public final class WebSupport {private WebSupport(){}public static User user(Authentication a){if(a==null||!(a.getPrincipal() instanceof User u))throw new IllegalArgumentException("请先登录");return u;}}

