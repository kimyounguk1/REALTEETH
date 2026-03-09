package realTeeth.backendDeveloper.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 카프카 리스너의 작업 실행기를 가상 스레드로 설정
        // 이제 @KafkaListener는 가상 스레드 위에서 동작합니다.
        factory.getContainerProperties().setListenerTaskExecutor(new VirtualThreadTaskExecutor());

        // 동시 처리량 설정, 파티션의 개수와 동일하게 맞추기
        factory.setConcurrency(5);

        return factory;
    }

}
