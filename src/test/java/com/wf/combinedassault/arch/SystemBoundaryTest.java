package com.wf.combinedassault.arch;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.CombinedAssaultSystem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Package deps ({@code <sys>..}, {@code mixin.<sys>..}) within own system, {@code requires()} and the mandatory systems.
 * Edges onto a switchable system it does not require are {@link #GUARDED}: the reference is inert while the system is
 * off (instanceof, data-driven, behind {@code WFSystems.enabled}); leaf-off boot + gametest runs back each entry.
 * Gametests exempt.
 */
@AnalyzeClasses(packages = "com.wf.combinedassault",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class SystemBoundaryTest {
    private static final String ROOT = "com.wf.combinedassault.";
    private static final Map<String, Set<String>> ALLOWED = new HashMap<>();

    private static final Map<String, Set<String>> GUARDED = Map.ofEntries(
            Map.entry("aircraft", Set.of("energy", "uav")),
            Map.entry("ballistics", Set.of("camo", "radar")),
            Map.entry("cargo", Set.of("aircraft", "deck", "tow")),
            Map.entry("dynamics", Set.of("aircraft", "tow")),
            Map.entry("hud", Set.of("aircraft", "cargo", "energy", "indirect", "naval", "radar", "uav")),
            Map.entry("naval", Set.of("energy")),
            Map.entry("pack", Set.of("aircraft", "radar")),
            Map.entry("radar", Set.of("camo")),
            Map.entry("render", Set.of("aircraft", "camo", "cargo", "indirect", "naval", "props", "radar", "tow")),
            Map.entry("tow", Set.of("cargo", "energy")),
            Map.entry("uav", Set.of("energy")),
            Map.entry("vehicle", Set.of("aircraft", "airdefense", "camo", "cargo", "deck", "energy", "indirect", "naval", "props", "radar", "tow", "uav")),
            Map.entry("weapon", Set.of("aircraft", "airdefense", "energy", "indirect", "radar", "uav"))
    );

    static {
        Set<String> mandatory = new HashSet<>();
        CombinedAssault.SYSTEMS.stream().filter(CombinedAssaultSystem::mandatory).forEach(s -> mandatory.add(s.id()));
        for (CombinedAssaultSystem s : CombinedAssault.SYSTEMS) {
            Set<String> allowed = new HashSet<>(mandatory);
            allowed.addAll(s.requires());
            allowed.add(s.id());
            allowed.addAll(GUARDED.getOrDefault(s.id(), Set.of()));
            ALLOWED.put(s.id(), allowed);
        }
    }

    private static String system(String pkg) {
        if (!pkg.startsWith(ROOT)) {
            return null;
        }
        String[] parts = pkg.substring(ROOT.length()).split("\\.");
        String id = parts[0].equals("mixin") && parts.length > 1 ? parts[1] : parts[0];
        return ALLOWED.containsKey(id) ? id : null;
    }

    @ArchTest
    static final ArchRule systemsUseOnlyRequiredSystems = classes()
            .that(resideInAPackage("com.wf.combinedassault..")).and(not(resideInAPackage("..gametest..")))
            .should(new ArchCondition<JavaClass>("depend only on their own system, the systems it requires, mandatory ones and guarded edges") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    String own = system(c.getPackageName());
                    if (own == null) {
                        return;
                    }
                    for (Dependency d : c.getDirectDependenciesFromSelf()) {
                        String target = system(d.getTargetClass().getPackageName());
                        if (target != null && !ALLOWED.get(own).contains(target)) {
                            events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                        }
                    }
                }
            });
}
