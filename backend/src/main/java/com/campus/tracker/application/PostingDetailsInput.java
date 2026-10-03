package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

/** JsonNode keeps omitted fields distinct from explicit null, including on older clients. */
final class PostingDetailsInput {
    private static final Set<String> FIELDS=Set.of("postingUrl","recruiterName","recruiterRole","recruiterEmail",
            "recruiterPhone","recruiterProfileUrl","referralChannel","referralSource","referrerName","referrerContact",
            "compensationMin","compensationMax","compensationCurrency","compensationPeriod","compensationStatus","compensationOriginalText");
    record Parsed(PostingDetails details, String description) {}
    private record EmailValue(@Email String email) {}
    static Parsed parse(JsonNode node, Validator validator) {
        if (node == null) return null;
        if (!node.isObject()) throw bad("Posting details must be an object.");
        node.fieldNames().forEachRemaining(key->{if(!FIELDS.contains(key)&&!key.equals("descriptionSnapshot")) throw bad("Unknown posting field: "+key);});
        for (String field:FIELDS) if (!node.has(field)) throw bad("Missing posting field: "+field);
        PostingDetails p=new PostingDetails();
        p.postingUrl=text(node,"postingUrl",2048); validateUrl(p.postingUrl);
        p.recruiterName=text(node,"recruiterName",120); p.recruiterRole=text(node,"recruiterRole",120);
        p.recruiterEmail=text(node,"recruiterEmail",254);
        if (!validator.validate(new EmailValue(p.recruiterEmail)).isEmpty()) throw bad("Enter a valid recruiter email.");
        p.recruiterPhone=text(node,"recruiterPhone",50); p.recruiterProfileUrl=text(node,"recruiterProfileUrl",2048); validateUrl(p.recruiterProfileUrl);
        p.referralChannel=choice(node,"referralChannel",Set.of("UNKNOWN","JOB_BOARD","COMPANY_WEBSITE","CAREER_FAIR","EMPLOYEE","PROFESSOR","OTHER"));
        p.referralSource=text(node,"referralSource",200); p.referrerName=text(node,"referrerName",120); p.referrerContact=text(node,"referrerContact",254);
        p.compensationMin=amount(node.get("compensationMin")); p.compensationMax=amount(node.get("compensationMax"));
        p.compensationCurrency=text(node,"compensationCurrency",3).toUpperCase(Locale.ROOT);
        if (!p.compensationCurrency.isEmpty()) {
            try { Currency.getInstance(p.compensationCurrency); } catch(IllegalArgumentException e) { throw bad("Enter a supported three-letter currency code."); }
        }
        p.compensationStatus=choice(node,"compensationStatus",Set.of("UNKNOWN","PAID","UNPAID"));
        p.compensationPeriod=node.get("compensationPeriod").isNull()?null:choice(node,"compensationPeriod",Set.of("HOUR","MONTH","YEAR","TOTAL_INTERNSHIP"));
        p.compensationOriginalText=text(node,"compensationOriginalText",1000);
        if (p.compensationMin!=null && p.compensationMax!=null && p.compensationMin.compareTo(p.compensationMax)>0) throw bad("Compensation minimum cannot exceed maximum.");
        if ((p.compensationMin!=null || p.compensationMax!=null) && (!p.compensationStatus.equals("PAID") || p.compensationCurrency.isEmpty() || p.compensationPeriod==null))
            throw bad("Compensation amounts need paid status, a currency, and a pay period.");
        String description=null;
        if (node.has("descriptionSnapshot")) {
            JsonNode value=node.get("descriptionSnapshot");
            if (!value.isTextual() || value.textValue().length()>100000) throw bad("Description must be text of at most 100,000 characters.");
            description=value.textValue();
        }
        return new Parsed(p,description);
    }
    private static String text(JsonNode n,String field,int max) {
        JsonNode value=n.get(field);
        if (!value.isTextual() || value.textValue().length()>max) throw bad("Check "+field+" (maximum "+max+" characters).");
        return value.textValue().trim();
    }
    private static String choice(JsonNode n,String field,Set<String> choices) {
        String value=text(n,field,30); if(!choices.contains(value)) throw bad("Invalid "+field+"."); return value;
    }
    private static BigDecimal amount(JsonNode value) {
        if(value.isNull()) return null;
        if(!value.isTextual() && !value.isNumber()) throw bad("Compensation must be a decimal amount or null.");
        try {
            BigDecimal number=new BigDecimal(value.asText());
            if(number.signum()<0 || number.scale()>2 || number.compareTo(new BigDecimal("999999999999.99"))>0) throw bad("Compensation needs a nonnegative amount with at most two decimal places.");
            return number.setScale(2);
        } catch(NumberFormatException|ArithmeticException e) {throw bad("Enter a valid compensation amount.");}
    }
    private static void validateUrl(String value) {
        if(value.isEmpty()) return;
        if(value.chars().anyMatch(Character::isISOControl)) throw bad("URLs cannot contain control characters.");
        try {
            URI uri=new URI(value);
            if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT)) || uri.getHost()==null || uri.getRawUserInfo()!=null)
                throw bad("Use an absolute HTTP or HTTPS URL without embedded credentials.");
        } catch(java.net.URISyntaxException e) {throw bad("Enter a valid HTTP or HTTPS URL.");}
    }
    private static ApiException bad(String message) {return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
