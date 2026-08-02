package com.cems.api.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One submitted response to a public survey (spec §3.3). {@code respondentSex} is REQUIRED — GAD
 * disaggregation depends on it. {@code respondentToken} provides best-effort per-device dedupe
 * (unique per survey); it is not an identity and carries no PII.
 */
@Entity
@Table(name = "survey_responses")
public class SurveyResponse {

    public static final String SEX_FEMALE = "female";
    public static final String SEX_MALE = "male";
    public static final String SEX_PREFER_NOT_TO_SAY = "prefer_not_to_say";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @Column(name = "respondent_token")
    private String respondentToken;

    @Column(name = "respondent_sex", nullable = false)
    private String respondentSex;

    @Column(name = "respondent_age_group")
    private String respondentAgeGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "respondent_sector_id")
    private Sector respondentSector;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "ip_address")
    private String ipAddress;

    @OneToMany(mappedBy = "surveyResponse", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SurveyAnswer> answers = new ArrayList<>();

    public SurveyResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Survey getSurvey() {
        return survey;
    }

    public void setSurvey(Survey survey) {
        this.survey = survey;
    }

    public String getRespondentToken() {
        return respondentToken;
    }

    public void setRespondentToken(String respondentToken) {
        this.respondentToken = respondentToken;
    }

    public String getRespondentSex() {
        return respondentSex;
    }

    public void setRespondentSex(String respondentSex) {
        this.respondentSex = respondentSex;
    }

    public String getRespondentAgeGroup() {
        return respondentAgeGroup;
    }

    public void setRespondentAgeGroup(String respondentAgeGroup) {
        this.respondentAgeGroup = respondentAgeGroup;
    }

    public Sector getRespondentSector() {
        return respondentSector;
    }

    public void setRespondentSector(Sector respondentSector) {
        this.respondentSector = respondentSector;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public List<SurveyAnswer> getAnswers() {
        return answers;
    }

    public void setAnswers(List<SurveyAnswer> answers) {
        this.answers = answers;
    }
}
