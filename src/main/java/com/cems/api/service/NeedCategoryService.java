package com.cems.api.service;

import com.cems.api.dto.NeedCategoryResponse;
import com.cems.api.repository.NeedCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read access to the seeded need-category lookup (spec §3.3). */
@Service
public class NeedCategoryService {

    private final NeedCategoryRepository needCategoryRepository;

    public NeedCategoryService(NeedCategoryRepository needCategoryRepository) {
        this.needCategoryRepository = needCategoryRepository;
    }

    @Transactional(readOnly = true)
    public List<NeedCategoryResponse> listActive() {
        return needCategoryRepository.findByActiveTrueOrderByName().stream()
                .map(NeedCategoryResponse::fromEntity)
                .toList();
    }
}
