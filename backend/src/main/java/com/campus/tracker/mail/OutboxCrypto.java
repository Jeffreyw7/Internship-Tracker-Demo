package com.campus.tracker.mail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
@Component
public class OutboxCrypto {
    private final MailProperties config;
    private final ObjectMapper json;
    private final SecureRandom random=new SecureRandom();
    public OutboxCrypto(MailProperties config,ObjectMapper json){this.config=config;this.json=json;}
    public record Encrypted(String payload,String nonce,String keyId) {
        @Override public String toString(){return "Encrypted[REDACTED]";}
    }
    public Encrypted encrypt(UUID job,EmailMessage message){
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        try {
            Cipher cipher=cipher(Cipher.ENCRYPT_MODE,job,nonce);
            return new Encrypted(Base64.getEncoder().encodeToString(cipher.doFinal(json.writeValueAsBytes(message))),
                    Base64.getEncoder().encodeToString(nonce),config.getKeyId());
        } catch(Exception e){throw new IllegalStateException("Cannot protect mail payload");}
    }
    public EmailMessage decrypt(UUID job,String payload,String nonce,String keyId){
        if(!config.getKeyId().equals(keyId)) throw new IllegalStateException("Unknown mail key");
        try {
            return json.readValue(cipher(Cipher.DECRYPT_MODE,job,Base64.getDecoder().decode(nonce))
                    .doFinal(Base64.getDecoder().decode(payload)),EmailMessage.class);
        } catch(Exception e){throw new IllegalStateException("Invalid mail payload");}
    }
    private Cipher cipher(int mode,UUID id,byte[] nonce) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(config.getKey(),"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(("campus-mail-v1:"+config.getKeyId()+":"+id).getBytes(StandardCharsets.UTF_8));return cipher;
    }
}
