package com.cems.api.repository;

import com.cems.api.entity.ProgramTypeNeedWeight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramTypeNeedWeightRepository extends JpaRepository<ProgramTypeNeedWeight, String> {

    List<ProgramTypeNeedWeight> findByProgramTypeId(String programTypeId);

    Optional<ProgramTypeNeedWeight> findByProgramTypeIdAndNeedCategoryId(
            String programTypeId, String needCategoryId);

    void deleteByProgramTypeId(String programTypeId);
}
