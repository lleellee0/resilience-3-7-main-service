package resilience.mainservice.main.circuitbreaker;


import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CircuitBreakerLogConfig {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerLogConfig.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public CircuitBreakerLogConfig(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @PostConstruct
    public void registerCircuitBreakerLoggers() {
        // 레지스트리에 등록된 모든 Circuit Breaker 인스턴스에 대해 로거 설정
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::registerLoggerForCircuitBreaker);

        // 레지스트리에 새로운 Circuit Breaker가 동적으로 추가될 때 로거 설정 (선택 사항)
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(entryAddedEvent -> {
                    CircuitBreaker addedCircuitBreaker = entryAddedEvent.getAddedEntry();
                    log.info("New Circuit Breaker '{}' detected, registering loggers.", addedCircuitBreaker.getName());
                    registerLoggerForCircuitBreaker(addedCircuitBreaker);
                });
    }

    private void registerLoggerForCircuitBreaker(CircuitBreaker circuitBreaker) {
        String cbName = circuitBreaker.getName();

        circuitBreaker.getEventPublisher()
                // === 상태 변경 이벤트 로깅 (가장 중요) ===
                .onStateTransition(event -> {
                    log.info("CircuitBreaker '{}' state changed from {} to {}",
                            cbName,
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState());
                })
                // 호출이 거부되었을 때 (Circuit이 Open 상태일 때)
                .onCallNotPermitted(event -> {
                    log.warn("CircuitBreaker '{}' call NOT PERMITTED. Current state is OPEN.", cbName);
                })
                // 호출 실패가 기록되었을 때
                .onError(event -> {
                    log.warn("CircuitBreaker '{}' recorded an ERROR: '{}'. Elapsed time: {}ms",
                            cbName,
                            event.getThrowable().toString(), // 실제 발생한 예외 정보 로깅
                            event.getElapsedDuration().toMillis());
                })
                // 호출 성공이 기록되었을 때 (HALF_OPEN 상태에서 상태 결정에 영향을 줌)
                .onSuccess(event -> {
                    // 성공 로그는 너무 많을 수 있으므로 DEBUG 레벨 사용 고려
                    log.debug("CircuitBreaker '{}' recorded a SUCCESS. Elapsed time: {}ms",
                            cbName,
                            event.getElapsedDuration().toMillis());
                })
                // 무시된 에러 발생 시 (recordExceptions에 없고 ignoreExceptions에 있는 경우)
                .onIgnoredError(event -> {
                    log.debug("CircuitBreaker '{}' ignored an error: '{}'. Elapsed time: {}ms",
                            cbName, event.getThrowable().toString(), event.getElapsedDuration().toMillis());
                });
        // 기타 필요한 이벤트(onReset, onSlowCall 등)도 유사하게 등록 가능

        log.info("Successfully registered loggers for Circuit Breaker '{}'", cbName);
    }
}