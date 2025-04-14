package resilience.mainservice.main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import resilience.mainservice.main.mail.MailSendException;
import resilience.mainservice.main.payment.PaymentServiceErrorException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MailSendException.class)
    public ResponseEntity<String> handleMailSendException(MailSendException ex) {
        logger.error("메일 전송 실패");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("메일 전송 실패: " + ex.getMessage());
    }

    @ExceptionHandler(PaymentServiceErrorException.class)
    public ResponseEntity<String> handlePaymentServiceErrorException(PaymentServiceErrorException ex) {
        logger.error("결제 서비스 오류 발생");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("결제 서비스 오류: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneralException(Exception ex) {
        logger.error("예기치 못한 오류 발생");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("예기치 못한 오류가 발생하였습니다.");
    }
}
