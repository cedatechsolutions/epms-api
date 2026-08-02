package com.cems.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A content question within a survey (spec §3.3). The GAD demographic block (sex/age/sector) is a
 * fixed part of the response schema and is NOT stored as a question. {@code options} holds the
 * choice-type labels as JSON text, (de)serialized in the service layer.
 */
@Entity
@Table(name = "survey_questions")
public class SurveyQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    private String questionText;

    /** One of: rating, multiple_choice, checkbox, open_text (enforced by DB check + service). */
    @Column(name = "question_type", nullable = false)
    private String questionType;

    /** Choice-type options as JSON text, e.g. [{"label":"Yes","value":"yes"}]; null for rating/open_text. */
    @Column(columnDefinition = "text")
    private String options;

    /** Scoring weight, 0.5–5.0 (validated in the service). */
    @Column(nullable = false)
    private BigDecimal weight = BigDecimal.ONE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "need_category_id")
    private NeedCategory needCategory;

    @Column(name = "is_required", nullable = false)
    private boolean required = true;

    public SurveyQuestion() {
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

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public String getOptions() {
        return options;
    }

    public void setOptions(String options) {
        this.options = options;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public NeedCategory getNeedCategory() {
        return needCategory;
    }

    public void setNeedCategory(NeedCategory needCategory) {
        this.needCategory = needCategory;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }
}
