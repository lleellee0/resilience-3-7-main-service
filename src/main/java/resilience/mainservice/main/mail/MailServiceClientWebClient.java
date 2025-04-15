package resilience.mainservice.main.mail;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException; // WebClientRequestException 임포트 추가
import reactor.core.publisher.Mono;
import resilience.mainservice.main.circuitbreaker.ConnectionRefusedException;
import resilience.mainservice.main.circuitbreaker.MailServiceTimeoutException;
import resilience.mainservice.main.circuitbreaker.RecordException; // RecordException 임포트 가정 (확인 필요)

import java.net.ConnectException; // ConnectException 임포트 추가
import java.time.Duration;

@Component
public class MailServiceClientWebClient implements MailServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MailServiceClientWebClient.class);
    private final WebClient webClient;

    // --- 상수 정의 ---
    private static final String MAIL_SERVICE_CB = "mailService";
    private static final String MAIL_SEND_URI = "/mail/send";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    // 최대 예외 체인 탐색 깊이 (무한 루프 방지)
    private static final int MAX_CAUSE_DEPTH = 10;

    public MailServiceClientWebClient(WebClient.Builder webClientBuilder) {
        // baseUrl 설정은 유지
        this.webClient = webClientBuilder.baseUrl("http://localhost:8081").build();
    }

    @Override
    @CircuitBreaker(name = MAIL_SERVICE_CB, fallbackMethod = "sendMailFallback")
    public String sendMail(String email) {
        log.debug("[Circuit Breaker: {}] Attempting to send mail for email: {}", MAIL_SERVICE_CB, email);
        EmailRequest request = new EmailRequest(email);

        // WebClient 호출 및 결과 처리
        // .block()을 사용하므로, 전체 메서드는 동기적으로 동작합니다.
        // 완전한 비동기 처리를 원한다면 Mono<String>을 반환하도록 수정해야 합니다.
        return webClient.post()
                .uri(MAIL_SEND_URI) // 상수 사용
                .bodyValue(request)
                .retrieve()
                // --- 5xx 서버 에러 처리 ---
                // onStatus를 사용하여 특정 상태 코드에 대한 처리를 명확히 함
                .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    // 5xx 에러는 별도의 예외(MailSendException)로 처리 (서킷 기록 안 됨 명시)
                                    log.warn("[CB: {}] Mail service returned 5xx server error for {}: {}", MAIL_SERVICE_CB, email, errorBody);
                                    return Mono.error(new MailSendException("Mail service server error: " + errorBody));
                                })
                )
                .bodyToMono(String.class) // 성공 시 응답 본문 처리
                // --- 타임아웃 처리 ---
                // timeout 연산자를 사용하여 지정된 시간 내 응답 없을 시 예외 발생
                // MailServiceTimeoutException은 RecordException을 상속한다고 가정 (실패 기록됨)
                .timeout(DEFAULT_TIMEOUT, Mono.error(
                        () -> new MailServiceTimeoutException("Timeout after " + DEFAULT_TIMEOUT + " for " + email)) // 지연 생성
                )
                // --- 성공 로그 ---
                .doOnSuccess(response -> log.debug("[CB: {}] Successfully sent mail for {}", MAIL_SERVICE_CB, email))
                // --- 에러 매핑 및 처리 ---
                // onErrorMap을 사용하여 특정 에러를 서킷 브레이커가 기록할 예외로 변환
                .onErrorMap(this::mapToCircuitBreakerException) // 에러 매핑 메서드 참조
                // --- 블로킹 호출 ---
                // 리액티브 스트림 결과를 동기적으로 기다림
                .block();
    }

    /**
     * WebClient에서 발생한 에러를 Circuit Breaker가 기록해야 하는 특정 예외로 매핑합니다.
     *
     * @param error 발생한 원본 에러
     * @return 매핑된 예외 (ConnectionRefusedException 등) 또는 원본 에러
     */
    private Throwable mapToCircuitBreakerException(Throwable error) {
        // Connection Refused 확인
        if (isConnectionRefused(error)) {
            log.warn("[CB: {}] Connection refused detected. Mapping to ConnectionRefusedException.", MAIL_SERVICE_CB);
            // ConnectionRefusedException은 RecordException을 상속한다고 가정 (실패 기록됨)
            return new ConnectionRefusedException(
                    "Mail service connection refused. Cause: " + error.getMessage(),
                    error // 원본 에러를 cause로 전달
            );
        }

        // MailSendException (5xx)은 이미 위에서 처리되었으므로 여기서는 무시됨.
        // MailServiceTimeoutException은 timeout 연산자에서 직접 발생.

        // 그 외 기록해야 할 다른 네트워크 관련 에러나 특정 비즈니스 에러가 있다면 여기에 추가
        // 예: if (error instanceof SomeOtherNetworkException) { return new RecordedNetworkException(...) }

        log.warn("[CB: {}] Unmapped error occurred: Type={}. Passing original error.", MAIL_SERVICE_CB, error.getClass().getName());
        // 매핑되지 않은 다른 모든 에러는 원본 그대로 반환
        // -> Resilience4j 설정의 ignoreExceptions/recordExceptions에 따라 처리됨
        return error;
    }

    /**
     * 주어진 에러의 원인 체인(cause chain) 내에 Connection Refused (ConnectException)가 있는지 확인합니다.
     *
     * @param error 확인할 에러
     * @return ConnectException이 원인 체인에 있으면 true, 아니면 false
     */
    private boolean isConnectionRefused(Throwable error) {
        Throwable currentEx = error;
        int depth = 0;
        while (currentEx != null && depth < MAX_CAUSE_DEPTH) {
            if (currentEx instanceof ConnectException) {
                // 메시지 내용까지 검사할 수도 있지만, 타입만으로도 충분한 경우가 많음
                // if (currentEx.getMessage() != null && currentEx.getMessage().toLowerCase().contains("connection refused"))
                return true;
            }
            if (currentEx.getCause() == currentEx) { // 자기 참조 방지
                break;
            }
            currentEx = currentEx.getCause();
            depth++;
        }
        return false;
    }

    /**
     * sendMail 메소드의 Fallback 메소드.
     * Circuit Breaker가 Open 상태이거나, recordExceptions에 지정된 예외가 발생했을 때 호출됩니다.
     */
    public String sendMailFallback(String email, Throwable t) {
        // 어떤 예외로 인해 Fallback이 실행되었는지 명확히 로깅
        log.warn("[CB: {}] Fallback activated for email: {}. Reason: {}",
                MAIL_SERVICE_CB, email, t.getClass().getName() + ": " + t.getMessage());

        // Fallback 로직 (예시):
        // 1. 단순 실패 반환: null 또는 "FAILED" 문자열 등 약속된 값 반환
        // return null;
        // 2. 재시도 큐에 저장: DB나 메시지 큐에 저장 후 나중에 재처리 로직 실행
        // saveToRetryQueue(email); return "QUEUED_FOR_RETRY";
        // 3. 대체 동작 수행: 이메일 대신 다른 알림(SMS 등) 발송
        // sendAlternativeNotification(email); return "SENT_ALTERNATIVE";
        // 4. 예외 다시 던지기 (현재 방식): 호출자에게 에러 처리 책임을 넘김
        throw new RuntimeException("Mail service unavailable. Fallback executed for " + email + ". Cause: " + t.getMessage(), t);
    }

}