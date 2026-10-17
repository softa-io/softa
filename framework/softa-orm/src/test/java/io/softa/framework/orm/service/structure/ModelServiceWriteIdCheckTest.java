package io.softa.framework.orm.service.structure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.ModelServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every public write entry of {@link ModelServiceImpl} reaches an id check of {@link PermissionService}
 * on its own path, following calls inside the class (lambdas included). A new write entry, or a
 * refactor that routes an existing one around the check, fails here instead of letting a caller write
 * rows outside its range.
 */
class ModelServiceWriteIdCheckTest {

    /** Name prefixes of the write entries. */
    private static final List<String> WRITE_PREFIXES = List.of(
            "create", "update", "delete", "copy", "addVersion", "setEndDate");

    /** The permission calls that check the ids a write touches. */
    private static final Set<String> ID_CHECKS = Set.of("checkIdsAccess", "checkIdAccess", "checkIdsFieldsAccess");

    @Test
    void everyPublicWriteEntryReachesAnIdCheck() {
        JavaClass service = new ClassFileImporter().importClass(ModelServiceImpl.class);
        Set<String> entries = new TreeSet<>();
        Set<String> unchecked = new TreeSet<>();
        for (JavaMethod method : service.getMethods()) {
            if (!method.getModifiers().contains(JavaModifier.PUBLIC) || method.getModifiers().contains(JavaModifier.SYNTHETIC)
                    || WRITE_PREFIXES.stream().noneMatch(method.getName()::startsWith)) {
                continue;
            }
            String entry = method.getName() + method.getRawParameterTypes().stream()
                    .map(JavaClass::getSimpleName).toList();
            entries.add(entry);
            if (!reachesIdCheck(service, method)) {
                unchecked.add(entry);
            }
        }
        assertThat(entries).as("write entries found").isNotEmpty();
        assertThat(unchecked).as("write entries with no id check on their path").isEmpty();
    }

    private static boolean reachesIdCheck(JavaClass service, JavaMethod entry) {
        Deque<JavaCodeUnit> todo = new ArrayDeque<>();
        Set<JavaCodeUnit> seen = new HashSet<>();
        todo.add(entry);
        while (!todo.isEmpty()) {
            JavaCodeUnit unit = todo.pop();
            if (!seen.add(unit)) {
                continue;
            }
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().isAssignableTo(PermissionService.class) && ID_CHECKS.contains(call.getName())) {
                    return true;
                }
                if (call.getTargetOwner().equals(service)) {
                    // Every overload of the called name: a delegating overload may pick any of them.
                    for (JavaCodeUnit candidate : service.getCodeUnits()) {
                        if (candidate.getName().equals(call.getName())) {
                            todo.add(candidate);
                        }
                    }
                }
            }
            // A lambda written in this method is a synthetic method of its own.
            for (JavaCodeUnit candidate : service.getCodeUnits()) {
                if (candidate.getName().startsWith("lambda$" + unit.getName() + "$")) {
                    todo.add(candidate);
                }
            }
        }
        return false;
    }
}
