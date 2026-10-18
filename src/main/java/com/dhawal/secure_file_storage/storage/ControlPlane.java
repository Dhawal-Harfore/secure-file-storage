package com.dhawal.secure_file_storage.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.dhawal.secure_file_storage.controlplane.AuditLog;
import com.dhawal.secure_file_storage.controlplane.FileCatalog;
import com.dhawal.secure_file_storage.controlplane.FileCipher;
import com.dhawal.secure_file_storage.controlplane.PermitService;

@Component
public class ControlPlane {

    private final ObjectProvider<FileCipher> cipher;
    private final ObjectProvider<PermitService> permits;
    private final ObjectProvider<FileCatalog> catalog;
    private final ObjectProvider<AuditLog> audit;

    public ControlPlane(
            ObjectProvider<FileCipher> cipher,
            ObjectProvider<PermitService> permits,
            ObjectProvider<FileCatalog> catalog,
            ObjectProvider<AuditLog> audit) {
        this.cipher = cipher;
        this.permits = permits;
        this.catalog = catalog;
        this.audit = audit;
    }

    public boolean ready() {
        return cipher.getIfAvailable() != null
                && permits.getIfAvailable() != null
                && catalog.getIfAvailable() != null
                && audit.getIfAvailable() != null;
    }

    public FileCipher cipher() {
        return require(cipher, "encryption service");
    }

    public PermitService permits() {
        return require(permits, "policy engine");
    }

    public FileCatalog catalog() {
        return require(catalog, "file catalog");
    }

    public AuditLog audit() {
        return require(audit, "audit log");
    }

    private static <T> T require(ObjectProvider<T> provider, String what) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                    "File storage isn't connected to the " + what + " yet.");
        }
        return bean;
    }
}
