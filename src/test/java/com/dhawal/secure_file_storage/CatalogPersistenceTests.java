package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.controlplane.FileKind;
import com.dhawal.secure_file_storage.controlplane.JpaFileCatalog;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.FileRenditionRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogPersistenceTests {

    @Autowired
    private StoredFileRepository files;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private FileRenditionRepository renditions;

    @Test
    void renditionsSurviveARestart() {
        SessionUser owner = SessionUser.of("catalog-owner@example.com");
        Instant now = Instant.now();
        Rendition original = new Rendition(QualityTier.ORIGINAL, "video/mp4", 28_656_976, "kh_0123456789abcdef_original");
        new JpaFileCatalog(files, users, renditions).add(new StoredFile("f_0123456789abcdef", "walk.mp4", "video/mp4",
                28_656_976, FileKind.VIDEO, owner.id(), owner.email(), now, now, Map.of(QualityTier.ORIGINAL, original)));
        new JpaFileCatalog(files, users, renditions).addRendition("f_0123456789abcdef",
                new Rendition(QualityTier.REDUCED, "video/webm", 1_024_945, "kh_0123456789abcdef_reduced"));

        StoredFile afterRestart = new JpaFileCatalog(files, users, renditions).find("f_0123456789abcdef").orElseThrow();

        assertThat(afterRestart.renditions()).containsOnlyKeys(QualityTier.ORIGINAL, QualityTier.REDUCED);
        assertThat(afterRestart.renditions().get(QualityTier.ORIGINAL)).isEqualTo(original);
        assertThat(afterRestart.renditions().get(QualityTier.REDUCED).keyHandle()).isEqualTo("kh_0123456789abcdef_reduced");
        assertThat(afterRestart.bestAtMost(QualityTier.LOWEST)).isNull();
    }
}
