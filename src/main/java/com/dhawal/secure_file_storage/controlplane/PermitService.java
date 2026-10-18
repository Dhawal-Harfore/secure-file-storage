package com.dhawal.secure_file_storage.controlplane;

public interface PermitService {

    Permit evaluate(String fileId, String subjectId);
}
