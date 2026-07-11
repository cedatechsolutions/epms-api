package com.cems.api.service;

import com.cems.api.dto.ActivityLogQuery;
import com.cems.api.dto.ActivityLogResponse;
import com.cems.api.entity.ActivityLog;
import com.cems.api.entity.User;
import com.cems.api.event.ActivityEvent;
import com.cems.api.repository.ActivityLogRepository;
import com.cems.api.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the audit trail (spec Module 1 §4). Callers invoke {@code record(...)} on the
 * request thread; the event is persisted after the surrounding transaction commits and
 * off the request thread, so a slow/failed log write never blocks or breaks the action.
 */
@Service
public class ActivityLogService {

    private static final Logger logger = LoggerFactory.getLogger(ActivityLogService.class);

    private final ActivityLogRepository activityLogRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher publisher;
    // Dedicated mapper for serializing small metadata maps; independent of the web layer.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActivityLogService(ActivityLogRepository activityLogRepository,
            UserRepository userRepository,
            ApplicationEventPublisher publisher) {
        this.activityLogRepository = activityLogRepository;
        this.userRepository = userRepository;
        this.publisher = publisher;
    }

    /** Records an action attributed to the current authenticated user (resolved from context). */
    public void record(String action, String entityType, String entityId, Map<String, Object> metadata) {
        record(resolveCurrentUserId(), action, entityType, entityId, metadata);
    }

    /** Records an action attributed to an explicit actor (e.g. login, before the security context is set). */
    public void record(String actorUserId, String action, String entityType, String entityId,
            Map<String, Object> metadata) {
        publisher.publishEvent(new ActivityEvent(
                actorUserId, action, entityType, entityId, toJson(metadata), resolveClientIp(), Instant.now()));
    }

    /** Persists the event after commit, asynchronously. Failures are logged, never propagated. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityEvent(ActivityEvent event) {
        try {
            ActivityLog log = new ActivityLog();
            log.setUserId(event.userId());
            log.setAction(event.action());
            log.setEntityType(event.entityType());
            log.setEntityId(event.entityId());
            log.setMetadata(event.metadataJson());
            log.setIpAddress(event.ipAddress());
            log.setCreatedAt(event.occurredAt());
            activityLogRepository.save(log);
        } catch (Exception ex) {
            logger.error("Failed to persist activity log for action '{}': {}", event.action(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<ActivityLogResponse> search(ActivityLogQuery query) {
        int page = Math.max(0, query.getPage() - 1);
        int size = Math.min(100, Math.max(1, query.getPerPage()));
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ActivityLog> logs = activityLogRepository.findAll(buildSpecification(query), pageable);
        Map<String, String> labels = resolveUserLabels(logs.getContent());

        return logs.map(log -> ActivityLogResponse.fromEntity(log, labels.get(log.getUserId())));
    }

    private Specification<ActivityLog> buildSpecification(ActivityLogQuery query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (isPresent(query.getUserId())) {
                predicates.add(cb.equal(root.get("userId"), query.getUserId().trim()));
            }
            if (isPresent(query.getAction())) {
                predicates.add(cb.equal(root.get("action"), query.getAction().trim()));
            }
            if (isPresent(query.getEntityType())) {
                predicates.add(cb.equal(root.get("entityType"), query.getEntityType().trim()));
            }
            if (isPresent(query.getFrom())) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), parseFrom(query.getFrom())));
            }
            if (isPresent(query.getTo())) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), parseTo(query.getTo())));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<String, String> resolveUserLabels(List<ActivityLog> logs) {
        Set<String> userIds = logs.stream()
                .map(ActivityLog::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, this::labelFor, (a, b) -> a));
    }

    private String labelFor(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isBlank() ? user.getEmail() : name;
    }

    private String resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
    }

    private String resolveClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            logger.warn("Could not serialize activity metadata: {}", ex.getMessage());
            return null;
        }
    }

    private Instant parseFrom(String value) {
        return parseBoundary(value, date -> date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private Instant parseTo(String value) {
        return parseBoundary(value, date -> date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1));
    }

    private Instant parseBoundary(String value, Function<LocalDate, Instant> dateMapper) {
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return dateMapper.apply(LocalDate.parse(trimmed));
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid date '" + value + "'. Use ISO date or date-time.");
            }
        }
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
