package br.com.paywallet.feed;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeedRepository extends MongoRepository<FeedEntry, String> {

    Page<FeedEntry> findByParticipantIds(Long userId, Pageable pageable);

    Page<FeedEntry> findByVisibility(Visibility visibility, Pageable pageable);
}
