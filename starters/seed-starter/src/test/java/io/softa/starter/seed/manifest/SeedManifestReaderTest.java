package io.softa.starter.seed.manifest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedManifestReaderTest {

    private static final Predicate<String> EVERY_FILE_EXISTS = path -> true;

    @Test
    void readsBothListsInOrderWithTheLevelFromTheDirectory() {
        SeedManifest manifest = read("""
                platform:
                  - data-system/Navigation.Hcm.json
                  - data-platform/MailTemplate.Platform.json
                  - data-system/Permission.Hcm.json
                tenant:
                  - files:
                      - data-tenant/SysSequence.Default.json
                      - file: data-tenant/Role.Tenant.json
                        mode: overwrite
                  - module: leave
                    countries: [NZ]
                    files:
                      - data-tenant/LeaveType.NZ.json
                """);

        // Load order is the list's order, whichever directory a platform file sits in.
        assertEquals(List.of("Navigation.Hcm.json", "MailTemplate.Platform.json", "Permission.Hcm.json"),
                names(manifest.platformFiles()));
        assertEquals(List.of("SysSequence.Default.json", "Role.Tenant.json", "LeaveType.NZ.json"),
                names(manifest.tenantFiles()));
        assertEquals(SeedLevel.PLATFORM_GLOBAL, manifest.file("Navigation.Hcm.json").orElseThrow().level());
        assertEquals(SeedLevel.PLATFORM_TENANT, manifest.file("MailTemplate.Platform.json").orElseThrow().level());
        SeedFile role = manifest.file("Role.Tenant.json").orElseThrow();
        assertEquals(SeedFileMode.OVERWRITE, role.mode());
        assertTrue(role.reloadsWhenChanged());
        // A tenant file runs once, and a platform file reloads whenever it changes, without saying so.
        SeedFile sequence = manifest.file("SysSequence.Default.json").orElseThrow();
        assertEquals(SeedFileMode.ONCE, sequence.mode());
        assertFalse(sequence.reloadsWhenChanged());
        SeedFile navigation = manifest.file("Navigation.Hcm.json").orElseThrow();
        assertEquals(SeedFileMode.OVERWRITE, navigation.mode());
        assertTrue(navigation.reloadsWhenChanged());
        // A group's module and countries are its files'.
        SeedFile leaveType = manifest.file("LeaveType.NZ.json").orElseThrow();
        assertEquals("leave", leaveType.module());
        assertEquals(List.of("NZ"), leaveType.countries());
        assertNull(role.module());
        assertEquals("data-tenant/LeaveType.NZ.json", leaveType.path());
    }

    @Test
    void thePlatformFilesInModeStartupAreTheOnesTheBuildOwns() {
        SeedManifest manifest = read("""
                platform:
                  - file: data-system/Navigation.Hcm.json
                    mode: startup
                  - data-system/Currency.json
                  - file: data-system/Permission.Hcm.json
                    mode: startup
                  - file: data-system/Bank.json
                    mode: overwrite
                """);

        // Only those, in load order.
        assertEquals(List.of("Navigation.Hcm.json", "Permission.Hcm.json"), names(manifest.startupFiles()));
        assertTrue(manifest.file("Navigation.Hcm.json").orElseThrow().loadsAtStartup());
        assertFalse(manifest.file("Currency.json").orElseThrow().loadsAtStartup());
        // The default, written out.
        assertEquals(SeedFileMode.OVERWRITE, manifest.file("Bank.json").orElseThrow().mode());
    }

    @Test
    void aTenantFileRunsOnceAppendsOrOverwrites() {
        SeedManifest manifest = read("""
                tenant:
                  - files:
                      - data-tenant/SysSequence.json
                      - file: data-tenant/LeaveType.json
                        mode: once
                      - file: data-tenant/MailTemplate.json
                        mode: append
                      - file: data-tenant/Role.Tenant.json
                        mode: overwrite
                """);

        assertEquals(List.of(SeedFileMode.ONCE, SeedFileMode.ONCE, SeedFileMode.APPEND, SeedFileMode.OVERWRITE),
                manifest.tenantFiles().stream().map(SeedFile::mode).toList());
        // An append file runs again when it changes, for the rows a tenant lacks.
        assertTrue(manifest.file("MailTemplate.json").orElseThrow().reloadsWhenChanged());
    }

    @Test
    void aModeItsListDoesNotTakeIsRefused() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - file: data-system/A.json
                    mode: append
                  - file: data-system/B.json
                    mode: yes
                tenant:
                  - files:
                      - file: data-tenant/C.json
                        mode: startup
                      - file: data-tenant/D.json
                        mode: sometimes
                """));

        List<String> problems = failure.getProblems();
        assertHas(problems, "platform file 'data-system/A.json' has mode 'append', which only a tenant file takes; "
                + "a platform file's mode is overwrite (the default) or startup");
        // YAML reads a bare yes as true, which names no mode.
        assertHas(problems, "platform file 'data-system/B.json' has mode 'true'; a platform file's mode is");
        assertHas(problems, "tenant file 'data-tenant/C.json' has mode 'startup', which only a platform file takes; "
                + "a tenant file's mode is once (the default), append or overwrite");
        assertHas(problems, "tenant file 'data-tenant/D.json' has mode 'sometimes'; a tenant file's mode is");
    }

    @Test
    void theFlagsTheModeReplacedAreUnknownKeysThatSayWhatToWrite() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - file: data-system/Navigation.Hcm.json
                    auto: true
                tenant:
                  - files:
                      - file: data-tenant/Role.Tenant.json
                        overwrite: true
                """));

        List<String> problems = failure.getProblems();
        assertHas(problems, "file 'data-system/Navigation.Hcm.json' has an unknown key 'auto' (allowed: file, mode); "
                + "'auto: true' is now 'mode: startup'");
        assertHas(problems, "file 'data-tenant/Role.Tenant.json' has an unknown key 'overwrite' (allowed: file, mode); "
                + "'overwrite: true' is now 'mode: overwrite'");
    }

    @Test
    void theRetiredBootstrapFlagIsAnUnknownKey() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - file: data-system/Navigation.Hcm.json
                    bootstrap: true
                """));

        assertHas(failure.getProblems(), "has an unknown key 'bootstrap'");
    }

    @Test
    void aTenantIsDueTheFilesOfItsPlanAndCountries() {
        SeedManifest manifest = read("""
                tenant:
                  - files:
                      - data-tenant/Base.json
                  - countries: [NZ]
                    files:
                      - data-tenant/Base.NZ.json
                  - module: leave
                    files:
                      - data-tenant/Leave.json
                """);

        assertEquals(List.of("Base.json"), names(manifest.tenantFiles(Set.of("core-hr"), Set.of("SG"))));
        assertEquals(List.of("Base.json", "Base.NZ.json", "Leave.json"),
                names(manifest.tenantFiles(Set.of("leave"), Set.of("SG", "NZ"))));
        // No plans in use: every module's files are due.
        assertEquals(List.of("Base.json", "Leave.json"), names(manifest.tenantFiles(null, Set.of())));
    }

    @Test
    void everythingWrongIsReportedAtOnce() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - data-system/A.json
                  - file: data-system/B.json
                    mode: once
                  - data-tenant/C.json
                tenant:
                  - module: leave
                    countrys: [SG]
                    files:
                      - data-tenant/D.json
                      - data-system/E.json
                      - file: data-tenant/F.json
                        mode: sometimes
                  - countries: [SG, SG]
                    files:
                      - data-tenant/A.json
                  - module: payroll
                tenants: []
                """));

        List<String> problems = failure.getProblems();
        assertHas(problems, "the manifest has an unknown key 'tenants'");
        assertHas(problems, "platform file 'data-system/B.json' has mode 'once', which only a tenant file takes");
        assertHas(problems, "platform file 'data-tenant/C.json' is not in data-system/ or data-platform/");
        assertHas(problems, "tenant group #1 has an unknown key 'countrys'");
        assertHas(problems, "tenant file 'data-system/E.json' is not in data-tenant/");
        assertHas(problems, "tenant file 'data-tenant/F.json' has mode 'sometimes'");
        assertHas(problems, "lists 'SG' twice");
        assertHas(problems, "file 'A.json' is registered twice");
        assertHas(problems, "tenant group #3 has no files");
    }

    @Test
    void aRepeatedKeyIsRefusedRatherThanDroppingTheFirstOnesFiles() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - data-system/A.json
                tenant:
                  - files:
                      - data-tenant/B.json
                platform:
                  - data-system/C.json
                """));

        assertHas(failure.getProblems(), "the manifest repeats a key");
    }

    @Test
    void whatYamlWouldMisreadIsRefused() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - data-system/Rules.xml
                tenant:
                  - module: [leave, payroll]
                    files:
                      - data-tenant/A.json
                  - countries: [NZ, NO, sg]
                    files:
                      - data-tenant/B.json
                """));

        List<String> problems = failure.getProblems();
        // A list is not one module: as a name, "[leave, payroll]" matches no tenant's plan.
        assertHas(problems, "tenant group #1: module must be one module name");
        // YAML reads an unquoted NO (Norway) as false, and a lower-case code never matches a country.
        assertHas(problems, "has 'false', which YAML reads from an unquoted NO");
        assertHas(problems, "has 'sg', which is not a two-letter upper-case country code");
        assertHas(problems, "file 'data-system/Rules.xml' is not a .json or .csv file");
    }

    @Test
    void theOldListOfPackagesIsRefused() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                - key: base
                  files:
                    - file: SysSequence.Default.json
                      level: TENANT
                """));

        assertHas(failure.getProblems(), "must be a map with a `platform` list and a `tenant` list");
    }

    @Test
    void aRegisteredFileMustBeOnTheClasspath() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> SeedManifestReader.read(
                stream("""
                        tenant:
                          - files:
                              - data-tenant/Missing.json
                        """), "test", path -> !path.equals("data-tenant/Missing.json")));

        assertEquals(List.of("file 'data-tenant/Missing.json' is not on the classpath"), failure.getProblems());
    }

    private static void assertHas(List<String> problems, String fragment) {
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(fragment)), problems.toString());
    }

    private static SeedManifest read(String yaml) {
        return SeedManifestReader.read(stream(yaml), "test", EVERY_FILE_EXISTS);
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(List<SeedFile> files) {
        return files.stream().map(SeedFile::file).toList();
    }
}
