package com.cems.api.service;

import com.cems.api.dto.QuestionOption;
import com.cems.api.dto.QuestionRequest;
import com.cems.api.dto.QuestionResponse;
import com.cems.api.dto.SurveyListQuery;
import com.cems.api.dto.SurveyRequest;
import com.cems.api.dto.SurveyResponse;
import com.cems.api.dto.SurveySummaryResponse;
import com.cems.api.entity.Community;
import com.cems.api.entity.NeedCategory;
import com.cems.api.entity.Survey;
import com.cems.api.entity.SurveyQuestion;
import com.cems.api.entity.User;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.CommunityRepository;
import com.cems.api.repository.NeedCategoryRepository;
import com.cems.api.repository.SurveyQuestionRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.SurveyResponseRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.Permissions;
import com.cems.api.security.RoleName;
import com.cems.api.security.TokenHasher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Survey builder business logic (spec Module 3 §1, Pass A). Draft surveys are freely editable;
 * deploying locks the questions and issues a public access token; closing ends collection.
 * Faculty may only manage their own surveys (ownership enforced here); admin + coordinators manage
 * all. Every mutation writes the audit trail.
 */
@Service
public class SurveyService {

    private static final List<String> QUESTION_TYPES =
            List.of("rating", "multiple_choice", "checkbox", "open_text");
    private static final BigDecimal MIN_WEIGHT = new BigDecimal("0.5");
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("5.0");

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyResponseRepository responseRepository;
    private final CommunityRepository communityRepository;
    private final NeedCategoryRepository needCategoryRepository;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final Permissions permissions;
    private final TokenHasher tokenHasher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SurveyService(SurveyRepository surveyRepository,
            SurveyQuestionRepository questionRepository,
            SurveyResponseRepository responseRepository,
            CommunityRepository communityRepository,
            NeedCategoryRepository needCategoryRepository,
            UserRepository userRepository,
            ActivityLogService activityLogService,
            Permissions permissions,
            TokenHasher tokenHasher) {
        this.surveyRepository = surveyRepository;
        this.questionRepository = questionRepository;
        this.responseRepository = responseRepository;
        this.communityRepository = communityRepository;
        this.needCategoryRepository = needCategoryRepository;
        this.userRepository = userRepository;
        this.activityLogService = activityLogService;
        this.permissions = permissions;
        this.tokenHasher = tokenHasher;
    }

    // --- reads ---

    @Transactional(readOnly = true)
    public Page<SurveySummaryResponse> list(SurveyListQuery query) {
        int page = Math.max(0, query.getPage() - 1);
        int size = Math.min(100, Math.max(1, query.getPerPage()));
        Sort sort = resolveSort(query.getSort(), query.getDirection());
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Survey> surveys = surveyRepository.findAll(buildSpecification(query), pageable);
        return surveys.map(survey -> SurveySummaryResponse.fromEntity(
                survey,
                survey.getQuestions().size(),
                responseRepository.countBySurveyId(survey.getId())));
    }

    @Transactional(readOnly = true)
    public SurveyResponse getById(String id) {
        Survey survey = findActive(id);
        assertCanView(survey);
        return toResponse(survey);
    }

    // --- survey CRUD ---

    public SurveyResponse create(SurveyRequest request) {
        Community community = findCommunity(request.communityId());
        Survey survey = new Survey();
        survey.setCommunity(community);
        applyMetadata(survey, request);
        survey.setStatus(Survey.STATUS_DRAFT);
        survey.setCreatedBy(resolveCurrentUserId());
        Survey saved = surveyRepository.save(survey);
        activityLogService.record("survey.created", "survey", saved.getId(),
                Map.of("title", saved.getTitle(), "communityId", community.getId()));
        return toResponse(saved);
    }

    public SurveyResponse update(String id, SurveyRequest request) {
        Survey survey = findActive(id);
        assertCanManage(survey);
        if (survey.isClosed()) {
            throw new ConflictException("A closed survey can no longer be edited.");
        }
        // The target community is immutable once a survey is deployed (responses are tied to it).
        if (survey.isDeployed() && !survey.getCommunity().getId().equals(request.communityId())) {
            throw new ConflictException("The target community cannot change after deployment.");
        }
        if (!survey.getCommunity().getId().equals(request.communityId())) {
            survey.setCommunity(findCommunity(request.communityId()));
        }
        applyMetadata(survey, request);
        Survey saved = surveyRepository.save(survey);
        activityLogService.record("survey.updated", "survey", saved.getId(),
                Map.of("title", saved.getTitle()));
        return toResponse(saved);
    }

    public void delete(String id) {
        Survey survey = findActive(id);
        assertCanManage(survey);
        survey.setDeletedAt(Instant.now());
        surveyRepository.save(survey);
        activityLogService.record("survey.deleted", "survey", survey.getId(),
                Map.of("title", survey.getTitle()));
    }

    // --- lifecycle ---

    public SurveyResponse deploy(String id) {
        Survey survey = findActive(id);
        assertCanManage(survey);
        if (!survey.isDraft()) {
            throw new ConflictException("Only a draft survey can be deployed.");
        }
        if (survey.getQuestions().isEmpty()) {
            throw new ConflictException("Add at least one question before deploying.");
        }
        survey.setStatus(Survey.STATUS_DEPLOYED);
        survey.setAccessToken(tokenHasher.generateRawToken());
        Survey saved = surveyRepository.save(survey);
        activityLogService.record("survey.deployed", "survey", saved.getId(),
                Map.of("title", saved.getTitle()));
        return toResponse(saved);
    }

    public SurveyResponse close(String id) {
        Survey survey = findActive(id);
        assertCanManage(survey);
        if (!survey.isDeployed()) {
            throw new ConflictException("Only a deployed survey can be closed.");
        }
        survey.setStatus(Survey.STATUS_CLOSED);
        Survey saved = surveyRepository.save(survey);
        activityLogService.record("survey.closed", "survey", saved.getId(),
                Map.of("title", saved.getTitle()));
        return toResponse(saved);
    }

    // --- questions ---

    public QuestionResponse addQuestion(String surveyId, QuestionRequest request) {
        Survey survey = findActive(surveyId);
        assertCanManage(survey);
        assertQuestionsEditable(survey);

        SurveyQuestion question = new SurveyQuestion();
        question.setSurvey(survey);
        int nextIndex = request.orderIndex() != null
                ? request.orderIndex()
                : (int) questionRepository.countBySurveyId(surveyId);
        question.setOrderIndex(nextIndex);
        applyQuestion(question, request);
        SurveyQuestion saved = questionRepository.save(question);
        activityLogService.record("survey.question_added", "survey", surveyId,
                Map.of("questionId", saved.getId()));
        return toQuestionResponse(saved);
    }

    public QuestionResponse updateQuestion(String surveyId, String questionId, QuestionRequest request) {
        Survey survey = findActive(surveyId);
        assertCanManage(survey);
        assertQuestionsEditable(survey);

        SurveyQuestion question = findQuestion(surveyId, questionId);
        if (request.orderIndex() != null) {
            question.setOrderIndex(request.orderIndex());
        }
        applyQuestion(question, request);
        SurveyQuestion saved = questionRepository.save(question);
        activityLogService.record("survey.question_updated", "survey", surveyId,
                Map.of("questionId", saved.getId()));
        return toQuestionResponse(saved);
    }

    public void deleteQuestion(String surveyId, String questionId) {
        Survey survey = findActive(surveyId);
        assertCanManage(survey);
        assertQuestionsEditable(survey);
        SurveyQuestion question = findQuestion(surveyId, questionId);
        questionRepository.delete(question);
        activityLogService.record("survey.question_deleted", "survey", surveyId,
                Map.of("questionId", questionId));
    }

    // --- helpers ---

    private void applyMetadata(Survey survey, SurveyRequest request) {
        survey.setTitle(request.title().trim());
        survey.setDescription(request.description() == null ? null : request.description().trim());
        survey.setOpensAt(request.opensAt());
        survey.setClosesAt(request.closesAt());
        survey.setTargetResponses(request.targetResponses());
    }

    private void applyQuestion(SurveyQuestion question, QuestionRequest request) {
        String type = normalizeType(request.questionType());
        question.setQuestionText(request.questionText().trim());
        question.setQuestionType(type);
        question.setWeight(validateWeight(request.weight()));
        question.setRequired(request.required() == null || request.required());
        question.setNeedCategory(resolveCategory(request.needCategoryId()));
        question.setOptions(serializeOptions(type, request.options()));
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!QUESTION_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Question type must be one of: " + String.join(", ", QUESTION_TYPES) + ".");
        }
        return normalized;
    }

    private BigDecimal validateWeight(BigDecimal weight) {
        BigDecimal value = weight == null ? BigDecimal.ONE : weight;
        if (value.compareTo(MIN_WEIGHT) < 0 || value.compareTo(MAX_WEIGHT) > 0) {
            throw new IllegalArgumentException("Weight must be between 0.5 and 5.0.");
        }
        return value;
    }

    private NeedCategory resolveCategory(String needCategoryId) {
        if (needCategoryId == null || needCategoryId.isBlank()) {
            return null;
        }
        return needCategoryRepository.findById(needCategoryId)
                .orElseThrow(() -> new IllegalArgumentException("Selected need category does not exist."));
    }

    /** Serializes choice options to JSON text; requires ≥2 options for choice types, null otherwise. */
    private String serializeOptions(String type, List<QuestionOption> options) {
        boolean choiceType = type.equals("multiple_choice") || type.equals("checkbox");
        if (!choiceType) {
            return null;
        }
        if (options == null || options.size() < 2) {
            throw new IllegalArgumentException("Choice questions require at least two options.");
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not serialize question options.");
        }
    }

    private List<QuestionOption> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<QuestionOption>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void assertQuestionsEditable(Survey survey) {
        if (!survey.isDraft()) {
            throw new ConflictException("Questions can only be edited while the survey is a draft.");
        }
    }

    private SurveyResponse toResponse(Survey survey) {
        List<QuestionResponse> questions = questionRepository.findBySurveyIdOrderByOrderIndexAsc(survey.getId())
                .stream()
                .map(this::toQuestionResponse)
                .toList();
        return SurveyResponse.fromEntity(survey, questions);
    }

    private QuestionResponse toQuestionResponse(SurveyQuestion question) {
        return QuestionResponse.fromEntity(question, parseOptions(question.getOptions()));
    }

    private Community findCommunity(String communityId) {
        return communityRepository.findByIdAndDeletedAtIsNull(communityId)
                .orElseThrow(() -> new IllegalArgumentException("Target community does not exist."));
    }

    private Survey findActive(String id) {
        return surveyRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("Survey not found."));
    }

    private SurveyQuestion findQuestion(String surveyId, String questionId) {
        return questionRepository.findByIdAndSurveyId(questionId, surveyId)
                .orElseThrow(() -> new NoSuchElementException("Question not found."));
    }

    /** Coordinators/admin manage any survey; faculty manage only their own (spec §2.2). */
    private void assertCanManage(Survey survey) {
        if (isCoordinatorOrAdmin()) {
            return;
        }
        String userId = resolveCurrentUserId();
        if (userId != null && userId.equals(survey.getCreatedBy())) {
            return;
        }
        throw new AccessDeniedException("You may only manage your own surveys.");
    }

    /** Faculty may only view their own surveys; a foreign survey looks not-found (no leak). */
    private void assertCanView(Survey survey) {
        if (isCoordinatorOrAdmin() || permissions.hasRole(RoleName.CAMPUS_ADMIN)) {
            return;
        }
        String userId = resolveCurrentUserId();
        if (userId == null || !userId.equals(survey.getCreatedBy())) {
            throw new NoSuchElementException("Survey not found.");
        }
    }

    private boolean isCoordinatorOrAdmin() {
        return permissions.hasAnyRole(RoleName.ADMIN,
                RoleName.CAMPUS_EXTENSION_COORDINATOR,
                RoleName.EXTENSION_COORDINATOR);
    }

    private String resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
    }

    private Specification<Survey> buildSpecification(SurveyListQuery query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (isPresent(query.getSearch())) {
                String like = "%" + query.getSearch().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("title")), like));
            }
            if (isPresent(query.getStatus())) {
                predicates.add(cb.equal(root.get("status"), query.getStatus().trim().toLowerCase(Locale.ROOT)));
            }
            if (isPresent(query.getCommunityId())) {
                predicates.add(cb.equal(root.get("community").get("id"), query.getCommunityId().trim()));
            }
            // Faculty (without a coordinator/admin/campus-admin role) see only their own surveys.
            if (!isCoordinatorOrAdmin() && !permissions.hasRole(RoleName.CAMPUS_ADMIN)) {
                predicates.add(cb.equal(root.get("createdBy"), resolveCurrentUserId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Sort resolveSort(String requestedSort, String requestedDirection) {
        String property = switch (requestedSort == null ? "" : requestedSort.trim()) {
            case "title" -> "title";
            case "status" -> "status";
            case "updatedAt" -> "updatedAt";
            case "createdAt", "" -> "createdAt";
            default -> throw new IllegalArgumentException("Unsupported sort field.");
        };
        Sort.Direction direction = "asc".equalsIgnoreCase(requestedDirection)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
