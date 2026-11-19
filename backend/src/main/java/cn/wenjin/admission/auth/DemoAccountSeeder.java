package cn.wenjin.admission.auth;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.domain.Domain.StudentProfile;
import cn.wenjin.admission.profile.ProfileRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DemoAccountSeeder implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final ProfileRepository profiles;

    public DemoAccountSeeder(JdbcClient jdbc, PasswordEncoder encoder, ProfileRepository profiles) {
        this.jdbc = jdbc; this.encoder = encoder; this.profiles = profiles;
    }

    @Override public void run(ApplicationArguments args) {
        seed("demo@wenjin.local", "Demo123!", "演示考生", "USER");
        seed("admin@wenjin.local", "Admin123!", "演示管理员", "ADMIN");
    }

    private void seed(String email,String password,String name,String role){
        Integer count=jdbc.sql("select count(*) from users where email=:email").param("email",email).query(Integer.class).single();
        if(count!=null&&count>0)return;String id=Ids.uuid();Instant now=Instant.now();jdbc.sql("insert into users(id,email,password_hash,display_name,role,anonymous,created_at,updated_at) values (:id,:email,:hash,:name,:role,false,:now,:now)").param("id",id).param("email",email).param("hash",encoder.encode(password)).param("name",name).param("role",role).param("now",java.sql.Timestamp.from(now)).update();profiles.create(StudentProfile.empty(Ids.uuid(),id));
    }
}
