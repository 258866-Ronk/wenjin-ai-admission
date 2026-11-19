package cn.wenjin.admission.auth;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.config.AppProperties;
import cn.wenjin.admission.domain.Domain.StudentProfile;
import cn.wenjin.admission.profile.ProfileRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class AuthService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final ProfileRepository profiles;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwordEncoder, AppProperties properties, ProfileRepository profiles) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.profiles = profiles;
    }

    @Transactional
    public SessionResult register(String email, String password, String displayName) {
        validatePassword(password);
        String userId = Ids.uuid();
        Instant now = Instant.now();
        try {
            jdbc.sql("insert into users(id,email,password_hash,display_name,role,anonymous,created_at,updated_at) values (:id,:email,:hash,:name,'USER',false,:now,:now)")
                    .param("id", userId).param("email", normalizeEmail(email))
                    .param("hash", passwordEncoder.encode(password)).param("name", displayName.trim())
                    .param("now", java.sql.Timestamp.from(now)).update();
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        profiles.create(StudentProfile.empty(Ids.uuid(), userId));
        return createSession(userId);
    }

    @Transactional
    public SessionResult anonymous() {
        String id = Ids.uuid();
        Instant now = Instant.now();
        jdbc.sql("insert into users(id,email,password_hash,display_name,role,anonymous,created_at,updated_at) values (:id,:email,:hash,'游客','USER',true,:now,:now)")
                .param("id", id).param("email", "guest-" + id + "@local.invalid")
                .param("hash", passwordEncoder.encode(Ids.token())).param("now", java.sql.Timestamp.from(now)).update();
        profiles.create(StudentProfile.empty(Ids.uuid(), id));
        return createSession(id);
    }

    public SessionResult login(String email, String password) {
        User user = jdbc.sql("select id,email,password_hash,display_name,role,anonymous from users where email=:email and deleted_at is null")
                .param("email", normalizeEmail(email)).query(this::mapUser).optional()
                .orElseThrow(() -> new IllegalArgumentException("邮箱或密码错误"));
        if (!passwordEncoder.matches(password, user.passwordHash())) throw new IllegalArgumentException("邮箱或密码错误");
        return createSession(user.id());
    }

    public Optional<User> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return jdbc.sql("""
                select u.id,u.email,u.password_hash,u.display_name,u.role,u.anonymous
                from user_sessions s join users u on u.id=s.user_id
                where s.token_hash=:hash and s.expires_at>:now and u.deleted_at is null
                """).param("hash", Ids.sha256(rawToken)).param("now", java.sql.Timestamp.from(Instant.now()))
                .query(this::mapUser).optional();
    }

    public Optional<User> findUser(String userId) {
        return jdbc.sql("select id,email,password_hash,display_name,role,anonymous from users where id=:id and deleted_at is null")
                .param("id", userId).query(this::mapUser).optional();
    }

    public void logout(String rawToken) {
        if (rawToken != null) jdbc.sql("delete from user_sessions where token_hash=:hash")
                .param("hash", Ids.sha256(rawToken)).update();
    }

    @Transactional
    public void deleteAccount(String userId) {
        Instant now = Instant.now();
        jdbc.sql("delete from user_sessions where user_id=:id").param("id", userId).update();
        jdbc.sql("update student_profiles set deleted_at=:now where user_id=:id").param("id", userId).param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("update conversations set deleted_at=:now where user_id=:id").param("id", userId).param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("update reports set deleted_at=:now where user_id=:id").param("id", userId).param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("update users set deleted_at=:now,updated_at=:now where id=:id").param("id", userId).param("now", java.sql.Timestamp.from(now)).update();
    }

    private SessionResult createSession(String userId) {
        String rawToken = Ids.token();
        Instant now = Instant.now();
        Instant expires = now.plus(properties.auth().sessionHours(), ChronoUnit.HOURS);
        jdbc.sql("insert into user_sessions(id,user_id,token_hash,expires_at,created_at,last_seen_at) values (:id,:userId,:hash,:expires,:now,:now)")
                .param("id", Ids.uuid()).param("userId", userId).param("hash", Ids.sha256(rawToken))
                .param("expires", java.sql.Timestamp.from(expires)).param("now", java.sql.Timestamp.from(now)).update();
        return new SessionResult(findUser(userId).orElseThrow(), rawToken, expires);
    }

    private User mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new User(rs.getString("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("role"), rs.getBoolean("anonymous"));
    }

    private static String normalizeEmail(String email) {
        if (email == null || !email.contains("@")) throw new IllegalArgumentException("邮箱格式不正确");
        return email.trim().toLowerCase();
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72)
            throw new IllegalArgumentException("密码长度应为 8-72 位");
    }

    public record User(String id, String email, String passwordHash, String displayName, String role, boolean anonymous) {
        public PublicUser publicView() { return new PublicUser(id, anonymous ? null : email, displayName, role, anonymous); }
    }
    public record PublicUser(String id, String email, String displayName, String role, boolean anonymous) { }
    public record SessionResult(User user, String rawToken, Instant expiresAt) { }
}
