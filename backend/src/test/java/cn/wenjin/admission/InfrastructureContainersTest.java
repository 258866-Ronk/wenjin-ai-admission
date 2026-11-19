package cn.wenjin.admission;

import org.junit.jupiter.api.Test;import org.testcontainers.junit.jupiter.Container;import org.testcontainers.junit.jupiter.Testcontainers;import org.testcontainers.postgresql.PostgreSQLContainer;import org.testcontainers.containers.GenericContainer;import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
@Testcontainers(disabledWithoutDocker=true)
class InfrastructureContainersTest {
 @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"));
 @Container static GenericContainer<?> redis=new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
 @Test void postgresAndRedisStart(){assertThat(postgres.isRunning()).isTrue();assertThat(redis.isRunning()).isTrue();}
}
