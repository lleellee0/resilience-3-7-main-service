package resilience.mainservice.main.mail;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import resilience.mainservice.main.circuitbreaker.ConnectionRefusedException;
import resilience.mainservice.main.circuitbreaker.MailServiceTimeoutException;

import java.net.ConnectException;
import java.time.Duration;

@Component
public class MailServiceClientWebClient implements MailServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MailServiceClientWebClient.class);
    private final WebClient webClient;
    // Circuit Breaker 이름을 상수로 정의하여 사용
    private static final String MAIL_SERVICE_CB = "mailService";

    public MailServiceClientWebClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:8081").build();
    }

    @Override
    @CircuitBreaker(name = MAIL_SERVICE_CB, fallbackMethod = "sendMailFallback") // 어노테이션 추가
    public String sendMail(String email) {
        log.debug("[Circuit Breaker: {}] Attempting to send mail for email: {}", MAIL_SERVICE_CB, email);
        EmailRequest request = new EmailRequest(email);
        return webClient.post()
                .uri("/mail/send")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.warn("[Circuit Breaker: {}] Mail service returned 5xx server error for {}: {}", MAIL_SERVICE_CB, email, errorBody);
                                    // MailSendException은 recordExceptions에 해당하지 않음
                                    return Mono.error(new MailSendException("서버 에러 발생: " + errorBody));
                                })
                )
                .bodyToMono(String.class)
                // Timeout 발생 시 MailServiceTimeoutException을 던지며, 이는 RecordException의 하위 타입이므로 Circuit Breaker 실패로 기록됨
                .timeout(Duration.ofSeconds(10), Mono.error(new MailServiceTimeoutException("Mail service read timeout after 10 seconds for " + email)))
                .doOnSuccess(response -> log.debug("[Circuit Breaker: {}] Successfully sent mail for {}", MAIL_SERVICE_CB, email))
                .doOnError(error -> {
                    Throwable rootCause = error;
                    while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                        rootCause = rootCause.getCause();
                    }

                    if (rootCause instanceof ConnectException || rootCause instanceof WebClientRequestException && rootCause.getMessage().contains("Connection refused")) {
                        throw new ConnectionRefusedException("Mail service connection refused for " + email + ". Fallback executed. Cause: " + rootCause.getMessage(), rootCause);
                    }

                    // (단, Fallback이 실행되면 이 doOnError는 실행되지 않을 수 있음)
                    log.error("[Circuit Breaker: {}] Error during mail sending for {}: {}", MAIL_SERVICE_CB, email, error.getMessage());
                })
                .block();
    }

    /**
     * sendMail 메소드의 Fallback 메소드.
     * Circuit Breaker가 Open 상태이거나, recordExceptions에 지정된 예외가 발생했을 때 호출됩니다.
     * 원본 메소드와 동일한 파라미터 목록을 가지며, 마지막에 Throwable 타입의 파라미터를 추가할 수 있습니다.
     * 반환 타입은 원본 메소드(String)와 동일해야 합니다.
     */
    public String sendMailFallback(String email, Throwable t) {
        // 어떤 이유로 Fallback이 실행되었는지 로그 기록
        log.warn("[Circuit Breaker: {}] Fallback activated for email: {}. Reason: {}",
                MAIL_SERVICE_CB, email, t.toString()); // t.toString()으로 예외 타입과 메시지 확인

        // 여기선 단순히 로그를 찍었지만 필요하다면 DB에 저장해놨다가 나중에 메일을 다시 전송한다던지,
        // 메일 대신 대체 수단으로 결제 완료 메시지를 보낸다던지 할 수 있습니다.

        throw new RuntimeException("Mail service is currently unavailable for " + email + ". Fallback executed. Cause: " + t.getMessage());
    }

}