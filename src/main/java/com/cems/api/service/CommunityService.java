package com.cems.api.service;

import com.cems.api.dto.CommunityDocumentResponse;
import com.cems.api.dto.CommunityHistoryEntry;
import com.cems.api.dto.CommunityListQuery;
import com.cems.api.dto.CommunityRequest;
import com.cems.api.dto.CommunityResponse;
import com.cems.api.dto.CommunityStatsResponse;
import com.cems.api.dto.CommunitySummaryResponse;
import com.cems.api.entity.Community;
import com.cems.api.entity.CommunityDocument;
import com.cems.api.entity.Sector;
import com.cems.api.entity.User;
import com.cems.api.repository.CommunityDocumentRepository;
import com.cems.api.repository.CommunityRepository;
import com.cems.api.repository.SectorRepository;
import com.cems.api.repository.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.cems.api.storage.StorageService;
import com.cems.api.storage.StoredFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Community profiling business logic (spec Module 2). Layered controller → service → repository;
 * soft-deletes, sector tagging, GAD-integrity warnings, and document storage live here.
 * Every mutation writes the audit trail via {@link ActivityLogService}.
 */
@Service
public class CommunityService {

    private static final List<String> ALLOWED_CLASSIFICATIONS =
            List.of("urban_poor", "rural", "urban", "coastal", "other");
    private static final List<String> ALLOWED_DOC_TYPES =
            List.of("moa", "certification", "photo", "other");

    /** GAD-integrity threshold: warn when |male+female − estimate| exceeds this fraction of the estimate. */
    private static final double POPULATION_SPLIT_TOLERANCE = 0.10;

    private final CommunityRepository communityRepository;
    private final CommunityDocumentRepository documentRepository;
    private final SectorRepository sectorRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;

    public CommunityService(CommunityRepository communityRepository,
            CommunityDocumentRepository documentRepository,
            SectorRepository sectorRepository,
            UserRepository userRepository,
            StorageService storageService,
            ActivityLogService activityLogService) {
        this.communityRepository = communityRepository;
        this.documentRepository = documentRepository;
        this.sectorRepository = sectorRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
    }

    // --- reads ---

    @Transactional(readOnly = true)
    public CommunityStatsResponse getStats() {
        long total = communityRepository.countByDeletedAtIsNull();
        // beneficiariesReached (Phase 5) + assessedThisSemester (Phase 2) are 0 until those modules land.
        return new CommunityStatsResponse(total, 0, 0);
    }

    @Transactional(readOnly = true)
    public Page<CommunitySummaryResponse> list(CommunityListQuery query) {
        int page = normalizePage(query.getPage());
        int pageSize = normalizePageSize(query.getPerPage());
        Sort sort = resolveSort(query.getSort(), query.getDirection());
        Specification<Community> specification = buildSpecification(query);
        Pageable pageable = PageRequest.of(page, pageSize, sort);

        Page<Community> communities = communityRepository.findAll(specification, pageable);
        if (communities.isEmpty() && communities.getTotalPages() > 0 && page >= communities.getTotalPages()) {
            communities = communityRepository.findAll(specification,
                    PageRequest.of(communities.getTotalPages() - 1, pageSize, sort));
        }
        return communities.map(CommunitySummaryResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public CommunityResponse getById(String id) {
        return toResponse(findActive(id));
    }

    @Transactional(readOnly = true)
    public List<CommunityHistoryEntry> getHistory(String id) {
        findActive(id); // 404 if missing/deleted
        // Derived from programs (Phase 4) — empty until that module lands (plan Phase 1 scope note).
        return List.of();
    }

    // --- writes ---

    public CommunityResponse create(CommunityRequest request) {
        Community community = new Community();
        applyRequest(community, request);
        community.setCreatedBy(resolveCurrentUserId());
        Community saved = communityRepository.save(community);
        activityLogService.record("community.created", "community", saved.getId(),
                Map.of("name", saved.getName(), "municipality", saved.getMunicipality()));
        return toResponse(saved);
    }

    public CommunityResponse update(String id, CommunityRequest request) {
        Community community = findActive(id);
        applyRequest(community, request);
        Community saved = communityRepository.save(community);
        activityLogService.record("community.updated", "community", saved.getId(),
                Map.of("name", saved.getName()));
        return toResponse(saved);
    }

    /**
     * Soft-deletes a community (spec §2 AC). Blocked with 409 once it has non-cancelled programs —
     * enforced in Phase 4 when the programs table exists; nothing blocks today.
     */
    public void delete(String id) {
        Community community = findActive(id);
        assertDeletable(community);
        community.setDeletedAt(Instant.now());
        communityRepository.save(community);
        activityLogService.record("community.deleted", "community", community.getId(),
                Map.of("name", community.getName()));
    }

    // --- documents ---

    @Transactional(readOnly = true)
    public List<CommunityDocumentResponse> listDocuments(String communityId) {
        findActive(communityId);
        return documentRepository.findByCommunityIdOrderByCreatedAtDesc(communityId).stream()
                .map(CommunityDocumentResponse::fromEntity)
                .toList();
    }

    public CommunityDocumentResponse addDocument(String communityId, MultipartFile file, String docType) {
        Community community = findActive(communityId);
        String normalizedType = normalizeDocType(docType);
        StoredFile stored = storageService.store(file, "communities/" + communityId);

        CommunityDocument document = new CommunityDocument();
        document.setCommunity(community);
        document.setFilePath(stored.path());
        document.setOriginalFilename(stored.originalFilename());
        document.setMimeType(stored.mimeType() == null ? "application/octet-stream" : stored.mimeType());
        document.setSizeBytes(stored.sizeBytes());
        document.setDocType(normalizedType);
        document.setUploadedBy(resolveCurrentUserId());
        CommunityDocument saved = documentRepository.save(document);

        activityLogService.record("community.document_added", "community", communityId,
                Map.of("documentId", saved.getId(), "filename", saved.getOriginalFilename()));
        return CommunityDocumentResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public DocumentDownload loadDocument(String communityId, String documentId) {
        findActive(communityId);
        CommunityDocument document = findDocument(communityId, documentId);
        Resource resource = storageService.load(document.getFilePath());
        return new DocumentDownload(resource, document.getOriginalFilename(),
                document.getMimeType(), document.getSizeBytes());
    }

    public void deleteDocument(String communityId, String documentId) {
        findActive(communityId);
        CommunityDocument document = findDocument(communityId, documentId);
        storageService.delete(document.getFilePath());
        documentRepository.delete(document);
        activityLogService.record("community.document_deleted", "community", communityId,
                Map.of("documentId", documentId, "filename", document.getOriginalFilename()));
    }

    /** Bytes + metadata for a document download (Resource is a Spring type, kept out of the DTO layer). */
    public record DocumentDownload(Resource resource, String filename, String mimeType, long sizeBytes) {
    }

    // --- helpers ---

    private void applyRequest(Community community, CommunityRequest request) {
        community.setName(trimmed(request.name()));
        community.setBarangayCode(trimmedOrNull(request.barangayCode()));
        community.setMunicipality(trimmed(request.municipality()));
        community.setProvince(trimmed(request.province()));
        community.setClassification(validateClassification(request.classification()));
        community.setEstimatedPopulation(request.estimatedPopulation());
        community.setHouseholdCount(request.householdCount());
        community.setPopulationMale(request.populationMale());
        community.setPopulationFemale(request.populationFemale());
        community.setContactPersonName(trimmedOrNull(request.contactPersonName()));
        community.setContactPersonDesignation(trimmedOrNull(request.contactPersonDesignation()));
        community.setContactPersonPhone(trimmedOrNull(request.contactPersonPhone()));
        community.setNotes(trimmedOrNull(request.notes()));
        community.setSectors(resolveSectors(request.sectorIds()));
    }

    private CommunityResponse toResponse(Community community) {
        List<CommunityDocumentResponse> documents =
                documentRepository.findByCommunityIdOrderByCreatedAtDesc(community.getId()).stream()
                        .map(CommunityDocumentResponse::fromEntity)
                        .toList();
        return CommunityResponse.fromEntity(community, documents, computeWarnings(community));
    }

    /** Non-blocking GAD data-integrity notices (spec §2.3): population split vs. estimate. */
    private List<String> computeWarnings(Community community) {
        List<String> warnings = new ArrayList<>();
        Integer male = community.getPopulationMale();
        Integer female = community.getPopulationFemale();
        Integer estimate = community.getEstimatedPopulation();
        if (male != null && female != null && estimate != null && estimate > 0) {
            int sum = male + female;
            double difference = Math.abs(sum - estimate) / (double) estimate;
            if (difference > POPULATION_SPLIT_TOLERANCE) {
                warnings.add("Male + female population (" + sum + ") differs from the estimated population ("
                        + estimate + ") by more than 10%.");
            }
        }
        return warnings;
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

    private String validateClassification(String classification) {
        String normalized = classification == null ? "" : classification.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CLASSIFICATIONS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Classification must be one of: " + String.join(", ", ALLOWED_CLASSIFICATIONS) + ".");
        }
        return normalized;
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

    /**
     * Placeholder for the Phase 4 delete-block: a community with non-cancelled programs must not be
     * deletable (spec §2 AC → 409). The programs table does not exist yet, so this is a no-op today.
     */
    private void assertDeletable(Community community) {
        // TODO Phase 4: throw ConflictException when community has non-cancelled programs.
    }

    private Community findActive(String id) {
        return communityRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("Community not found."));
    }

    private CommunityDocument findDocument(String communityId, String documentId) {
        return documentRepository.findByIdAndCommunityId(documentId, communityId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
    }

    private String resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
    }

    private Specification<Community> buildSpecification(CommunityListQuery query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (isPresent(query.getSearch())) {
                String like = "%" + query.getSearch().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("municipality")), like),
                        cb.like(cb.lower(root.get("province")), like),
                        cb.like(cb.lower(root.get("barangayCode")), like),
                        cb.like(cb.lower(root.get("contactPersonName")), like)));
            }

            if (isPresent(query.getMunicipality())) {
                predicates.add(cb.equal(cb.lower(root.get("municipality")),
                        query.getMunicipality().trim().toLowerCase(Locale.ROOT)));
            }

            if (isPresent(query.getClassification())) {
                predicates.add(cb.equal(root.get("classification"),
                        query.getClassification().trim().toLowerCase(Locale.ROOT)));
            }

            if (isPresent(query.getSectorId())) {
                Join<Community, Sector> sectorJoin = root.join("sectors", JoinType.INNER);
                predicates.add(cb.equal(sectorJoin.get("id"), query.getSectorId().trim()));
                criteriaQuery.distinct(true);
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private int normalizePage(int page) {
        return Math.max(0, page - 1);
    }

    private int normalizePageSize(int perPage) {
        return Math.min(100, Math.max(1, perPage));
    }

    private Sort resolveSort(String requestedSort, String requestedDirection) {
        String property = switch (requestedSort == null ? "" : requestedSort.trim()) {
            case "municipality" -> "municipality";
            case "province" -> "province";
            case "classification" -> "classification";
            case "createdAt" -> "createdAt";
            case "updatedAt" -> "updatedAt";
            case "name", "" -> "name";
            default -> throw new IllegalArgumentException("Unsupported sort field.");
        };
        Sort.Direction direction = "desc".equalsIgnoreCase(requestedDirection)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, property);
        return "name".equals(property) ? sort : sort.and(Sort.by(Sort.Direction.ASC, "name"));
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
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
