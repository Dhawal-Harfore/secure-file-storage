package com.dhawal.secure_file_storage.security;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.UserAccount;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;

@Service
public class AuthenticatedUserService {

    private final UserAccountRepository users;

    public AuthenticatedUserService(UserAccountRepository users) {
        this.users = users;
    }

    @Transactional
    public SessionUser resolve(String email) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        SessionUser sessionUser = SessionUser.of(normalized);
        users.findByEmail(normalized)
                .orElseGet(() -> users.save(new UserAccount(sessionUser.id(), normalized)));
        return sessionUser;
    }
}
