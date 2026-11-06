package io.softa.app.demo.structure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.fail;

/**
 * Every place framework or starter code steps around a permission guard is listed, with its reason, in
 * {@code permission/bypass-allowlist.txt}. A guard is stepped around by:
 * <ul>
 *   <li>a read that skips the caller's row range ({@code *IgnoringRowScope}, {@code getDisplayNames});</li>
 *   <li>switching the permission check off ({@code @SkipPermissionCheck}, {@code Context.setSkipPermissionCheck},
 *       {@code ContextUtils.inSystemContext} / {@code inTenantContext});</li>
 *   <li>writing through {@code JdbcService} directly, which checks no id.</li>
 * </ul>
 * The list must match the code exactly: a new site fails until it is added with a reason, and a site
 * that is gone fails until its line is removed, so every change to it is reviewed.
 *
 * <p>It lives in this app because the check spans every module: the classes are read from each
 * module's {@code target/classes}, which a reactor build has produced by the time this module runs.
 */
class PermissionBypassAllowlistTest {

    private static final String ALLOWLIST = "/permission/bypass-allowlist.txt";
    private static final String SKIP_ANNOTATION = "io.softa.framework.orm.annotation.SkipPermissionCheck";
    private static final String JDBC_SERVICE = "io.softa.framework.orm.jdbc.JdbcService";

    @Test
    void everyPermissionBypassIsListed() throws IOException {
        Set<String> found = bypassSites(importModules());
        Map<String, String> listed = loadAllowlist();
        Set<String> unlisted = new TreeSet<>(found);
        unlisted.removeAll(listed.keySet());
        Set<String> stale = new TreeSet<>(listed.keySet());
        stale.removeAll(found);
        if (unlisted.isEmpty() && stale.isEmpty()) {
            return;
        }
        List<String> message = new ArrayList<>();
        if (!unlisted.isEmpty()) {
            message.add(unlisted.size() + " permission bypass site(s) not in " + ALLOWLIST
                    + ". Remove the bypass, or add the line with the reason it is safe:");
            unlisted.forEach(site -> message.add("    " + site + " | <why>"));
        }
        if (!stale.isEmpty()) {
            message.add(stale.size() + " line(s) in " + ALLOWLIST + " name a site that no longer exists; delete them:");
            stale.forEach(site -> message.add("    " + site));
        }
        fail(String.join("\n", message));
    }

    private static JavaClasses importModules() throws IOException {
        Path root = Path.of("").toAbsolutePath().resolve("../..").normalize();
        List<Path> classDirs = new ArrayList<>();
        for (String group : List.of("framework", "starters")) {
            try (Stream<Path> modules = Files.list(root.resolve(group))) {
                modules.filter(Files::isDirectory).filter(dir -> Files.exists(dir.resolve("pom.xml"))).forEach(module -> {
                    Path classes = module.resolve("target/classes");
                    if (!Files.isDirectory(classes)) {
                        fail("No compiled classes in " + classes + "; build the whole reactor (mvn test from the root) first");
                    }
                    classDirs.add(classes);
                });
            }
        }
        return new ClassFileImporter().importPaths(classDirs);
    }

    static Set<String> bypassSites(JavaClasses classes) {
        Set<String> sites = new TreeSet<>();
        for (JavaClass type : classes) {
            if (type.isAnnotatedWith(SKIP_ANNOTATION)) {
                sites.add(type.getName() + " @SkipPermissionCheck");
            }
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                if (unit.isAnnotatedWith(SKIP_ANNOTATION)) {
                    sites.add(siteOf(unit) + " @SkipPermissionCheck");
                }
                for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                    String target = bypassTarget(call);
                    if (target != null && !call.getTargetOwner().equals(type)) {
                        sites.add(siteOf(unit) + " -> " + target);
                    }
                }
            }
        }
        return sites;
    }

    private static String bypassTarget(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        String label = call.getTargetOwner().getSimpleName() + "." + name;
        if (name.endsWith("IgnoringRowScope") || name.equals("getDisplayNames")) {
            return label;
        }
        if (owner.equals("io.softa.framework.base.context.Context") && name.equals("setSkipPermissionCheck")) {
            return label;
        }
        if (owner.equals("io.softa.framework.base.context.ContextUtils")
                && (name.equals("inSystemContext") || name.equals("inTenantContext"))) {
            return label;
        }
        if (call.getTargetOwner().isAssignableTo(JDBC_SERVICE)
                && (name.startsWith("insert") || name.startsWith("update") || name.startsWith("delete"))) {
            return "JdbcService." + name;
        }
        return null;
    }

    /** {@code Owner#method}, with a lambda body attributed to the method that declares it. */
    private static String siteOf(JavaCodeUnit unit) {
        String name = unit.getName();
        if (name.startsWith("lambda$")) {
            String[] parts = name.split("\\$");
            name = parts.length > 1 ? parts[1] : name;
        }
        return unit.getOwner().getName() + "#" + name;
    }

    private static Map<String, String> loadAllowlist() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = PermissionBypassAllowlistTest.class.getResourceAsStream(ALLOWLIST)) {
            if (in == null) {
                throw new IllegalStateException("Missing allowlist " + ALLOWLIST);
            }
            int lineNo = 0;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                lineNo++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int bar = trimmed.lastIndexOf(" | ");
                String site = bar < 0 ? trimmed : trimmed.substring(0, bar).strip();
                String reason = bar < 0 ? "" : trimmed.substring(bar + 3).strip();
                if (reason.isEmpty()) {
                    throw new IllegalStateException(ALLOWLIST + ":" + lineNo + " has no reason: " + trimmed);
                }
                if (entries.put(site, reason) != null) {
                    throw new IllegalStateException(ALLOWLIST + ":" + lineNo + " lists a site twice: " + site);
                }
            }
        }
        return entries;
    }
}
