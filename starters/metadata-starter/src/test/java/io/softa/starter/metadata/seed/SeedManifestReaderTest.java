package io.softa.starter.metadata.seed;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A manifest is refused with every problem named, so a broken one is fixed in one pass; a good one is
 * read into packages and files and loaded in dependency order.
 */
class SeedManifestReaderTest {

    private static SeedManifest read(String yaml) {
        return SeedManifestReader.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    private static List<String> problemsOf(String yaml) {
        SeedManifestException e = catchThrowableOfType(SeedManifestException.class, () -> read(yaml));
        assertThat(e).as("the manifest should have been refused").isNotNull();
        return e.getProblems();
    }

    @Test
    void readsPackagesAndFiles() {
        SeedManifest manifest = read("""
                - key: leave
                  name: Leave
                  module: leave
                  changelog: First version
                  files:
                    - file: LeaveType.SG.json
                      level: TENANT
                      countries: [SG]
                      retired: [leave_type.SG.Paternity]
                    - file: LeavePolicy.SG.json
                      level: TENANT
                      countries: [SG]
                      dependsOn: [LeaveType.SG.json]
                - key: platform
                  files:
                    - file: Bank.Default.json
                      level: PLATFORM_GLOBAL
                    - file: ImportTemplate.Company-SG.json
                      level: TENANT
                      push: InvalidColumns
                """);

        assertThat(manifest.packages()).extracting(SeedPackage::key).containsExactly("leave", "platform");
        SeedPackage leave = manifest.packages().getFirst();
        assertThat(leave.module()).isEqualTo("leave");
        assertThat(leave.changelog()).isEqualTo("First version");

        SeedFile leaveType = manifest.file("LeaveType.SG.json").orElseThrow();
        assertThat(leaveType.level()).isEqualTo(SeedLevel.TENANT);
        assertThat(leaveType.countries()).containsExactly("SG");
        assertThat(leaveType.retired()).containsExactly("leave_type.SG.Paternity");
        assertThat(leaveType.push()).isEqualTo(SeedPushScope.NONE);
        assertThat(leaveType.path()).isEqualTo("data-tenant/LeaveType.SG.json");
        assertThat(leaveType.packageKey()).isEqualTo("leave");

        assertThat(manifest.file("Bank.Default.json").orElseThrow().path()).isEqualTo("data-system/Bank.Default.json");
        assertThat(manifest.file("ImportTemplate.Company-SG.json").orElseThrow().push())
                .isEqualTo(SeedPushScope.INVALID_COLUMNS);
        assertThat(manifest.packages().get(1).module()).isNull();
    }

    @Test
    void platformTenantFilesLiveInTheirOwnDirectory() {
        SeedManifest manifest = read("""
                - key: platform-tenant
                  files:
                    - file: MailTemplate.Platform.json
                      level: PLATFORM_TENANT
                """);

        assertThat(manifest.file("MailTemplate.Platform.json").orElseThrow().path())
                .isEqualTo("data-platform/MailTemplate.Platform.json");
        assertThat(manifest.files(SeedLevel.TENANT)).isEmpty();
    }

    @Test
    void aFileIsLoadedAfterWhatItDependsOnAndOtherwiseInManifestOrder() {
        SeedManifest manifest = read("""
                - key: a
                  files:
                    - file: OvertimePolicy.SG.json
                      level: TENANT
                      dependsOn: [LeaveType.SG.json]
                    - file: MailTemplate.Default.json
                      level: TENANT
                - key: b
                  files:
                    - file: LeavePolicy.SG.json
                      level: TENANT
                      dependsOn: [LeaveType.SG.json]
                    - file: LeaveType.SG.json
                      level: TENANT
                    - file: Bank.Default.json
                      level: PLATFORM_GLOBAL
                """);

        assertThat(manifest.loadOrder(SeedLevel.TENANT)).containsExactly(
                "MailTemplate.Default.json", "LeaveType.SG.json", "OvertimePolicy.SG.json", "LeavePolicy.SG.json");
        assertThat(manifest.loadOrder(SeedLevel.PLATFORM_GLOBAL)).containsExactly("Bank.Default.json");
    }

    @Test
    void aManifestWithoutDependenciesLoadsExactlyAsWritten() {
        SeedManifest manifest = read("""
                - key: a
                  files:
                    - { file: C.json, level: TENANT }
                    - { file: A.json, level: TENANT }
                    - { file: B.json, level: TENANT }
                """);

        assertThat(manifest.loadOrder(SeedLevel.TENANT)).containsExactly("C.json", "A.json", "B.json");
    }

    @Test
    void aMissingOrUnknownLevelOrPushIsNamed() {
        List<String> problems = problemsOf("""
                - key: a
                  files:
                    - file: A.json
                    - file: B.json
                      level: GLOBAL
                    - file: C.json
                      level: TENANT
                      push: Everything
                """);

        assertThat(problems).containsExactlyInAnyOrder(
                "file 'A.json' has no level (TENANT, PLATFORM_GLOBAL or PLATFORM_TENANT)",
                "file 'B.json' has an unknown level 'GLOBAL' (TENANT, PLATFORM_GLOBAL or PLATFORM_TENANT)",
                "file 'C.json' has an unknown push 'Everything' (None, InvalidColumns or NewNestedItems)");
    }

    @Test
    void aMisspeltKeyIsReportedRatherThanIgnored() {
        List<String> problems = problemsOf("""
                - key: a
                  modul: leave
                  files:
                    - file: B.json
                      level: TENANT
                      dependOn: [A.json]
                """);

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).startsWith("package 'a' has an unknown key 'modul'");
        assertThat(problems.get(1)).startsWith("file 'B.json' has an unknown key 'dependOn'");
    }

    @Test
    void aFileRegisteredTwiceIsRefused() {
        assertThat(problemsOf("""
                - key: a
                  files:
                    - { file: A.json, level: TENANT }
                - key: b
                  files:
                    - { file: A.json, level: TENANT }
                """)).containsExactly("file 'A.json' is registered twice (packages 'a' and 'b')");
    }

    @Test
    void aDependencyMustNameARegisteredFileOfTheSameLevel() {
        List<String> problems = problemsOf("""
                - key: a
                  files:
                    - { file: A.json, level: TENANT, dependsOn: [Missing.json] }
                    - { file: B.json, level: TENANT, dependsOn: [Global.json] }
                    - { file: C.json, level: TENANT, dependsOn: [C.json] }
                    - { file: Global.json, level: PLATFORM_GLOBAL }
                """);

        assertThat(problems).containsExactlyInAnyOrder(
                "file 'A.json' depends on 'Missing.json', which is not registered",
                "file 'B.json' (TENANT) depends on 'Global.json' (PLATFORM_GLOBAL); a file can only depend "
                        + "on files of its own level, which are loaded together",
                "file 'C.json' depends on itself");
    }

    @Test
    void aDependencyCycleIsRefusedAndSpelledOut() {
        List<String> problems = problemsOf("""
                - key: a
                  files:
                    - { file: A.json, level: TENANT, dependsOn: [B.json] }
                    - { file: B.json, level: TENANT, dependsOn: [C.json] }
                    - { file: C.json, level: TENANT, dependsOn: [A.json] }
                """);

        assertThat(problems).containsExactly("files depend on each other in a cycle: A.json -> B.json -> C.json -> A.json");
    }

    @Test
    void aBlankOrRepeatedRetiredPreIdIsRefused() {
        List<String> problems = problemsOf("""
                - key: a
                  files:
                    - file: A.json
                      level: TENANT
                      retired: [old.one, '', old.one]
                """);

        assertThat(problems).containsExactlyInAnyOrder(
                "file 'A.json': retired has a blank entry",
                "file 'A.json': retired lists 'old.one' twice");
    }

    @Test
    void aManifestThatIsNotAListOfPackagesIsRefused() {
        assertThat(problemsOf("packages: []")).containsExactly("the manifest must be a list of packages");
        assertThat(problemsOf("- key: a")).containsExactly("package 'a' has no files");
    }

    @Test
    void theMessageListsEveryProblem() {
        assertThatThrownBy(() -> read("""
                - key: a
                  files:
                    - file: A.json
                    - { file: B.json, level: TENANT, dependsOn: [Missing.json] }
                """))
                .isInstanceOf(SeedManifestException.class)
                .hasMessageContaining("Invalid seed manifest test.yml")
                .hasMessageContaining("file 'A.json' has no level")
                .hasMessageContaining("file 'B.json' depends on 'Missing.json'");
    }
}
