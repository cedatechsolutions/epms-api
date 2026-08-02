package com.cems.api.repository;

import com.cems.api.entity.NeedCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NeedCategoryRepository extends JpaRepository<NeedCategory, String> {

    List<NeedCategory> findByActiveTrueOrderByName();
}
