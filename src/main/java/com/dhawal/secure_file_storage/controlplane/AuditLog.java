package com.dhawal.secure_file_storage.controlplane;

public interface AuditLog {

    void record(AuditEvent event);
}
