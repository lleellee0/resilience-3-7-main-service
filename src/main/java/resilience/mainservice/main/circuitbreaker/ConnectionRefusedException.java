package resilience.mainservice.main.circuitbreaker;

public class ConnectionRefusedException extends RecordException {

    public ConnectionRefusedException(String message) {
        super(message);
    }

    public ConnectionRefusedException(String message, Throwable cause) {
        super(message, cause);
    }

}