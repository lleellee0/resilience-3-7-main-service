package resilience.mainservice.main.mail;

public interface MailServiceClient {
    String sendMail(String email);
}