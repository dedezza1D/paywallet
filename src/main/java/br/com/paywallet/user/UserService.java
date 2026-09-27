package br.com.paywallet.user;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.crypto.FieldCipher;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserDtos.CreateUserRequest;
import br.com.paywallet.user.UserDtos.UserResponse;

@Service
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final LedgerService ledger;
    private final ApplicationEventPublisher events;
    private final FieldCipher cipher;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, LedgerService ledger,
                       ApplicationEventPublisher events, FieldCipher cipher) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.ledger = ledger;
        this.events = events;
        this.cipher = cipher;
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        if (req.type() == UserType.COMMON && req.document().length() != 11) {
            throw new BusinessException("Individual users must register with a CPF");
        }
        if (req.type() == UserType.MERCHANT && req.document().length() != 14) {
            throw new BusinessException("Merchants must register with a CNPJ");
        }
        var email = req.email().toLowerCase();
        PasswordPolicy.check(req.password(), email, req.document());
        String documentIndex = cipher.blindIndex(req.document());
        if (repository.existsByDocumentIndex(documentIndex)) {
            throw new BusinessException("Document already registered");
        }
        if (repository.existsByEmail(email)) {
            throw new BusinessException("Email already registered");
        }
        var user = repository.save(new User(req.fullName(), req.document(), documentIndex, email,
                passwordEncoder.encode(req.password()), req.type()));
        ledger.openUserWallet(user.getId());
        events.publishEvent(new UserRegisteredEvent(user.getId(), user.getEmail()));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public User get(Long id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.user(id));
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return UserResponse.from(get(id));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return repository.findAll().stream().map(UserResponse::from).toList();
    }
}
