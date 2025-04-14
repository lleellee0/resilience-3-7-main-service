package resilience.mainservice.main.payment;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger; // SLF4J Logger 사용
import org.slf4j.LoggerFactory; // SLF4J LoggerFactory 사용
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException; // 네트워크 관련 예외 처리

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PaymentControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentControllerIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 약 1분 동안 /payments/process-mvc 엔드포인트에 부하를 가하는 테스트.
     * Load Pattern:
     * - 0-10초: 1 RPS
     * - 10-15초: 1 RPS에서 20 RPS로 빠르게 증가 (5초간)
     * - 15-18초: 20 RPS에서 1 RPS로 빠르게 감소 (3초간)
     * - 18초-60초: 1 RPS
     */
    @Test
    public void testProcessMvcWithVariableLoad() throws InterruptedException {
        int testDurationSeconds = 60; // 총 테스트 시간 (초)

        // 스레드 풀 생성
        ExecutorService executor = Executors.newCachedThreadPool(); // 필요시 FixedThreadPool 고려

        // 성공/실패 카운터
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        // 고유한 주문 ID 및 총 요청 수 카운터
        AtomicInteger requestCounter = new AtomicInteger(0);
        AtomicInteger totalSubmittedRequests = new AtomicInteger(0);

        log.info("가변 부하 테스트 시작: 총 {}초 동안 실행 (패턴: 1 -> peak -> 1 RPS)", testDurationSeconds);

        long testStartTime = System.currentTimeMillis();

        // 1초마다 RPS를 계산하고 요청을 제출하는 루프
        for (int t = 0; t < testDurationSeconds; t++) {
            long loopStartTime = System.currentTimeMillis();
            int currentRps = calculateRps(t); // 현재 시간(t)에 대한 목표 RPS 계산
            totalSubmittedRequests.addAndGet(currentRps); // 총 제출 요청 수 업데이트

            log.info("Second {}: Target RPS = {}", t + 1, currentRps);

            // 현재 RPS만큼 요청 작업을 스레드 풀에 제출
            for (int i = 0; i < currentRps; i++) {
                int currentRequestNum = requestCounter.incrementAndGet();
                executor.submit(() -> {
                    String orderId = "order-" + currentRequestNum;
                    try {
                        PaymentRequest request = new PaymentRequest();
                        request.setOrderId(orderId);
                        request.setAmount(100.0);

                        // log.debug("요청 전송: {}", orderId); // 디버그 시 주석 해제
                        ResponseEntity<String> response = restTemplate.postForEntity(
                                "/payments/process-mvc", request, String.class);

                        if (response.getStatusCode().is2xxSuccessful()) {
                            successCount.incrementAndGet();
                            // log.debug("요청 성공: {}", orderId); // 디버그 시 주석 해제
                        } else {
                            failureCount.incrementAndGet();
                            log.warn("요청 실패: {}. 상태 코드: {}", orderId, response.getStatusCode());
                        }
                    } catch (ResourceAccessException rae) {
                        failureCount.incrementAndGet();
                        log.error("네트워크 오류 발생: {}: {}", orderId, rae.getMessage().split(":")[0]); // 에러 메시지 간소화
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                        log.error("예기치 않은 오류 발생: {}: {}", orderId, e.getMessage(), e);
                    }
                    // CountDownLatch를 사용하지 않으므로 여기서 카운트다운 불필요
                });
            } // 내부 루프 (RPS만큼 요청 제출) 끝

            // 다음 초까지 남은 시간 계산 및 대기
            long loopEndTime = System.currentTimeMillis();
            long loopDuration = loopEndTime - loopStartTime;
            long sleepTime = 1000 - loopDuration; // 1초에서 루프 실행 시간 제외

            if (sleepTime > 0) {
                Thread.sleep(sleepTime);
            } else {
                log.warn("Second {}: Loop execution took longer than 1 second ({}ms).", t + 1, loopDuration);
            }
        } // 외부 루프 (시간 반복) 끝

        log.info("모든 요청 작업 제출 완료 (총 {}개). 스레드 풀 종료 시작...", totalSubmittedRequests.get());

        // 스레드 풀 종료 (새 작업은 받지 않고 기존 작업 완료 대기)
        executor.shutdown();
        try {
            // 모든 작업이 완료될 때까지 대기 (예: 최대 2분 추가 대기)
            log.info("Executor 작업 완료 대기 중...");
            if (!executor.awaitTermination(120, TimeUnit.SECONDS)) { // 충분한 대기 시간 부여
                log.warn("스레드 풀이 시간 내에 완전히 종료되지 않았습니다. 강제 종료 시도.");
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("스레드 풀 강제 종료 실패.");
                }
            } else {
                log.info("Executor 작업 모두 완료.");
            }
        } catch (InterruptedException ie) {
            log.warn("Executor 종료 대기 중 인터럽트 발생. 강제 종료 시도.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        long testEndTime = System.currentTimeMillis();
        long totalDurationMillis = testEndTime - testStartTime;

        // 최종 결과 로깅
        log.info("===== MVC 엔드포인트 가변 부하 테스트 결과 =====");
        log.info("총 테스트 시간: {}ms (약 {}초)", totalDurationMillis, totalDurationMillis / 1000);
        log.info("총 제출된 요청 수: {}", totalSubmittedRequests.get());
        log.info("  - 성공 요청 수: {}", successCount.get());
        log.info("  - 실패 요청 수: {}", failureCount.get());
    }

    /**
     * 테스트 시간(경과 시간, 초)에 따라 목표 RPS(Requests Per Second)를 계산합니다.
     * Pattern:
     * - 0-10초 (t=0-9): 1 RPS
     * - 10-15초 (t=10-14): 1 RPS -> 20 RPS 선형 증가 (5초간)
     * - 15-18초 (t=15-17): 20 RPS -> 1 RPS 선형 감소 (3초간)
     * - 18초-60초 (t=18-59): 1 RPS
     * @param elapsedSeconds 테스트 시작 후 경과 시간 (0부터 시작)
     * @return 해당 초에 보내야 할 요청 수 (RPS)
     */
    private int calculateRps(int elapsedSeconds) {
        if (elapsedSeconds < 10) {
            // Phase 1 (0-9초): 1 RPS
            return 1;
        } else if (elapsedSeconds < 15) {
            // Phase 2 (10-14초): 1 RPS -> 20 RPS 선형 증가 (5초간)
            // t=10일 때 약 5 RPS, t=14일 때 20 RPS
            double progress = (double) (elapsedSeconds - 9) / 5.0; // 0.2 to 1.0
            double rps = 1.0 + progress * 19.0;
            return Math.max(1, (int) Math.round(rps));
        } else if (elapsedSeconds < 18) {
            // Phase 3 (15-17초): 20 RPS -> 1 RPS 선형 감소 (3초간)
            // t=15일 때 약 14 RPS, t=17일 때 1 RPS
            double progress = (double) (elapsedSeconds - 14) / 3.0; // 1/3 to 1.0
            double rps = 20.0 - progress * 19.0;
            return Math.max(1, (int) Math.round(rps));
        } else {
            // Phase 4 (18-59초): 1 RPS
            return 1;
        }
    }
}