package com.cems.api.service;

import com.cems.api.dto.ProgramApprovalResponse;
import com.cems.api.dto.ProgramDocumentResponse;
import com.cems.api.dto.ProgramListQuery;
import com.cems.api.dto.ProgramRequest;
import com.cems.api.dto.ProgramResponse;
import com.cems.api.dto.ProgramStageActionRequest;
import com.cems.api.dto.ProgramStatsResponse;
import com.cems.api.dto.ProgramSummaryResponse;
import com.cems.api.entity.AcademicPeriod;
import com.cems.api.entity.Community;
import com.cems.api.entity.Program;
import com.cems.api.entity.ProgramApproval;
import com.cems.api.entity.ProgramDocument;
import com.cems.api.entity.ProgramType;
import com.cems.api.entity.Sector;
import com.cems.api.entity.Survey;
import com.cems.api.entity.User;
import com.cems.api.repository.CommunityRepository;
import com.cems.api.repository.ProgramApprovalRepository;
import com.cems.api.repository.ProgramDocumentRepository;
import com.cems.api.repository.ProgramMemberRepository;
import com.cems.api.repository.ProgramRepository;
import com.cems.api.repository.ProgramTypeRepository;
import com.cems.api.repository.SectorRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.Permissions;
import com.cems.api.security.RoleName;
import com.cems.api.service.ProgramStateMachine.Action;
import com.cems.api.service.ProgramStateMachine.TransitionResult;
import com.cems.api.storage.StorageService;
import com.cems.api.storage.StoredFile;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.core.io.Resource;
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
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Proposal / program business logic (spec Module 5a). Layered controller → service → repository;
 * every status change is delegated to {@link ProgramStateMachine} and every mutation writes the
 * audit trail via {@link ActivityLogService}.
 *
 * <p>Two access rules run throughout and are easy to confuse:
 * <ul>
 *   <li><b>Visibility</b> — faculty and student volunteers see only proposals they created or lead
 *       ({@code createdBy} OR {@code facultyLeadId}); everyone else sees all. Applied in the
 *       {@link Specification} so it cannot be bypassed by a list filter.</li>
 *   <li><b>Editability</b> — a proposal is editable by its owner only while {@code draft} or
 *       {@code returned}. Once submitted it is read-only to faculty (spec Module 5 AC 2).</li>
 * </ul>
 */
@Service
public class ProgramService {

    private static final List<String> ALLOWED_DOC_TYPES = List.of(
            "letter_request", "moa", "budget_breakdown", "needs_assessment_report", "photo", "other");

    /** The three statuses collapsed behind the list's "Under Review" tab. */
    private static final List<String> UNDER_REVIEW_STATUSES = List.of(
            Program.STATUS_SUBMITTED,
            Program.STATUS_COORDINATOR_REVIEW,
            Program.STATUS_RECOMMENDING_APPROVAL);

    private final ProgramRepository programRepository;
    private final ProgramApprovalRepository approvalRepository;
    private final ProgramDocumentRepository documentRepository;
    private final CommunityRepository communityRepository;
    private final ProgramTypeRepository programTypeRepository;
    private final SurveyRepository surveyRepository;
    private final SectorRepository sectorRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final ProgramStateMachine stateMachine;
    private final Permissions permissions;
    private final ProgramAccessPolicy accessPolicy;
    private final ProgramMemberRepository memberRepository;
    private final AcademicPeriodService academicPeriodService;

    public ProgramService(ProgramRepository programRepository,
            ProgramApprovalRepository approvalRepository,
            ProgramDocumentRepository documentRepository,
            CommunityRepository communityRepository,
            ProgramTypeRepository programTypeRepository,
            SurveyRepository surveyRepository,
            SectorRepository sectorRepository,
            UserRepository userRepository,
            StorageService storageService,
            ActivityLogService activityLogService,
            ProgramStateMachine stateMachine,
            Permissions permissions,
            ProgramAccessPolicy accessPolicy,
            ProgramMemberRepository memberRepository,
            AcademicPeriodService academicPeriodService) {
        this.programRepository = programRepository;
        this.approvalRepository = approvalRepository;
        this.documentRepository = documentRepository;
        this.communityRepository = communityRepository;
        this.programTypeRepository = programTypeRepository;
        this.surveyRepository = surveyRepository;
        this.sectorRepository = sectorRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.stateMachine = stateMachine;
        this.permissions = permissions;
        this.accessPolicy = accessPolicy;
        this.memberRepository = memberRepository;
        this.academicPeriodService = academicPeriodService;
    }

    // --- reads ---

    @Transactional(readOnly = true)
    public Page<ProgramSummaryResponse> list(ProgramListQuery query) {
        int page = Math.max(0, query.getPage() - 1);
        int pageSize = Math.min(100, Math.max(1, query.getPerPage()));
        Sort sort = resolveSort(query.getSort(), query.getDirection());
        // Resolved once, here, rather than inside the specification lambda: that lambda runs on both
        // the count query and the data query, and again on the out-of-range retry below, so looking
        // the period up inside it would repeat the same read up to three times per list call. It
        // also means an unknown period id raises its 404 before any program query is built.
        Specification<Program> specification = buildSpecification(
                query, academicPeriodService.resolveRequested(query.getPeriodId()).orElse(null));
        Pageable pageable = PageRequest.of(page, pageSize, sort);

        Page<Program> programs = programRepository.findAll(specification, pageable);
        if (programs.isEmpty() && programs.getTotalPages() > 0 && page >= programs.getTotalPages()) {
            programs = programRepository.findAll(specification,
                    PageRequest.of(programs.getTotalPages() - 1, pageSize, sort));
        }
        Map<String, String> names = resolveUserNames(programs.getContent().stream()
                .map(Program::getFacultyLeadId)
                .toList());
        return programs.map(program ->
                ProgramSummaryResponse.fromEntity(program, names.get(program.getFacultyLeadId())));
    }

    /**
     * Status counts for the list screen's tab badges.
     *
     * @param periodId scopes the counts exactly as {@link #list} scopes its rows. Pass the same
     *                 value the list was called with, or the badges will describe a different set of
     *                 programs than the ones on screen.
     */
    @Transactional(readOnly = true)
    public ProgramStatsResponse getStats(String periodId) {
        // Faculty/student counts are scoped exactly as the list is — created, led, or assigned —
        // so the tab badges can never disagree with the rows they sit above. The sentinel keeps the
        // IN clause valid when the user is assigned to nothing; no program id is ever an empty string.
        String ownerId = restrictedToOwnPrograms() ? resolveCurrentUserId() : null;
        List<String> assignedIds = ownerId == null
                ? List.of("")
                : memberRepository.findProgramIdsByUserId(ownerId);
        if (assignedIds.isEmpty()) {
            assignedIds = List.of("");
        }
        AcademicPeriod period = academicPeriodService.resolveRequested(periodId).orElse(null);
        LocalDate startsOn = period == null ? null : period.getStartsOn();
        LocalDate endsOn = period == null ? null : period.getEndsOn();

        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : (startsOn == null
                ? programRepository.countByStatusForOwner(ownerId, assignedIds)
                : programRepository.countByStatusForOwnerAndPeriod(ownerId, assignedIds, startsOn, endsOn))) {
            counts.put((String) row[0], (Long) row[1]);
        }
        long underReview = UNDER_REVIEW_STATUSES.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new ProgramStatsResponse(
                total,
                counts.getOrDefault(Program.STATUS_DRAFT, 0L),
                underReview,
                counts.getOrDefault(Program.STATUS_RETURNED, 0L),
                counts.getOrDefault(Program.STATUS_APPROVED, 0L),
                counts.getOrDefault(Program.STATUS_ONGOING, 0L),
                counts.getOrDefault(Program.STATUS_COMPLETED, 0L),
                counts.getOrDefault(Program.STATUS_CANCELLED, 0L));
    }

    @Transactional(readOnly = true)
    public ProgramResponse getById(String id) {
        Program program = findActive(id);
        assertCanView(program);
        return toResponse(program);
    }

    @Transactional(readOnly = true)
    public List<ProgramApprovalResponse> getApprovals(String id) {
        Program program = findActive(id);
        assertCanView(program);
        return loadApprovals(id);
    }

    // --- writes ---

    public ProgramResponse create(ProgramRequest request) {
        Program program = new Program();
        applyRequest(program, request);
        program.setCreatedBy(resolveCurrentUserId());
        // A proposal with no explicit lead is led by whoever drafted it.
        if (program.getFacultyLeadId() == null) {
            program.setFacultyLeadId(resolveCurrentUserId());
        }
        Program saved = programRepository.save(program);

        activityLogService.record("program.created", "program", saved.getId(),
                Map.of("title", saved.getTitle(), "status", saved.getStatus()));
        return toResponse(saved);
    }

    public ProgramResponse update(String id, ProgramRequest request) {
        Program program = findActive(id);
        assertCanEdit(program);
        applyRequest(program, request);
        Program saved = programRepository.save(program);

        activityLogService.record("program.updated", "program", saved.getId(),
                Map.of("title", saved.getTitle(), "status", saved.getStatus()));
        return toResponse(saved);
    }

    /** Soft-deletes a proposal. Only drafts and returned proposals may be withdrawn this way. */
    public void delete(String id) {
        Program program = findActive(id);
        assertCanEdit(program);
        program.setDeletedAt(java.time.Instant.now());
        programRepository.save(program);
        activityLogService.record("program.deleted", "program", program.getId(),
                Map.of("title", program.getTitle()));
    }

    // --- approval chain (spec Module 5) ---

    /**
     * Stage 1 — the owning faculty submits a draft or a returned proposal. Full-field validation
     * happens here, not at save time, so partial drafts remain saveable.
     */
    public ProgramResponse submit(String id) {
        Program program = findActive(id);
        assertCanEdit(program); // submitting is an owner action, same rule as editing
        assertSubmittable(program);
        return applyStageAction(program, Action.SUBMIT, null, null, actorRoleForSubmit());
    }

    /** Stage 2 — extension coordinator notes the proposal onward, or returns it. */
    public ProgramResponse review(String id, ProgramStageActionRequest request) {
        return actOnChain(id, request, Action.NOTE, RoleName.EXTENSION_COORDINATOR);
    }

    /** Stage 3 — campus extension coordinator recommends it for approval, or returns it. */
    public ProgramResponse recommend(String id, ProgramStageActionRequest request) {
        return actOnChain(id, request, Action.RECOMMEND, RoleName.CAMPUS_EXTENSION_COORDINATOR);
    }

    /** Stage 4 — campus administrator approves it, or returns it. */
    public ProgramResponse approve(String id, ProgramStageActionRequest request) {
        return actOnChain(id, request, Action.APPROVE, RoleName.CAMPUS_ADMIN);
    }

    /**
     * Shared body of the three review endpoints.
     *
     * <p>Order matters and is deliberate: the <b>status</b> check runs before the <b>stage-owner</b>
     * check. A coordinator hitting the right endpoint for the wrong stage gets 409 ("this proposal
     * has moved on"), which is more truthful than 403 — they do hold the role, the proposal simply
     * is not theirs to act on any more. A caller without the role at all gets 403 from the
     * controller's {@code @PreAuthorize} before reaching here.
     */
    private ProgramResponse actOnChain(String id,
            ProgramStageActionRequest request,
            Action advanceAction,
            RoleName stageRole) {
        Program program = findActive(id);
        Action action = request != null && request.isReturn() ? Action.RETURN : advanceAction;
        String comment = request == null ? null : request.comment();

        // Resolve first: an illegal status raises 409 (and a return without a comment, 422).
        TransitionResult result = stateMachine.resolve(program, action, stageRole, comment);

        // Right role, right status — but is this actor's role the one that owns THIS stage?
        if (!stateMachine.canActOnCurrentStage(program, stageRole)) {
            throw new AccessDeniedException("This proposal is not awaiting your action.");
        }

        BigDecimal budgetApproved = request == null ? null : request.budgetApproved();
        if (action == Action.APPROVE && budgetApproved != null) {
            program.setBudgetApproved(budgetApproved);
        }
        return applyStageAction(program, action, comment, result, stageRole);
    }

    /** Applies a resolved (or freshly resolved) transition, writes the audit row, and logs it. */
    private ProgramResponse applyStageAction(Program program,
            Action action,
            String comment,
            TransitionResult resolved,
            RoleName actorRole) {
        TransitionResult result = resolved != null
                ? resolved
                : stateMachine.resolve(program, action, actorRole, comment);

        String fromStatus = program.getStatus();
        stateMachine.apply(program, result);
        Program saved = programRepository.save(program);

        if (result.auditAction() != null) {
            ProgramApproval approval = new ProgramApproval();
            approval.setProgram(saved);
            approval.setStage(result.stage());
            approval.setStageRole(result.stageRole());
            approval.setAction(result.auditAction());
            approval.setActedBy(resolveCurrentUserId());
            approval.setComment(comment == null || comment.isBlank() ? null : comment.trim());
            approvalRepository.save(approval);
        }

        activityLogService.record("program." + action.name().toLowerCase(Locale.ROOT),
                "program", saved.getId(),
                Map.of("title", saved.getTitle(), "from", fromStatus, "to", saved.getStatus()));

        return toResponse(saved);
    }

    // --- documents ---

    @Transactional(readOnly = true)
    public List<ProgramDocumentResponse> listDocuments(String programId) {
        Program program = findActive(programId);
        assertCanView(program);
        return documentRepository.findByProgramIdOrderByCreatedAtDesc(programId).stream()
                .map(ProgramDocumentResponse::fromEntity)
                .toList();
    }

    public ProgramDocumentResponse addDocument(String programId, MultipartFile file, String docType) {
        Program program = findActive(programId);
        assertCanEdit(program);
        String normalizedType = normalizeDocType(docType);
        StoredFile stored = storageService.store(file, "programs/" + programId);

        ProgramDocument document = new ProgramDocument();
        document.setProgram(program);
        document.setFilePath(stored.path());
        document.setOriginalFilename(stored.originalFilename());
        document.setMimeType(stored.mimeType() == null ? "application/octet-stream" : stored.mimeType());
        document.setSizeBytes(stored.sizeBytes());
        document.setDocType(normalizedType);
        document.setUploadedBy(resolveCurrentUserId());
        ProgramDocument saved = documentRepository.save(document);

        activityLogService.record("program.document_added", "program", programId,
                Map.of("documentId", saved.getId(), "filename", saved.getOriginalFilename()));
        return ProgramDocumentResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public DocumentDownload loadDocument(String programId, String documentId) {
        Program program = findActive(programId);
        assertCanView(program); // reviewers must be able to read attachments they are ruling on
        ProgramDocument document = findDocument(programId, documentId);
        Resource resource = storageService.load(document.getFilePath());
        return new DocumentDownload(resource, document.getOriginalFilename(),
                document.getMimeType(), document.getSizeBytes());
    }

    public void deleteDocument(String programId, String documentId) {
        Program program = findActive(programId);
        assertCanEdit(program);
        ProgramDocument document = findDocument(programId, documentId);
        storageService.delete(document.getFilePath());
        documentRepository.delete(document);
        activityLogService.record("program.document_deleted", "program", programId,
                Map.of("documentId", documentId, "filename", document.getOriginalFilename()));
    }

    /** Bytes + metadata for a document download (Resource is a Spring type, kept out of the DTO layer). */
    public record DocumentDownload(Resource resource, String filename, String mimeType, long sizeBytes) {
    }

    // --- provenance (Module 4 → Module 5) ---

    /**
     * Creates the pre-filled draft proposal a coordinator gets when they accept or modify a
     * recommendation (spec Module 4 §3 → Module 5). Called from {@code RecommendationService}
     * inside the same transaction as the decision, so a failure here rolls the decision back too.
     *
     * <p>Deliberately bypasses {@link #assertCanEdit}: the caller has already been authorized by
     * {@code canDecideRecommendations()}, and the resulting proposal is a draft owned by them.
     */
    public Program createFromRecommendation(com.cems.api.entity.Recommendation recommendation) {
        Survey survey = recommendation.getSurvey();
        ProgramType programType = recommendation.getProgramType();

        Program program = new Program();
        program.setTitle(programType.getName() + " — " + survey.getCommunity().getName());
        program.setCommunity(survey.getCommunity());
        program.setProgramType(programType);
        program.setRecommendation(recommendation);
        program.setSurvey(survey);
        program.setObjectives(programType.getDescription());
        program.setSectors(new HashSet<>(survey.getCommunity().getSectors()));
        program.setCreatedBy(resolveCurrentUserId());
        program.setFacultyLeadId(resolveCurrentUserId());

        Program saved = programRepository.save(program);
        activityLogService.record("program.created_from_recommendation", "program", saved.getId(),
                Map.of("recommendationId", recommendation.getId(),
                        "programType", programType.getName(),
                        "surveyId", survey.getId()));
        return saved;
    }

    // --- community integration (closes the Phase 1 stubs) ---

    /** True when the community still has non-cancelled programs, which blocks its deletion (409). */
    @Transactional(readOnly = true)
    public boolean hasBlockingPrograms(String communityId) {
        return programRepository.existsByCommunityIdAndDeletedAtIsNullAndStatusNot(
                communityId, Program.STATUS_CANCELLED);
    }

    // --- helpers ---

    private void applyRequest(Program program, ProgramRequest request) {
        program.setTitle(trimmed(request.title()));
        program.setCommunity(resolveCommunity(request.communityId()));
        program.setProgramType(resolveProgramType(request.programTypeId()));
        program.setSurvey(resolveSurvey(request.surveyId()));
        program.setObjectives(trimmedOrNull(request.objectives()));
        program.setTargetBeneficiaries(request.targetBeneficiaries());
        program.setProposedDate(request.proposedDate());
        program.setEndDate(request.endDate());
        program.setVenue(trimmedOrNull(request.venue()));
        program.setBudgetRequested(request.budgetRequested());
        program.setSectors(resolveSectors(request.sectorIds()));

        // A proposal must never be left leaderless by an edit that simply did not mention the lead.
        // An omitted facultyLeadId keeps whoever is already leading (create() defaults it to the
        // drafter); only an explicit id reassigns it. Reassignment is therefore always deliberate.
        String requestedLead = resolveFacultyLead(request.facultyLeadId());
        if (requestedLead != null) {
            program.setFacultyLeadId(requestedLead);
        }

        if (program.getEndDate() != null && program.getProposedDate() != null
                && program.getEndDate().isBefore(program.getProposedDate())) {
            throw new IllegalArgumentException("End date cannot be earlier than the proposed start date.");
        }
    }

    /**
     * Full validation applied only at submit time (spec Module 5 AC 2: "Save Draft — minimal
     * validation: title only; Submit for Review — full validation").
     */
    private void assertSubmittable(Program program) {
        Map<String, String> missing = new java.util.LinkedHashMap<>();
        if (program.getCommunity() == null) {
            missing.put("communityId", "A target community is required.");
        }
        if (program.getProgramType() == null) {
            missing.put("programTypeId", "A program type is required.");
        }
        if (isBlank(program.getObjectives())) {
            missing.put("objectives", "Objectives are required.");
        }
        if (program.getTargetBeneficiaries() == null || program.getTargetBeneficiaries() <= 0) {
            missing.put("targetBeneficiaries", "A target beneficiary count is required.");
        }
        if (program.getProposedDate() == null) {
            missing.put("proposedDate", "A proposed date is required.");
        }
        if (program.getFacultyLeadId() == null) {
            missing.put("facultyLeadId", "A faculty lead is required.");
        }
        if (program.getBudgetRequested() == null) {
            missing.put("budgetRequested", "A requested budget is required.");
        }
        if (!missing.isEmpty()) {
            throw new com.cems.api.exception.ValidationException(
                    "This proposal is not ready to submit.", missing);
        }
    }

    private ProgramResponse toResponse(Program program) {
        List<ProgramDocumentResponse> documents =
                documentRepository.findByProgramIdOrderByCreatedAtDesc(program.getId()).stream()
                        .map(ProgramDocumentResponse::fromEntity)
                        .toList();
        return ProgramResponse.fromEntity(
                program,
                program.getFacultyLeadId() == null ? null
                        : resolveUserNames(List.of(program.getFacultyLeadId())).get(program.getFacultyLeadId()),
                documents,
                loadApprovals(program.getId()),
                availableActionsFor(program),
                canEditQuietly(program),
                accessPolicy.canDeliverQuietly(program),
                computeWarnings(program));
    }

    private List<ProgramApprovalResponse> loadApprovals(String programId) {
        List<ProgramApproval> approvals = approvalRepository.findByProgramIdOrderByActedAtAsc(programId);
        Map<String, String> names = resolveUserNames(approvals.stream()
                .map(ProgramApproval::getActedBy)
                .toList());
        return approvals.stream()
                .map(approval -> ProgramApprovalResponse.fromEntity(approval, names.get(approval.getActedBy())))
                .toList();
    }

    /**
     * Which actions the CURRENT user may take right now. The frontend renders its buttons from this
     * rather than re-implementing the state machine, so client and server cannot drift apart.
     */
    private List<String> availableActionsFor(Program program) {
        List<String> actions = new ArrayList<>();
        if (canEditQuietly(program)) {
            actions.add("edit");
            if (stateMachine.legalActionsFrom(program.getStatus()).contains(Action.SUBMIT)) {
                actions.add("submit");
            }
        }
        RoleName owner = stateMachine.stageOwner(program);
        if (owner != null && permissions.hasRole(owner)) {
            switch (owner) {
                case EXTENSION_COORDINATOR -> actions.add("note");
                case CAMPUS_EXTENSION_COORDINATOR -> actions.add("recommend");
                case CAMPUS_ADMIN -> actions.add("approve");
                default -> { /* no other role owns a stage */ }
            }
            actions.add("return");
        }
        return List.copyOf(actions);
    }

    /** Non-blocking notices surfaced on the detail screen (spec Module 5 AC 2). */
    private List<String> computeWarnings(Program program) {
        List<String> warnings = new ArrayList<>();
        boolean hasAssessment = program.getSurvey() != null
                || documentRepository.existsByProgramIdAndDocType(program.getId(), "needs_assessment_report");
        if (!hasAssessment) {
            warnings.add("No needs assessment is linked to this proposal. "
                    + "Extension proposals are expected to cite the assessment that justifies them.");
        }
        return warnings;
    }

    /** Coordinators/admin manage any proposal; faculty manage only their own, and only while open. */
    private void assertCanEdit(Program program) {
        if (!isOwner(program) && !isCoordinatorOrAdmin()) {
            throw new AccessDeniedException("You may only edit proposals you created or lead.");
        }
        if (!program.isEditableByOwner()) {
            throw new com.cems.api.exception.ConflictException(
                    "This proposal is " + program.getStatus().replace('_', ' ')
                            + " and can no longer be edited. It must be returned first.");
        }
    }

    /** Same rule as {@link #assertCanEdit} but as a boolean, for the response's {@code canEdit} flag. */
    private boolean canEditQuietly(Program program) {
        return (isOwner(program) || isCoordinatorOrAdmin()) && program.isEditableByOwner();
    }

    // The visibility rules below live in ProgramAccessPolicy so the delivery phase (activities,
    // attendance, evaluations) shares one definition of "owner" and "restricted to their own".
    // Only the proposal-phase STATUS rule stays here — see assertCanEdit above, and the policy's
    // class javadoc for why the two phases are deliberately opposite.

    /** Faculty/volunteers may only view their own proposals; a foreign one looks not-found (no leak). */
    private void assertCanView(Program program) {
        accessPolicy.assertCanView(program);
    }

    private boolean isOwner(Program program) {
        return accessPolicy.isOwner(program);
    }

    private boolean isCoordinatorOrAdmin() {
        return accessPolicy.isCoordinatorOrAdmin();
    }

    /** Faculty and student volunteers see only what they created or lead (spec Module 5 §1). */
    private boolean restrictedToOwnPrograms() {
        return accessPolicy.restrictedToOwnPrograms();
    }

    /** The role recorded on a stage-1 audit row: the submitter is acting as project leader. */
    private RoleName actorRoleForSubmit() {
        return RoleName.FACULTY;
    }

    private Community resolveCommunity(String communityId) {
        if (isBlank(communityId)) {
            return null;
        }
        return communityRepository.findByIdAndDeletedAtIsNull(communityId.trim())
                .orElseThrow(() -> new IllegalArgumentException("The selected community does not exist."));
    }

    private ProgramType resolveProgramType(String programTypeId) {
        if (isBlank(programTypeId)) {
            return null;
        }
        return programTypeRepository.findById(programTypeId.trim())
                .orElseThrow(() -> new IllegalArgumentException("The selected program type does not exist."));
    }

    private Survey resolveSurvey(String surveyId) {
        if (isBlank(surveyId)) {
            return null;
        }
        return surveyRepository.findByIdAndDeletedAtIsNull(surveyId.trim())
                .orElseThrow(() -> new IllegalArgumentException("The selected needs assessment does not exist."));
    }

    private String resolveFacultyLead(String facultyLeadId) {
        if (isBlank(facultyLeadId)) {
            return null;
        }
        String trimmed = facultyLeadId.trim();
        if (!userRepository.existsById(trimmed)) {
            throw new IllegalArgumentException("The selected faculty lead does not exist.");
        }
        return trimmed;
    }

    private Set<Sector> resolveSectors(Set<String> sectorIds) {
        if (sectorIds == null || sectorIds.isEmpty()) {
            return new HashSet<>();
        }
        List<Sector> found = sectorRepository.findByIdIn(sectorIds);
        if (found.size() != sectorIds.size()) {
            throw new IllegalArgumentException("One or more selected sectors do not exist.");
        }
        return new HashSet<>(found);
    }

    private String normalizeDocType(String docType) {
        if (docType == null || docType.isBlank()) {
            return "other";
        }
        String normalized = docType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_DOC_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Document type must be one of: " + String.join(", ", ALLOWED_DOC_TYPES) + ".");
        }
        return normalized;
    }

    private Program findActive(String id) {
        return programRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("Program not found."));
    }

    private ProgramDocument findDocument(String programId, String documentId) {
        return documentRepository.findByIdAndProgramId(documentId, programId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
    }

    /** Resolves display names for a batch of user ids in one query (avoids N+1 on list screens). */
    private Map<String, String> resolveUserNames(List<String> userIds) {
        Set<String> ids = new HashSet<>(userIds.stream().filter(java.util.Objects::nonNull).toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), displayName(user));
        }
        return names;
    }

    private String displayName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isBlank() ? user.getEmail() : name;
    }

    private String resolveCurrentUserId() {
        return accessPolicy.currentUserId();
    }

    private Specification<Program> buildSpecification(ProgramListQuery query, AcademicPeriod period) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (isPresent(query.getSearch())) {
                String like = "%" + query.getSearch().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("venue")), like)));
            }
            if (isPresent(query.getStatus())) {
                String status = query.getStatus().trim().toLowerCase(Locale.ROOT);
                if (ProgramListQuery.STATUS_UNDER_REVIEW.equals(status)) {
                    predicates.add(root.get("status").in(UNDER_REVIEW_STATUSES));
                } else {
                    predicates.add(cb.equal(root.get("status"), status));
                }
            }
            if (isPresent(query.getCommunityId())) {
                predicates.add(cb.equal(root.get("community").get("id"), query.getCommunityId().trim()));
            }
            if (isPresent(query.getProgramTypeId())) {
                predicates.add(cb.equal(root.get("programType").get("id"), query.getProgramTypeId().trim()));
            }
            if (isPresent(query.getFacultyLeadId())) {
                predicates.add(cb.equal(root.get("facultyLeadId"), query.getFacultyLeadId().trim()));
            }
            // Academic period (spec Module 6 AC 6: every dashboard count must open the list behind
            // it). This clause must stay identical to the one the dashboard aggregates use in
            // ProgramRepository — a program belongs to a period when its proposed date falls in it,
            // and a program without a proposed date belongs to no period. If these two ever diverge,
            // a KPI reading 12 opens a list of 11 and the whole screen loses its credibility.
            if (period != null) {
                Path<LocalDate> proposedDate = root.get("proposedDate");
                predicates.add(cb.isNotNull(proposedDate));
                predicates.add(cb.between(proposedDate, period.getStartsOn(), period.getEndsOn()));
            }
            // Faculty and student volunteers see what they created, lead, or are assigned to
            // (spec Module 5 §1 "own + assigned"). The assigned ids are fetched once and folded in
            // as an IN clause rather than correlated per row.
            if (restrictedToOwnPrograms()) {
                String userId = resolveCurrentUserId();
                List<Predicate> visibility = new ArrayList<>();
                visibility.add(cb.equal(root.get("createdBy"), userId));
                visibility.add(cb.equal(root.get("facultyLeadId"), userId));
                List<String> assignedIds = memberRepository.findProgramIdsByUserId(userId);
                if (!assignedIds.isEmpty()) {
                    visibility.add(root.get("id").in(assignedIds));
                }
                predicates.add(cb.or(visibility.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Sort resolveSort(String requestedSort, String requestedDirection) {
        String property = switch (requestedSort == null ? "" : requestedSort.trim()) {
            case "title" -> "title";
            case "status" -> "status";
            case "proposedDate" -> "proposedDate";
            case "updatedAt" -> "updatedAt";
            case "createdAt", "" -> "createdAt";
            default -> throw new IllegalArgumentException("Unsupported sort field.");
        };
        Sort.Direction direction = "asc".equalsIgnoreCase(requestedDirection)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, property);
        return "title".equals(property) ? sort : sort.and(Sort.by(Sort.Direction.ASC, "title"));
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
