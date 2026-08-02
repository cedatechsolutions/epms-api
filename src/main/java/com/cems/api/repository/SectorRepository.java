package com.cems.api.repository;

import com.cems.api.entity.Sector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface SectorRepository extends JpaRepository<Sector, String> {

    List<Sector> findByActiveTrueOrderByName();

    List<Sector> findByIdIn(Set<String> ids);
}
