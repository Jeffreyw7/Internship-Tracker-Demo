package com.campus.tracker.mail;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
@Component
public class SmtpEmailSender implements EmailSender {
    private final ObjectProvider<JavaMailSender> sender;
    private final MailProperties config;
    public SmtpEmailSender(ObjectProvider<JavaMailSender> sender, MailProperties config){this.sender=sender;this.config=config;}
    @Override public void send(EmailMessage message) {
        JavaMailSender smtp=sender.getIfAvailable();
        if(smtp==null) throw new IllegalStateException("SMTP is unavailable");
        SimpleMailMessage mail=new SimpleMailMessage();mail.setFrom(config.getFrom());mail.setTo(message.recipient());
        if(message.type().equals("CHANGED")) {
            mail.setSubject("Your Campus password was changed");
            mail.setText("Your Campus password was changed at "+message.timestamp()+".\nIf you did not make this change, use Forgot password on Campus to recover access.");
        } else {
            boolean reset=message.type().equals("RESET");
            mail.setSubject(reset?"Reset your Campus password":"Confirm your Campus password change");
            mail.setText((reset?"Set a new password":"Open this link in the browser where you requested the change")+":\n"
                    +config.getOrigin()+"/#password-"+(reset?"reset":"change")+"="+message.token()
                    +"\nThis link expires in "+(reset?config.getResetTtl():config.getChangeTtl()).toMinutes()+" minutes."
                    +"\nIf you did not request it, you can ignore this email.");
        }
        try{smtp.send(mail);}
        catch(org.springframework.mail.MailAuthenticationException e){throw new PermanentDeliveryException();}
        catch(org.springframework.mail.MailSendException e){
            for(Exception failure:e.getMessageExceptions()){
                if(failure instanceof jakarta.mail.SendFailedException rejected && rejected.getInvalidAddresses()!=null
                        && rejected.getInvalidAddresses().length>0)throw new PermanentDeliveryException();
            }
            throw e;
        }
    }
}
