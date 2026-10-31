package com.wf.combinedassault.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.wf.combinedassault",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class WflibApiBoundaryTest {
    @ArchTest
    static final ArchRule wflibApiOnly = noClasses().should()
            .dependOnClassesThat(resideInAPackage("com.wf.wflib..").and(not(resideInAPackage("com.wf.wflib..api.."))))
            .because("dependents consume wflib api only");
}
