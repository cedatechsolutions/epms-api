package com.cems.api.service;

import com.cems.api.dto.AcademicPeriodResponse;
import com.cems.api.entity.AcademicPeriod;
import com.cems.api.repository.AcademicPeriodRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Read-only lookup behind {@code GET /api/academic-periods} and the resolver every period-scoped
 * query goes through (spec Module 6 §1).
 *
 * <p>Nothing here writes: periods are seeded by migration {@code V12}. The one piece of logic is
 * {@link #resolveCurrent()} — see its javadoc for why the date range outranks the {@code is_current}
 * column.
 */
@Service
public class AcademicPeriodService {

    /**
     * The campus timezone (cross-cutting rule 6). Period boundaries are calendar dates, so "today"
     * has to be asked of Manila rather than of the server's clock — a UTC host would roll a semester
     * over eight hours late for everyone using it.
     */
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Manila");

    private final AcademicPeriodRepository repository;
    private final Clock clock;

    // Two constructors, so Spring is told explicitly which one to wire.
    @Autowired
    public AcademicPeriodService(AcademicPeriodRepository repository) {
        this(repository, Clock.system(CAMPUS_ZONE));
    }

    /** Test seam: lets a fixed clock pin "today" without rewriting the seeded calendar. */
    AcademicPeriodService(AcademicPeriodRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Every period in calendar order, with exactly one flagged as the current one. */
    @Transactional(readOnly = true)
    public List<AcademicPeriodResponse> list() {
        String currentId = resolveCurrent().map(AcademicPeriod::getId).orElse(null);
        return repository.findAllByOrderByStartsOnAsc().stream()
                .map(period -> AcademicPeriodResponse.fromEntity(period, period.getId().equals(currentId)))
                .toList();
    }

    /**
     * The period the dashboard opens on.
     *
     * <p>Resolution order, and the reason for it:
     * <ol>
     *   <li>the period whose date range contains today — always right, never goes stale;</li>
     *   <li>the row flagged {@code is_current} — covers the gap between terms (the August and June
     *       breaks), where no range matches but the campus still thinks of itself as being in a
     *       particular semester;</li>
     *   <li>the latest period on the calendar — so a database seeded only with past terms still
     *       renders a dashboard instead of an error.</li>
     * </ol>
     *
     * <p>Empty only when no periods exist at all, which the migration makes impossible outside a
     * hand-edited database.
     */
    @Transactional(readOnly = true)
    public Optional<AcademicPeriod> resolveCurrent() {
        LocalDate today = LocalDate.now(clock);
        return repository.findAllByOrderByStartsOnAsc().stream()
                .filter(period -> period.contains(today))
                .findFirst()
                .or(repository::findFirstByIsCurrentTrueOrderByStartsOnDesc)
                .or(repository::findFirstByOrderByStartsOnDesc);
    }

    /**
     * Resolves the {@code periodId} query parameter shared by the dashboard and the programs list.
     *
     * <p>A blank id means "all periods" and yields empty — the caller then applies no date filter.
     * An id that does not exist is a 404 rather than a silent fall-back to everything: a stale
     * bookmark must not quietly show campus-wide figures under a period label the user still sees
     * selected.
     */
    @Transactional(readOnly = true)
    public Optional<AcademicPeriod> resolveRequested(String periodId) {
        if (periodId == null || periodId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(repository.findById(periodId.trim())
                .orElseThrow(() -> new NoSuchElementException("Academic period not found.")));
    }
}
