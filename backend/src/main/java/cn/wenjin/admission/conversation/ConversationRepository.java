package cn.wenjin.admission.conversation;

import cn.wenjin.admission.common.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepository {
    private final JdbcClient jdbc;
    public ConversationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Conversation create(String userId, String title) {
        Conversation c = new Conversation(Ids.uuid(), userId, title, "ACTIVE", Instant.now(), Instant.now());
        jdbc.sql("insert into conversations(id,user_id,title,status,created_at,updated_at) values (:id,:userId,:title,:status,:created,:updated)")
                .param("id", c.id()).param("userId", userId).param("title", title).param("status", c.status())
                .param("created", java.sql.Timestamp.from(c.createdAt())).param("updated", java.sql.Timestamp.from(c.updatedAt())).update();
        return c;
    }
    public List<Conversation> list(String userId) {
        return jdbc.sql("select * from conversations where user_id=:userId and deleted_at is null order by updated_at desc")
                .param("userId", userId).query((rs,n)->new Conversation(rs.getString("id"),rs.getString("user_id"),rs.getString("title"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant())).list();
    }
    public Optional<Conversation> findOwned(String id, String userId) { return list(userId).stream().filter(c->c.id().equals(id)).findFirst(); }
    public Conversation rename(String id, String userId, String title) {
        require(id,userId); Instant now=Instant.now(); jdbc.sql("update conversations set title=:title,updated_at=:now where id=:id").param("title",title).param("now",java.sql.Timestamp.from(now)).param("id",id).update(); return findOwned(id,userId).orElseThrow();
    }
    public void delete(String id,String userId){ require(id,userId); jdbc.sql("update conversations set deleted_at=:now where id=:id").param("now",java.sql.Timestamp.from(Instant.now())).param("id",id).update(); }
    public Message addMessage(String conversationId,String role,String type,String content,String structured){
        Message m=new Message(Ids.uuid(),conversationId,role,type,content,structured,Instant.now());
        jdbc.sql("insert into conversation_messages(id,conversation_id,role,message_type,content,structured_content_json,created_at) values (:id,:conversationId,:role,:type,:content,:structured,:created)")
                .param("id",m.id()).param("conversationId",conversationId).param("role",role).param("type",type).param("content",content).param("structured",structured).param("created",java.sql.Timestamp.from(m.createdAt())).update();
        jdbc.sql("update conversations set updated_at=:now where id=:id").param("now",java.sql.Timestamp.from(Instant.now())).param("id",conversationId).update(); return m;
    }
    public List<Message> messages(String id,String userId){ require(id,userId); return jdbc.sql("select * from conversation_messages where conversation_id=:id order by created_at").param("id",id).query((rs,n)->new Message(rs.getString("id"),rs.getString("conversation_id"),rs.getString("role"),rs.getString("message_type"),rs.getString("content"),rs.getString("structured_content_json"),rs.getTimestamp("created_at").toInstant())).list(); }
    private void require(String id,String userId){ if(findOwned(id,userId).isEmpty()) throw new IllegalArgumentException("会话不存在"); }
    public record Conversation(String id,String userId,String title,String status,Instant createdAt,Instant updatedAt){}
    public record Message(String id,String conversationId,String role,String messageType,String content,String structuredContentJson,Instant createdAt){}
}
