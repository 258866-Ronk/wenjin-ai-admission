package cn.wenjin.admission.web;

import cn.wenjin.admission.domain.Domain.ProfilePatch;import cn.wenjin.admission.profile.ProfileService;import jakarta.validation.Valid;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/profiles") public class ProfileController {private final ProfileService profiles;public ProfileController(ProfileService p){profiles=p;}@GetMapping("/current")Object current(Authentication a){return profiles.current(WebSupport.user(a).id());}@PutMapping("/current")Object put(Authentication a,@Valid @RequestBody ProfilePatch p){return profiles.save(WebSupport.user(a).id(),p,"manual-profile-update");}@PatchMapping("/current")Object patch(Authentication a,@Valid @RequestBody ProfilePatch p){return put(a,p);}@GetMapping("/current/completeness")Object completeness(Authentication a){return profiles.completeness(profiles.current(WebSupport.user(a).id()));}}

