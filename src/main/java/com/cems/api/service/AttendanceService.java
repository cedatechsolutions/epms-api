package com.cems.api.service;

import com.cems.api.dto.AttendanceImportResponse;
import com.cems.api.dto.AttendanceRequest;
import com.cems.api.dto.AttendanceResponse;
import com.cems.api.dto.SexSplitResponse;
import com.cems.api.entity.AttendanceRecord;
import com.cems.api.entity.Program;
import com.cems.api.entity.ProgramActivity;
import com.cems.api.entity.Sector;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.AttendanceRecordRepository;
import com.cems.api.repository.SectorRepository;
import com.cems.api.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Attendance encoding for one activity (spec Module 5b §4) — quick entry, CSV import, and the
 * running Total/F/M.
 *
 * <p>This table is personal data under RA 10173. Two consequences are implemented here rather than
 * left to callers: names are <b>masked server-side</b> for readers without
 * {@code canViewBeneficiaryNames()} (a client cannot hide a value it was never sent), and every
 * bulk read is written to {@code activity_logs}.
 */
@Service
@Transactional
public class AttendanceService {

    /** Upper bound on a single CSV import, so a pasted-in spreadsheet cannot exhaust memory. */
    static final int MAX_IMPORT_ROWS = 2000;

    /**
     * Accepted spellings of each sex in an imported file. Anything else is a row-level rejection —
     * the spec is explicit that invalid sex values are reported, never guessed at, because a wrong
     * guess silently corrupts the GAD figures the whole system reports on.
     */
    private static final Map<String, String> SEX_ALIASES = Map.of(
            "female", AttendanceRecord.SEX_FEMALE,
            "f", AttendanceRecord.SEX_FEMALE,
            "babae", AttendanceRecord.SEX_FEMALE,
            "male", AttendanceRecord.SEX_MALE,
            "m", AttendanceRecord.SEX_MALE,
            "lalaki", AttendanceRecord.SEX_MALE);

    private final AttendanceRecordRepository attendanceRepository;
    private final SectorRepository sectorRepository;
    private final ProgramActivityService activityService;
    private final ProgramAccessPolicy accessPolicy;
    private final Permissions permissions;
    private final ActivityLogService activityLogService;

    public AttendanceService(AttendanceRecordRepository attendanceRepository,
            SectorRepository sectorRepository,
            ProgramActivityService activityService,
            ProgramAccessPolicy accessPolicy,
            Permissions permissions,
            ActivityLogService activityLogService) {
        this.attendanceRepository = attendanceRepository;
        this.sectorRepository = sectorRepository;
        this.activityService = activityService;
        this.accessPolicy = accessPolicy;
        this.permissions = permissions;
        this.activityLogService = activityLogService;
    }

    // --- reads ---

    @Transactional(readOnly = true)
    public List<AttendanceResponse> list(String activityId) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanView(activity.getProgram());

        boolean includeNames = permissions.canViewBeneficiaryNames();
        List<AttendanceRecord> records =
                attendanceRepository.findByProgramActivityIdOrderByAttendeeNameAsc(activityId);

        // Reading the roster is an access to personal data; log it (RA 10173, spec §5.3).
        if (includeNames && !records.isEmpty()) {
            activityLogService.record("attendance.viewed", "program_activity", activityId,
                    Map.of("rows", records.size()));
        }
        return records.stream().map(record -> AttendanceResponse.fromEntity(record, includeNames)).toList();
    }

    @Transactional(readOnly = true)
    public SexSplitResponse totals(String activityId) {
        ProgramActivity activity = activityService.findActive(activityId);
        accessPolicy.assertCanView(activity.getProgram());
        return ProgramActivityService.toSplit(
                attendanceRepository.countBySexForActivities(List.of(activityId)).stream()
                        .map(row -> new Object[] { row[1], row[2] })
                        .toList());
    }

    // --- writes ---

    public AttendanceResponse add(String activityId, AttendanceRequest request) {
        ProgramActivity activity = requireOpenActivity(activityId);

        AttendanceRecord record = new AttendanceRecord();
        record.setProgramActivity(activity);
        record.setAttendeeName(requireName(request.attendeeName()));
        record.setSex(normalizeSex(request.sex()));
        record.setAge(request.age());
        record.setSector(resolveSector(request.sectorId()));
        record.setCommunity(activity.getProgram().getCommunity());
        record.setCreatedBy(accessPolicy.currentUserId());

        AttendanceRecord saved = attendanceRepository.save(record);
        activityLogService.record("attendance.recorded", "program_activity", activityId,
                Map.of("attendanceId", saved.getId(), "sex", saved.getSex()));
        return AttendanceResponse.fromEntity(saved, permissions.canViewBeneficiaryNames());
    }

    public void delete(String activityId, String attendanceId) {
        requireOpenActivity(activityId);
        AttendanceRecord record = attendanceRepository
                .findByIdAndProgramActivityId(attendanceId, activityId)
                .orElseThrow(() -> new NoSuchElementException("Attendance record not found."));

        attendanceRepository.delete(record);
        activityLogService.record("attendance.deleted", "program_activity", activityId,
                Map.of("attendanceId", attendanceId));
    }

    /**
     * Imports a CSV of attendees (columns: name, sex, age, sector).
     *
     * <p><strong>Partial by design</strong> — valid rows are saved and invalid ones are reported per
     * row (spec AC 4). One bad sex value in row 40 must not discard the 39 good rows above it; the
     * alternative would make a coordinator re-upload the whole sheet to fix a single typo.
     *
     * <p>A header line is detected and skipped, so a file exported straight from a spreadsheet works
     * without editing. Line numbers in the error report are 1-based over the raw file, which is what
     * the user sees in their editor.
     */
    public AttendanceImportResponse importCsv(String activityId, MultipartFile file) {
        ProgramActivity activity = requireOpenActivity(activityId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a CSV file to import.");
        }

        // Errors accumulate in a local list passed down to the parser. They must NOT live on the
        // bean: this is a singleton, and two coordinators importing at once would interleave their
        // reports.
        List<AttendanceImportResponse.RowError> errors = new ArrayList<>();
        List<AttendanceRecord> pending = new ArrayList<>();
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = stripBom(line, lineNumber).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (lineNumber == 1 && looksLikeHeader(trimmed)) {
                    continue;
                }
                if (pending.size() >= MAX_IMPORT_ROWS) {
                    errors.add(new AttendanceImportResponse.RowError(lineNumber,
                            "Import limit of " + MAX_IMPORT_ROWS + " rows reached; the rest of the file was skipped.",
                            trimmed));
                    break;
                }
                parseRow(trimmed, lineNumber, activity, errors).ifPresent(pending::add);
            }
        } catch (IOException readFailure) {
            throw new IllegalArgumentException("That file could not be read. Please export it as UTF-8 CSV.");
        }

        attendanceRepository.saveAll(pending);
        activityLogService.record("attendance.imported", "program_activity", activityId,
                Map.of("imported", pending.size(), "skipped", errors.size()));

        return new AttendanceImportResponse(pending.size(), errors.size(), List.copyOf(errors),
                currentTotals(activityId));
    }

    private SexSplitResponse currentTotals(String activityId) {
        return ProgramActivityService.toSplit(
                attendanceRepository.countBySexForActivities(List.of(activityId)).stream()
                        .map(row -> new Object[] { row[1], row[2] })
                        .toList());
    }

    /** Parses one data row, appending to {@code errors} and returning empty when it is rejected. */
    private Optional<AttendanceRecord> parseRow(String line,
            int lineNumber,
            ProgramActivity activity,
            List<AttendanceImportResponse.RowError> errors) {
        String[] cells = splitCsv(line);
        String name = cells.length > 0 ? cells[0].trim() : "";
        String sexCell = cells.length > 1 ? cells[1].trim() : "";
        String ageCell = cells.length > 2 ? cells[2].trim() : "";
        String sectorCell = cells.length > 3 ? cells[3].trim() : "";

        if (name.isEmpty()) {
            errors.add(new AttendanceImportResponse.RowError(lineNumber, "Name is required.", line));
            return Optional.empty();
        }
        String sex = SEX_ALIASES.get(sexCell.toLowerCase(Locale.ROOT));
        if (sex == null) {
            errors.add(new AttendanceImportResponse.RowError(lineNumber,
                    sexCell.isEmpty()
                            ? "Sex is required (female or male)."
                            : "\"" + sexCell + "\" is not a valid sex; use female or male.",
                    line));
            return Optional.empty();
        }

        Integer age = null;
        if (!ageCell.isEmpty()) {
            try {
                age = Integer.valueOf(ageCell);
            } catch (NumberFormatException notANumber) {
                errors.add(new AttendanceImportResponse.RowError(lineNumber,
                        "\"" + ageCell + "\" is not a valid age.", line));
                return Optional.empty();
            }
            if (age < 0 || age > 130) {
                errors.add(new AttendanceImportResponse.RowError(lineNumber,
                        "Age must be between 0 and 130.", line));
                return Optional.empty();
            }
        }

        Sector sector = null;
        if (!sectorCell.isEmpty()) {
            sector = sectorRepository.findAll().stream()
                    .filter(candidate -> candidate.getName().equalsIgnoreCase(sectorCell))
                    .findFirst()
                    .orElse(null);
            if (sector == null) {
                errors.add(new AttendanceImportResponse.RowError(lineNumber,
                        "\"" + sectorCell + "\" is not a known sector.", line));
                return Optional.empty();
            }
        }

        AttendanceRecord record = new AttendanceRecord();
        record.setProgramActivity(activity);
        record.setAttendeeName(name);
        record.setSex(sex);
        record.setAge(age);
        record.setSector(sector);
        record.setCommunity(activity.getProgram().getCommunity());
        record.setCreatedBy(accessPolicy.currentUserId());
        return Optional.of(record);
    }

    /**
     * Minimal CSV split honouring double-quoted cells, which is enough for the four-column shape the
     * spec defines and avoids pulling in a parser dependency. A quoted cell may contain commas;
     * doubled quotes inside a quoted cell are an escaped quote.
     */
    static String[] splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(character);
                }
            } else if (character == '"') {
                inQuotes = true;
            } else if (character == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    /** A first line whose second cell is not a recognised sex is treated as a header. */
    private static boolean looksLikeHeader(String line) {
        String[] cells = splitCsv(line);
        if (cells.length < 2) {
            return false;
        }
        return !SEX_ALIASES.containsKey(cells[1].trim().toLowerCase(Locale.ROOT));
    }

    /** Excel writes a UTF-8 BOM; left in place it would corrupt the first name in the file. */
    private static String stripBom(String line, int lineNumber) {
        return lineNumber == 1 && !line.isEmpty() && line.charAt(0) == '﻿' ? line.substring(1) : line;
    }

    // --- validation helpers ---

    /** Attendance may not be recorded against a cancelled activity (spec AC 4 → 409). */
    private ProgramActivity requireOpenActivity(String activityId) {
        ProgramActivity activity = activityService.findActive(activityId);
        Program program = activity.getProgram();
        accessPolicy.assertCanDeliver(program);
        if (!activity.acceptsAttendance()) {
            throw new ConflictException(
                    "This activity was cancelled, so attendance cannot be recorded against it.");
        }
        return activity;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An attendee name is required.");
        }
        return name.trim();
    }

    private String normalizeSex(String sex) {
        String normalized = sex == null ? "" : SEX_ALIASES.getOrDefault(
                sex.trim().toLowerCase(Locale.ROOT), null);
        if (normalized == null) {
            throw new IllegalArgumentException("Sex must be female or male.");
        }
        return normalized;
    }

    private Sector resolveSector(String sectorId) {
        if (sectorId == null || sectorId.isBlank()) {
            return null;
        }
        return sectorRepository.findById(sectorId.trim())
                .orElseThrow(() -> new IllegalArgumentException("The selected sector does not exist."));
    }
}
