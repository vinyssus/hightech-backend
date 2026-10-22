package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.ReviewHelpfulVote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewHelpfulVoteRepository extends JpaRepository<ReviewHelpfulVote, ReviewHelpfulVote.Key> {
}
