package com.cems.api.service;

import com.cems.api.dto.ProgramMemberRequest;
import com.cems.api.dto.ProgramMemberResponse;
import com.cems.api.entity.Program;
import com.cems.api.entity.ProgramMember;
import com.cems.api.entity.User;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.ProgramMemberRepository;
import com.cems.api.repository.ProgramRepository;
import com.cems.api.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Assigning people to a program — the "assigned" half of spec Module 5 §1's "faculty sees own +
 * assigned programs", and what lets a student volunteer see the programs they help run (§2.2).
 *
 * <p>Assignment is a delivery-phase concern but is <b>not</b> gated by
 * {@link ProgramAccessPolicy#assertCanDeliver}: a team is named while the proposal is still being
 * drafted, long before approval. It is gated on ownership instead — the lead, the creator, or a
 * coordinator decides who is on their program.
 */
@Service
@Transactional
public class ProgramMemberService {

    private static final Set<String> ALLOWED_ROLES = Set.of(
            ProgramMember.ROLE_VOLUNTEER, ProgramMember.ROLE_CO_FACULTY);

    private final ProgramMemberRepository memberRepository;
    private final ProgramRepository programRepository;
    private final UserRepository userRepository;
    private final ProgramAccessPolicy accessPolicy;
    private final ActivityLogService activityLogService;

    public ProgramMemberService(ProgramMemberRepository memberRepository,
            ProgramRepository programRepository,
            UserRepository userRepository,
            ProgramAccessPolicy accessPolicy,
            ActivityLogService activityLogService) {
        this.memberRepository = memberRepository;
        this.programRepository = programRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.activityLogService = activityLogService;
    }

    @Transactional(readOnly = true)
    public List<ProgramMemberResponse> list(String programId) {
        Program program = findProgram(programId);
        accessPolicy.assertCanView(program);

        List<ProgramMember> members = memberRepository.findByProgramIdOrderByCreatedAtAsc(programId);
        Map<String, User> users = usersById(members);
        return members.stream()
                .map(member -> {
                    User user = users.get(member.getUserId());
                    return ProgramMemberResponse.fromEntity(member,
                            user == null ? null : displayName(user),
                            user == null ? null : user.getEmail());
                })
                .toList();
    }

    public ProgramMemberResponse add(String programId, ProgramMemberRequest request) {
        Program program = findProgram(programId);
        assertCanManageTeam(program);

        String userId = request.userId().trim();
        User user = userRepository.findById(userId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("The selected user does not exist."));
        if (memberRepository.existsByProgramIdAndUserId(programId, userId)) {
            throw new ConflictException(displayName(user) + " is already assigned to this program.");
        }

        ProgramMember member = new ProgramMember();
        member.setProgram(program);
        member.setUserId(userId);
        member.setRoleInProgram(normalizeRole(request.roleInProgram()));
        member.setAssignedBy(accessPolicy.currentUserId());
        ProgramMember saved = memberRepository.save(member);

        activityLogService.record("program.member_assigned", "program", programId,
                Map.of("userId", userId, "roleInProgram", saved.getRoleInProgram()));
        return ProgramMemberResponse.fromEntity(saved, displayName(user), user.getEmail());
    }

    public void remove(String programId, String memberId) {
        Program program = findProgram(programId);
        assertCanManageTeam(program);

        ProgramMember member = memberRepository.findById(memberId)
                .filter(candidate -> candidate.getProgram().getId().equals(programId))
                .orElseThrow(() -> new NoSuchElementException("Assignment not found."));

        memberRepository.delete(member);
        activityLogService.record("program.member_removed", "program", programId,
                Map.of("userId", member.getUserId()));
    }

    /**
     * The lead, the creator, or a coordinator names the team. Deliberately ownership-based rather
     * than {@code assertCanDeliver}: a team is assembled while the proposal is still a draft.
     */
    private void assertCanManageTeam(Program program) {
        if (!accessPolicy.isOwner(program) && !accessPolicy.isCoordinatorOrAdmin()) {
            throw new AccessDeniedException(
                    "You may only assign people to programs you created or lead.");
        }
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ProgramMember.ROLE_VOLUNTEER;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ROLES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Role must be one of: " + String.join(", ", ALLOWED_ROLES) + ".");
        }
        return normalized;
    }

    private Map<String, User> usersById(List<ProgramMember> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        Map<String, User> byId = new HashMap<>();
        userRepository.findAllById(members.stream().map(ProgramMember::getUserId).toList())
                .forEach(user -> byId.put(user.getId(), user));
        return byId;
    }

    private String displayName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getEmail() : name;
    }

    private Program findProgram(String programId) {
        return programRepository.findByIdAndDeletedAtIsNull(programId)
                .orElseThrow(() -> new NoSuchElementException("Program not found."));
    }
}
