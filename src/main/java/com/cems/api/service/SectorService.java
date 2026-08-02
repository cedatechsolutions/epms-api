package com.cems.api.service;

import com.cems.api.dto.SectorResponse;
import com.cems.api.repository.SectorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read access to the seeded GAD sector lookup (spec §3.2). */
@Service
public class SectorService {

    private final SectorRepository sectorRepository;

    public SectorService(SectorRepository sectorRepository) {
        this.sectorRepository = sectorRepository;
    }

    @Transactional(readOnly = true)
    public List<SectorResponse> listActive() {
        return sectorRepository.findByActiveTrueOrderByName().stream()
                .map(SectorResponse::fromEntity)
                .toList();
    }
}
