package sdd.plan.gen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SafeWriteTest {
    @TempDir Path dir;

    @Test
    void firstWriteHasNoBackupSecondWriteBacksUpThirdReplacesTheBackup() throws Exception {
        Path target = dir.resolve("x.plan.md");

        assertThat(SafeWrite.writeWithBackup(target, "v1")).isNull();
        assertThat(Files.readString(target)).isEqualTo("v1");

        Path backup = SafeWrite.writeWithBackup(target, "v2");
        assertThat(backup).isEqualTo(dir.resolve("x.plan.md.bak"));
        assertThat(Files.readString(backup)).isEqualTo("v1");
        assertThat(Files.readString(target)).isEqualTo("v2");

        assertThat(SafeWrite.writeWithBackup(target, "v3")).isEqualTo(backup);
        assertThat(Files.readString(backup)).isEqualTo("v2");
        assertThat(Files.readString(target)).isEqualTo("v3");
    }
}
