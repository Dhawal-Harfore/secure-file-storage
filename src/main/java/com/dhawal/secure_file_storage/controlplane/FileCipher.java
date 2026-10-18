package com.dhawal.secure_file_storage.controlplane;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;

public interface FileCipher {

    OutputStream encrypt(String keyHandle, OutputStream ciphertext) throws IOException;

    InputStream decrypt(String keyHandle, SeekableByteChannel ciphertext, long plaintextOffset)
            throws IOException;

    void destroy(String keyHandle) throws IOException;
}
