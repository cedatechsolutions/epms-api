package com.cems.api.service;

import com.cems.api.dto.EvaluationRequest;
import com.cems.api.dto.EvaluationResponse;
import com.cems.api.entity.Evaluation;
import com.cems.api.entity.ProgramActivity;
import com.cems.api.exception.ValidationException;
import com.cems.api.repository.EvaluationRepository;
import com.cems.api.storage.StorageService;
import com.cems.api.storage.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Encoded pre/post evaluation summaries (spec Module 5b §5).
 *
 * <p>Encoding a <b>post</b> evaluation is one of the two preconditions for a program reaching
 * {@code completed}, so every write here re-runs {@link ProgramActivityService#syncProgramStatus} —
 * the last post-eval on a program whose activities are all done is exactly the event that completes
 * it, and requiring a separate button would leave programs sitting finished-but-not-marked.
 */
@Service
@Transactional
public class EvaluationService {

    private final EvaluationRepository evaluationRepository;
    private final ProgramActivityService activityService;
    private final ProgramAccessPolicy accessPolicy;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;

    public EvaluationService(EvaluationRepository evaluationRepository,
            ProgramActivityService activityService,
            ProgramAccessPolicy accessPolicy,
            StorageService storageService,
            ActivityLogService activityLogService) {
        this.evaluationRepository = evaluationRepository;
        this.activityService = activityService;
        this.accessPolicy = accessPolicy;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
    }

    @Transactional(readOnly = true)
    public List<EvaluationResponse> list(String activityId) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanView(activity.getProgram());
        return evaluationRepository.findByProgramActivityIdOrderByCreatedAtAsc(activityId).stream()
                .map(EvaluationResponse::fromEntity)
                .toList();
    }

    public EvaluationResponse create(String activityId, EvaluationRequest request) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanDeliver(activity.getProgram());

        Evaluation evaluation = new Evaluation();
        evaluation.setProgramActivity(activity);
        applyRequest(evaluation, request);
        evaluation.setEncodedBy(accessPolicy.currentUserId());
        Evaluation saved = evaluationRepository.save(evaluation);

        activityLogService.record("evaluation.encoded", "program_activity", activityId,
                Map.of("evaluationId", saved.getId(),
                        "evalType", saved.getEvalType(),
                        "respondents", saved.getRespondentCount()));

        // A post-eval may be the last thing standing between the program and 'completed'.
        activityService.syncProgramStatus(activity.getProgram());
        return EvaluationResponse.fromEntity(saved);
    }

    public EvaluationResponse update(String activityId, String evaluationId, EvaluationRequest request) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanDeliver(activity.getProgram());

        Evaluation evaluation = findOwned(activityId, evaluationId);
        applyRequest(evaluation, request);
        Evaluation saved = evaluationRepository.save(evaluation);

        activityLogService.record("evaluation.updated", "program_activity", activityId,
                Map.of("evaluationId", saved.getId(), "evalType", saved.getEvalType()));

        activityService.syncProgramStatus(activity.getProgram());
        return EvaluationResponse.fromEntity(saved);
    }

    public void delete(String activityId, String evaluationId) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanDeliver(activity.getProgram());

        Evaluation evaluation = findOwned(activityId, evaluationId);
        if (evaluation.getFilePath() != null) {
            storageService.delete(evaluation.getFilePath());
        }
        evaluationRepository.delete(evaluation);

        activityLogService.record("evaluation.deleted", "program_activity", activityId,
                Map.of("evaluationId", evaluationId));

        activityService.syncProgramStatus(activity.getProgram());
    }

    // --- the optional scanned instrument ---

    /** Attaches (or replaces) the scanned evaluation form. Replacing deletes the previous bytes. */
    public EvaluationResponse attachFile(String activityId, String evaluationId, MultipartFile file) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanDeliver(activity.getProgram());

        Evaluation evaluation = findOwned(activityId, evaluationId);
        StoredFile stored = storageService.store(file, "evaluations/" + evaluationId);

        String previousPath = evaluation.getFilePath();
        evaluation.setFilePath(stored.path());
        evaluation.setOriginalFilename(stored.originalFilename());
        evaluation.setMimeType(stored.mimeType() == null ? "application/octet-stream" : stored.mimeType());
        evaluation.setSizeBytes(stored.sizeBytes());
        Evaluation saved = evaluationRepository.save(evaluation);

        // Only after the replacement is persisted — a delete-then-fail would lose the original.
        if (previousPath != null) {
            storageService.delete(previousPath);
        }

        activityLogService.record("evaluation.file_attached", "program_activity", activityId,
                Map.of("evaluationId", evaluationId, "filename", stored.originalFilename()));
        return EvaluationResponse.fromEntity(saved);
    }

    /** Bytes of the scanned instrument, for the authorized download route. */
    @Transactional(readOnly = true)
    public FileDownload loadFile(String activityId, String evaluationId) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanView(activity.getProgram());

        Evaluation evaluation = findOwned(activityId, evaluationId);
        if (evaluation.getFilePath() == null) {
            throw new NoSuchElementException("This evaluation has no attached file.");
        }
        activityLogService.record("evaluation.file_downloaded", "program_activity", activityId,
                Map.of("evaluationId", evaluationId));
        return new FileDownload(
                storageService.load(evaluation.getFilePath()),
                evaluation.getOriginalFilename(),
                evaluation.getMimeType() == null ? "application/octet-stream" : evaluation.getMimeType(),
                evaluation.getSizeBytes() == null ? 0L : evaluation.getSizeBytes());
    }

    public record FileDownload(Resource resource, String filename, String mimeType, long sizeBytes) {
    }

    // --- validation ---

    /**
     * Applies and validates the payload.
     *
     * <p>The cross-field rule is checked here rather than by annotation so the 422 can name both
     * offending fields and quote the numbers: "18 female + 14 male exceeds 30 respondents" is
     * actionable, "invalid" is not.
     */
    private void applyRequest(Evaluation evaluation, EvaluationRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String evalType = request.evalType() == null ? "" : request.evalType().trim().toLowerCase(Locale.ROOT);
        if (!Evaluation.TYPE_PRE.equals(evalType) && !Evaluation.TYPE_POST.equals(evalType)) {
            errors.put("evalType", "Evaluation type must be either pre or post.");
        }

        int respondents = request.respondentCount() == null ? 0 : request.respondentCount();
        int female = request.femaleCount() == null ? 0 : request.femaleCount();
        int male = request.maleCount() == null ? 0 : request.maleCount();

        if (request.respondentCount() == null) {
            errors.put("respondentCount", "A respondent count is required.");
        }
        if (female + male > respondents) {
            String message = female + " female + " + male + " male exceeds the "
                    + respondents + " respondents recorded.";
            errors.put("femaleCount", message);
            errors.put("maleCount", message);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("This evaluation could not be saved.", errors);
        }

        evaluation.setEvalType(evalType);
        evaluation.setRespondentCount(respondents);
        evaluation.setFemaleCount(female);
        evaluation.setMaleCount(male);
        evaluation.setAvgRating(request.avgRating());
        evaluation.setNotes(request.notes() == null || request.notes().isBlank()
                ? null
                : request.notes().trim());
    }

    private Evaluation findOwned(String activityId, String evaluationId) {
        return evaluationRepository.findByIdAndProgramActivityId(evaluationId, activityId)
                .orElseThrow(() -> new NoSuchElementException("Evaluation not found."));
    }
}
