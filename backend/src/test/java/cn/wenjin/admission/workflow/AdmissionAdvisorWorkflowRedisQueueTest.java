package cn.wenjin.admission.workflow;

import cn.wenjin.admission.config.AppProperties;
import cn.wenjin.admission.conversation.ConversationRepository;
import cn.wenjin.admission.data.AdmissionDataRepository;
import cn.wenjin.admission.engine.RecommendationEngine;
import cn.wenjin.admission.engine.RiskEngine;
import cn.wenjin.admission.profile.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdmissionAdvisorWorkflowRedisQueueTest {
    @Test
    void consumesJobsWhenCreateGroupWasWrappedButGroupActuallyExists() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        StreamInfo.XInfoGroups noGroups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroups existingGroups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup existingGroup = mock(StreamInfo.XInfoGroup.class);

        when(redis.opsForStream()).thenReturn(stream);
        when(noGroups.stream()).thenReturn(Stream.empty());
        when(existingGroups.stream()).thenAnswer(ignored -> Stream.of(existingGroup));
        when(existingGroup.groupName()).thenReturn("advisor-workers");
        when(stream.groups("admission:jobs")).thenReturn(noGroups, existingGroups);
        doThrow(new IllegalStateException("Error in execution"))
                .when(stream).createGroup("admission:jobs", org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"), "advisor-workers");
        when(stream.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        AppProperties properties = new AppProperties(null, null, null,
                new AppProperties.Workflow(1, 10, true), null);
        AdmissionAdvisorWorkflow workflow = new AdmissionAdvisorWorkflow(
                mock(ProfileService.class), mock(AdmissionDataRepository.class),
                mock(RecommendationEngine.class), mock(RiskEngine.class),
                mock(ReportGenerator.class), mock(RunRepository.class),
                mock(RunEventStore.class), mock(ConversationRepository.class),
                redis, properties);
        try {
            workflow.consumeRedisJobs();
            verify(stream).read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
        } finally {
            workflow.close();
        }
    }
}
