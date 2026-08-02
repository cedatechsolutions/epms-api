package com.cems.api.repository;

import com.cems.api.entity.CommunityDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommunityDocumentRepository extends JpaRepository<CommunityDocument, String> {

    List<CommunityDocument> findByCommunityIdOrderByCreatedAtDesc(String communityId);

    Optional<CommunityDocument> findByIdAndCommunityId(String id, String communityId);
}
