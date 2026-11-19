package cn.wenjin.admission.web;

import cn.wenjin.admission.common.Jsons;import cn.wenjin.admission.conversation.ConversationRepository;import cn.wenjin.admission.domain.Domain.ProfilePatch;import cn.wenjin.admission.profile.ProfileService;import cn.wenjin.admission.workflow.AdmissionAdvisorWorkflow;import jakarta.validation.Valid;import jakarta.validation.constraints.NotBlank;import jakarta.validation.constraints.Size;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.Map;
@RestController @RequestMapping("/api/v1/conversations") public class ConversationController {private final ConversationRepository conversations;private final ProfileService profiles;private final AdmissionAdvisorWorkflow workflow;private final RateLimiter rate;public ConversationController(ConversationRepository c,ProfileService p,AdmissionAdvisorWorkflow w,RateLimiter r){conversations=c;profiles=p;workflow=w;rate=r;}
 @PostMapping Object create(Authentication a,@RequestBody(required=false)Create x){String title=x==null||x.title==null||x.title.isBlank()?"新建志愿咨询":x.title.trim();return conversations.create(WebSupport.user(a).id(),title);}
 @GetMapping Object list(Authentication a){return conversations.list(WebSupport.user(a).id());}
 @GetMapping("/{id}")Object get(Authentication a,@PathVariable String id){String uid=WebSupport.user(a).id();return Map.of("conversation",conversations.findOwned(id,uid).orElseThrow(()->new IllegalArgumentException("会话不存在")),"messages",conversations.messages(id,uid));}
 @PatchMapping("/{id}")Object rename(Authentication a,@PathVariable String id,@Valid @RequestBody Rename x){return conversations.rename(id,WebSupport.user(a).id(),x.title);}
 @DeleteMapping("/{id}")Map<String,Object> delete(Authentication a,@PathVariable String id){conversations.delete(id,WebSupport.user(a).id());return Map.of("deleted",true);}
 @GetMapping("/{id}/messages")Object messages(Authentication a,@PathVariable String id){return conversations.messages(id,WebSupport.user(a).id());}
 @PostMapping("/{id}/messages")Object message(Authentication a,@PathVariable String id,@Valid @RequestBody Send x,@RequestHeader(value="Idempotency-Key",required=false)String key){String uid=WebSupport.user(a).id();rate.check("message:"+uid,30,60);conversations.findOwned(id,uid).orElseThrow(()->new IllegalArgumentException("会话不存在"));var m=conversations.addMessage(id,"user","TEXT",x.content,null);ProfilePatch patch=profiles.extract(x.content);if(!patch.presentValues().isEmpty())profiles.save(uid,patch,m.id());return workflow.submit(uid,id,m.id(),key);}
 record Create(String title){}record Rename(@NotBlank @Size(max=120)String title){}record Send(@NotBlank @Size(max=8000)String content){}
}

