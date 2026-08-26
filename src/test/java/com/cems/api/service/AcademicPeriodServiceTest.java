package com.cems.api.service;

import com.cems.api.entity.AcademicPeriod;
import com.cems.api.repository.AcademicPeriodRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one piece of logic in the period lookup: which term the dashboard opens on.
 *
 * <p>Worth pinning because it is the only place a stored flag and a date range can disagree, and the
 * resolution order is a decision rather than an implementation detail — see the service's javadoc.
 */
class AcademicPeriodServiceTest {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    private final AcademicPeriodRepository repository = mock(AcademicPeriodRepository.class);

    @Test
    void theDateRangeWinsOverTheStoredFlag() {
        AcademicPeriod firstSemester = period("first", "2026-08-01", "2026-12-31", true);
        AcademicPeriod secondSemester = period("second", "2027-01-01", "2027-05-31", false);
        stub(List.of(firstSemester, secondSemester));

        // February 2027 is inside the second semester, even though the first is the one flagged
        // current. A flag left over from December must not outrank the calendar.
        AcademicPeriodService service = serviceOn("2027-02-10");
        assertEquals("second", service.resolveCurrent().orElseThrow().getId());
    }

    @Test
    void betweenTermsTheStoredFlagDecides() {
        AcademicPeriod midyear = period("midyear", "2026-06-01", "2026-07-15", false);
        AcademicPeriod firstSemester = period("first", "2026-08-01", "2026-12-31", true);
        stub(List.of(midyear, firstSemester));

        // 20 July falls in the break: no range contains it, so the campus's own answer is used.
        AcademicPeriodService service = serviceOn("2026-07-20");
        assertEquals("first", service.resolveCurrent().orElseThrow().getId());
    }

    @Test
    void withNoRangeAndNoFlagTheLatestTermIsUsedRatherThanNothing() {
        AcademicPeriod older = period("older", "2024-08-01", "2024-12-31", false);
        AcademicPeriod newer = period("newer", "2025-01-01", "2025-05-31", false);
        stub(List.of(older, newer));
        when(repository.findFirstByIsCurrentTrueOrderByStartsOnDesc()).thenReturn(Optional.empty());
        when(repository.findFirstByOrderByStartsOnDesc()).thenReturn(Optional.of(newer));

        // A database seeded only with past terms still renders a dashboard.
        assertEquals("newer", serviceOn("2026-08-14").resolveCurrent().orElseThrow().getId());
    }

    @Test
    void exactlyOnePeriodIsMarkedCurrentInTheListing() {
        stub(List.of(
                period("first", "2026-08-01", "2026-12-31", true),
                period("second", "2027-01-01", "2027-05-31", false)));

        long current = serviceOn("2027-02-10").list().stream()
                .filter(period -> period.current())
                .count();
        assertEquals(1, current);
    }

    @Test
    void aBlankPeriodMeansAllPeriodsAndAnUnknownOneIsNotFound() {
        stub(List.of(period("first", "2026-08-01", "2026-12-31", true)));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        AcademicPeriodService service = serviceOn("2026-09-01");

        assertTrue(service.resolveRequested(null).isEmpty());
        assertTrue(service.resolveRequested("   ").isEmpty());
        assertThrows(NoSuchElementException.class, () -> service.resolveRequested("missing"));
    }

    private void stub(List<AcademicPeriod> periods) {
        when(repository.findAllByOrderByStartsOnAsc()).thenReturn(periods);
        when(repository.findFirstByIsCurrentTrueOrderByStartsOnDesc()).thenReturn(
                periods.stream().filter(AcademicPeriod::isCurrent).findFirst());
        when(repository.findFirstByOrderByStartsOnDesc()).thenReturn(
                periods.isEmpty() ? Optional.empty() : Optional.of(periods.get(periods.size() - 1)));
    }

    private AcademicPeriodService serviceOn(String today) {
        Clock clock = Clock.fixed(
                LocalDate.parse(today).atStartOfDay(MANILA).toInstant().plusSeconds(3600),
                MANILA);
        return new AcademicPeriodService(repository, clock);
    }

    private AcademicPeriod period(String id, String startsOn, String endsOn, boolean current) {
        AcademicPeriod period = new AcademicPeriod();
        period.setId(id);
        period.setLabel(id);
        period.setStartsOn(LocalDate.parse(startsOn));
        period.setEndsOn(LocalDate.parse(endsOn));
        period.setCurrent(current);
        period.setCreatedAt(Instant.now());
        return period;
    }
}
