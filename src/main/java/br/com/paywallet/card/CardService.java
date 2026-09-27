package br.com.paywallet.card;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.card.CardDtos.AuthorizationResponse;
import br.com.paywallet.card.CardDtos.CardResponse;
import br.com.paywallet.card.CardDtos.IssueCardRequest;
import br.com.paywallet.credit.CreditAnalysisService;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.observability.PartnerCalls;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;

/** Issues and manages virtual cards. The processor keeps the card number; this service keeps its token. */
@Service
public class CardService {

    /** Credit card limit per risk band of the credit analysis, in cents. */
    static final Map<String, Long> CREDIT_LIMIT_BY_BAND = Map.of("A", 500_000L, "B", 200_000L, "C", 80_000L);
    static final int DEFAULT_CLOSING_DAY = 5;

    private final CardRepository cards;
    private final CardAuthorizationRepository authorizations;
    private final CardCharges charges;
    private final CardProcessor processor;
    private final PartnerCalls partners;
    private final CreditAnalysisService analyses;
    private final UserService users;
    private final CardProperties props;
    private final Clock clock;

    public CardService(CardRepository cards, CardAuthorizationRepository authorizations, CardCharges charges,
                       CardProcessor processor, PartnerCalls partners, CreditAnalysisService analyses,
                       UserService users, CardProperties props, Clock clock) {
        this.cards = cards;
        this.authorizations = authorizations;
        this.charges = charges;
        this.processor = processor;
        this.partners = partners;
        this.analyses = analyses;
        this.users = users;
        this.props = props;
        this.clock = clock;
    }

    /** Not transactional: a rejected credit analysis must still be stored, so it is reused until it expires. */
    public CardResponse issue(Long userId, IssueCardRequest req) {
        var user = users.get(userId);
        boolean alreadyHasOne = cards.findByUserIdOrderByCreatedAt(userId).stream()
                .anyMatch(c -> c.getType() == req.type() && c.getStatus() != Card.Status.CANCELLED);
        if (alreadyHasOne) {
            throw new ConflictException("There is already a %s card for this account".formatted(req.type()));
        }
        Long limit = null;
        Integer closingDay = null;
        if (req.type() == Card.Type.CREDIT) {
            if (user.getType() != UserType.COMMON) {
                throw new BusinessException("Credit cards are available to individuals only");
            }
            var analysis = analyses.current(userId);
            if (!analysis.isApproved()) {
                List<String> reasons = analysis.reasonList();
                throw new BusinessException("Credit not approved" + (reasons.isEmpty() ? "" : ": " + reasons.getFirst()));
            }
            limit = CREDIT_LIMIT_BY_BAND.get(analysis.getRiskBand());
            closingDay = req.closingDay() == null ? DEFAULT_CLOSING_DAY : req.closingDay();
        }
        var issued = partners.call("card-processor", "issue",
                () -> processor.issue(userId, user.getFullName(), req.type()));
        try {
            return response(cards.saveAndFlush(new Card(userId, req.type(), issued, limit, closingDay, clock.instant())));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("There is already a %s card for this account".formatted(req.type()));
        }
    }

    @Transactional(readOnly = true)
    public List<CardResponse> list(Long userId) {
        return cards.findByUserIdOrderByCreatedAt(userId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public CardResponse get(Long userId, UUID cardId) {
        return response(owned(userId, cardId));
    }

    @Transactional
    public CardResponse block(Long userId, UUID cardId) {
        return transition(userId, cardId, Card.Status.ACTIVE, Card.Status.BLOCKED);
    }

    @Transactional
    public CardResponse unblock(Long userId, UUID cardId) {
        return transition(userId, cardId, Card.Status.BLOCKED, Card.Status.ACTIVE);
    }

    /** Irreversible. A credit card can only be cancelled once nothing is owed on it. */
    @Transactional
    public CardResponse cancel(Long userId, UUID cardId) {
        var card = lockOwned(userId, cardId);
        if (card.getStatus() == Card.Status.CANCELLED) {
            return response(card);
        }
        if (card.getType() == Card.Type.CREDIT && charges.used(cardId) > 0) {
            throw new BusinessException("Pay the outstanding balance before cancelling the card");
        }
        card.changeStatus(Card.Status.CANCELLED);
        partners.run("card-processor", "update-status",
                () -> processor.updateStatus(card.getProcessorToken(), Card.Status.CANCELLED));
        return response(card);
    }

    @Transactional(readOnly = true)
    public Page<AuthorizationResponse> transactions(Long userId, UUID cardId, Pageable pageable) {
        owned(userId, cardId);
        return authorizations.findByCardIdOrderByCreatedAtDesc(cardId, pageable).map(CardService::response);
    }

    Card owned(Long userId, UUID cardId) {
        return cards.findByIdAndUserId(cardId, userId).orElseThrow(() -> new NotFoundException("Card not found"));
    }

    private Card lockOwned(Long userId, UUID cardId) {
        var card = cards.lockById(cardId).filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Card not found"));
        if (card.getStatus() != Card.Status.CANCELLED && card.isExpired(LocalDate.now(clock.withZone(props.zone())))) {
            throw new BusinessException("Card expired");
        }
        return card;
    }

    private CardResponse transition(Long userId, UUID cardId, Card.Status from, Card.Status to) {
        var card = lockOwned(userId, cardId);
        if (card.getStatus() == to) {
            return response(card);
        }
        if (card.getStatus() != from) {
            throw new BusinessException("Card is %s".formatted(card.getStatus()));
        }
        card.changeStatus(to);
        partners.run("card-processor", "update-status", () -> processor.updateStatus(card.getProcessorToken(), to));
        return response(card);
    }

    CardResponse response(Card card) {
        boolean credit = card.getType() == Card.Type.CREDIT;
        return new CardResponse(card.getId(), card.getType(), card.getStatus(), card.getBrand(), card.getLast4(),
                card.getExpMonth(), card.getExpYear(),
                credit ? Money.fromCents(card.getCreditLimit()) : null,
                credit ? Money.fromCents(Math.max(0, card.getCreditLimit() - charges.used(card.getId()))) : null,
                card.getClosingDay(), card.getCreatedAt());
    }

    static AuthorizationResponse response(CardAuthorization a) {
        return new AuthorizationResponse(a.getId(), a.getCardId(), Money.fromCents(a.getAmount()), a.getMerchantName(),
                a.getMcc(), a.getInstallments(), a.getStatus(), a.getResponseCode(), a.getDeclineReason(),
                a.getClearedAmount() == null ? null : Money.fromCents(a.getClearedAmount()), a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
