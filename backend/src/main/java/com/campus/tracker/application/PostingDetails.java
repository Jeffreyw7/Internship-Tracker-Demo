package com.campus.tracker.application;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Current posting metadata belongs to the versioned application aggregate. */
@Embeddable
public class PostingDetails {
    @Column(name="posting_url", nullable=false, length=2048) String postingUrl="";
    @Column(name="recruiter_name", nullable=false, length=120) String recruiterName="";
    @Column(name="recruiter_role", nullable=false, length=120) String recruiterRole="";
    @Column(name="recruiter_email", nullable=false, length=254) String recruiterEmail="";
    @Column(name="recruiter_phone", nullable=false, length=50) String recruiterPhone="";
    @Column(name="recruiter_profile_url", nullable=false, length=2048) String recruiterProfileUrl="";
    @Column(name="referral_channel", nullable=false, length=30) String referralChannel="UNKNOWN";
    @Column(name="referral_source", nullable=false, length=200) String referralSource="";
    @Column(name="referrer_name", nullable=false, length=120) String referrerName="";
    @Column(name="referrer_contact", nullable=false, length=254) String referrerContact="";
    @Column(name="compensation_min", precision=14, scale=2) BigDecimal compensationMin;
    @Column(name="compensation_max", precision=14, scale=2) BigDecimal compensationMax;
    @Column(name="compensation_currency", nullable=false, length=3) String compensationCurrency="";
    @Column(name="compensation_period", length=20) String compensationPeriod;
    @Column(name="compensation_status", nullable=false, length=10) String compensationStatus="UNKNOWN";
    @Column(name="compensation_original_text", nullable=false, length=1000) String compensationOriginalText="";

    public record View(String postingUrl, String recruiterName, String recruiterRole, String recruiterEmail,
                       String recruiterPhone, String recruiterProfileUrl, String referralChannel, String referralSource,
                       String referrerName, String referrerContact, String compensationMin, String compensationMax,
                       String compensationCurrency, String compensationPeriod, String compensationStatus,
                       String compensationOriginalText, int descriptionRevision) {}
    View view(int revision) {
        return new View(postingUrl,recruiterName,recruiterRole,recruiterEmail,recruiterPhone,recruiterProfileUrl,
                referralChannel,referralSource,referrerName,referrerContact,decimal(compensationMin),decimal(compensationMax),
                compensationCurrency,compensationPeriod,compensationStatus,compensationOriginalText,revision);
    }
    private static String decimal(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }
}
