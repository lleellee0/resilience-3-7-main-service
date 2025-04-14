package resilience.mainservice.main.circuitbreaker;

public class RecordException extends RuntimeException {

    // 메시지를 받는 생성자
    public RecordException(String message) {
        super(message);
    }

    // 메시지와 원인 예외를 받는 생성자
    public RecordException(String message, Throwable cause) {
        super(message, cause);
    }

}