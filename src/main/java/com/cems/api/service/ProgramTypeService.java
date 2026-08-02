package com.cems.api.service;

import com.cems.api.dto.NeedCategoryResponse;
import com.cems.api.dto.ProgramTypeRequest;
import com.cems.api.dto.ProgramTypeResponse;
import com.cems.api.dto.ProgramTypeResponse.NeedWeight;
import com.cems.api.dto.ScoringMatrixRequest;
import com.cems.api.dto.ScoringMatrixResponse;
import com.cems.api.dto.ScoringMatrixResponse.MatrixRow;
import com.cems.api.dto.SectorResponse;
import com.cems.api.entity.NeedCategory;
import com.cems.api.entity.ProgramType;
import com.cems.api.entity.ProgramTypeNeedWeight;
import com.cems.api.entity.Sector;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.NeedCategoryRepository;
import com.cems.api.repository.ProgramTypeNeedWeightRepository;
import com.cems.api.repository.ProgramTypeRepository;
import com.cems.api.repository.SectorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The program-type library and the scoring matrix behind it (spec Module 4 §4).
 *
 * <p>Both are admin configuration: edits here shape <b>future</b> scoring runs only. Existing
 * recommendations keep the breakdown they were generated with, so a historical decision always
 * remains explainable against the matrix that produced it.
 */
@Service
public class ProgramTypeService {

    private static final BigDecimal MIN_WEIGHT = BigDecimal.ZERO;
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("5.00");

    private final ProgramTypeRepository programTypeRepository;
    private final ProgramTypeNeedWeightRepository weightRepository;
    private final NeedCategoryRepository needCategoryRepository;
    private final SectorRepository sectorRepository;
    private final ActivityLogService activityLogService;

    public ProgramTypeService(ProgramTypeRepository programTypeRepository,
            ProgramTypeNeedWeightRepository weightRepository,
            NeedCategoryRepository needCategoryRepository,
            SectorRepository sectorRepository,
            ActivityLogService activityLogService) {
        this.programTypeRepository = programTypeRepository;
        this.weightRepository = weightRepository;
        this.needCategoryRepository = needCategoryRepository;
        this.sectorRepository = sectorRepository;
        this.activityLogService = activityLogService;
    }

    // --- program-type library ---

    @Transactional(readOnly = true)
    public List<ProgramTypeResponse> list() {
        return programTypeRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProgramTypeResponse getById(String id) {
        return toResponse(find(id));
    }

    public ProgramTypeResponse create(ProgramTypeRequest request) {
        if (programTypeRepository.existsByNameIgnoreCase(request.name().trim())) {
            throw new ConflictException("A program type with this name already exists.");
        }

        ProgramType programType = new ProgramType();
        apply(programType, request);
        programTypeRepository.save(programType);

        activityLogService.record("program_type.created", "program_type", programType.getId(),
                Map.of("name", programType.getName()));
        return toResponse(programType);
    }

    public ProgramTypeResponse update(String id, ProgramTypeRequest request) {
        ProgramType programType = find(id);

        programTypeRepository.findByNameIgnoreCase(request.name().trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ConflictException("A program type with this name already exists.");
                });

        apply(programType, request);
        programTypeRepository.save(programType);

        activityLogService.record("program_type.updated", "program_type", id,
                Map.of("name", programType.getName()));
        return toResponse(programType);
    }

    // --- scoring matrix ---

    /** The full grid, with every cell filled in (absent rows read as 0.00). */
    @Transactional(readOnly = true)
    public ScoringMatrixResponse getMatrix() {
        List<NeedCategory> categories = activeCategories();
        List<ProgramType> programTypes = programTypeRepository.findAllByOrderByNameAsc();
        Map<String, Map<String, BigDecimal>> stored = storedWeights();

        List<MatrixRow> rows = new ArrayList<>();
        for (ProgramType programType : programTypes) {
            Map<String, BigDecimal> weights = new LinkedHashMap<>();
            Map<String, BigDecimal> forType = stored.getOrDefault(programType.getId(), Map.of());
            for (NeedCategory category : categories) {
                weights.put(category.getId(),
                        forType.getOrDefault(category.getId(), zero()));
            }
            rows.add(new MatrixRow(programType.getId(), programType.getName(), programType.isActive(), weights));
        }

        return new ScoringMatrixResponse(
                categories.stream().map(NeedCategoryResponse::fromEntity).toList(),
                rows);
    }

    /**
     * Writes the supplied cells. A weight of 0 deletes the row so the stored matrix stays sparse —
     * which is also what makes "unmapped" and "explicitly zero" the same thing to the scorer.
     */
    public ScoringMatrixResponse updateMatrix(ScoringMatrixRequest request) {
        for (ScoringMatrixRequest.MatrixCell cell : request.cells()) {
            BigDecimal weight = cell.weight();
            if (weight.compareTo(MIN_WEIGHT) < 0 || weight.compareTo(MAX_WEIGHT) > 0) {
                throw new IllegalArgumentException("Matrix weights must be between 0.00 and 5.00.");
            }

            ProgramType programType = find(cell.programTypeId());
            NeedCategory category = needCategoryRepository.findById(cell.needCategoryId())
                    .orElseThrow(() -> new NoSuchElementException("Need category not found."));

            ProgramTypeNeedWeight existing = weightRepository
                    .findByProgramTypeIdAndNeedCategoryId(programType.getId(), category.getId())
                    .orElse(null);

            if (weight.compareTo(MIN_WEIGHT) == 0) {
                if (existing != null) {
                    weightRepository.delete(existing);
                }
                continue;
            }

            ProgramTypeNeedWeight row = existing == null ? new ProgramTypeNeedWeight() : existing;
            row.setProgramType(programType);
            row.setNeedCategory(category);
            row.setWeight(weight);
            weightRepository.save(row);
        }

        activityLogService.record("scoring_matrix.updated", "scoring_matrix", null,
                Map.of("cells", request.cells().size()));
        return getMatrix();
    }

    // --- helpers ---

    private void apply(ProgramType programType, ProgramTypeRequest request) {
        programType.setName(request.name().trim());
        programType.setDescription(request.description());
        programType.setDefaultDuration(request.defaultDuration());
        if (request.active() != null) {
            programType.setActive(request.active());
        }
        if (request.sectorIds() != null) {
            Set<Sector> sectors = new HashSet<>();
            for (String sectorId : request.sectorIds()) {
                sectors.add(sectorRepository.findById(sectorId)
                        .orElseThrow(() -> new NoSuchElementException("Sector not found: " + sectorId)));
            }
            programType.setSectors(sectors);
        }
    }

    private ProgramTypeResponse toResponse(ProgramType programType) {
        List<NeedWeight> weights = weightRepository.findByProgramTypeId(programType.getId()).stream()
                .map(row -> new NeedWeight(
                        row.getNeedCategory().getId(),
                        row.getNeedCategory().getName(),
                        row.getWeight()))
                .sorted(Comparator.comparing(NeedWeight::needCategoryName))
                .toList();

        List<SectorResponse> sectors = programType.getSectors().stream()
                .map(SectorResponse::fromEntity)
                .sorted(Comparator.comparing(SectorResponse::name))
                .toList();

        return new ProgramTypeResponse(
                programType.getId(),
                programType.getName(),
                programType.getDescription(),
                programType.getDefaultDuration(),
                programType.isActive(),
                sectors,
                weights,
                programType.getCreatedAt(),
                programType.getUpdatedAt());
    }

    private Map<String, Map<String, BigDecimal>> storedWeights() {
        Map<String, Map<String, BigDecimal>> grouped = new LinkedHashMap<>();
        for (ProgramTypeNeedWeight row : weightRepository.findAll()) {
            grouped.computeIfAbsent(row.getProgramType().getId(), ignored -> new LinkedHashMap<>())
                    .put(row.getNeedCategory().getId(), row.getWeight());
        }
        return grouped;
    }

    private List<NeedCategory> activeCategories() {
        return needCategoryRepository.findAll().stream()
                .filter(NeedCategory::isActive)
                .sorted(Comparator.comparing(NeedCategory::getName))
                .toList();
    }

    private BigDecimal zero() {
        return new BigDecimal("0.00");
    }

    private ProgramType find(String id) {
        return programTypeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Program type not found."));
    }
}
