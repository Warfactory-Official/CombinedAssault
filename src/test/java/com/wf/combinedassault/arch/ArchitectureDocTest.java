package com.wf.combinedassault.arch;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.CombinedAssaultSystem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArchitectureDocTest {
    private static final Path DOC = Path.of(System.getProperty("combinedassault.architectureDoc"));

    @Test
    void systemsTableMatchesSystems() throws IOException {
        assumeTrue(Files.exists(DOC), DOC + " absent");
        String doc = Files.readString(DOC);
        int from = doc.indexOf("<!-- systems:begin -->");
        int to = doc.indexOf("<!-- systems:end -->");
        List<String> table = doc.substring(from, to).lines().filter(l -> l.startsWith("| ") && !l.startsWith("| system "))
                .toList();
        List<String> expected = new ArrayList<>();
        for (CombinedAssaultSystem s : CombinedAssault.SYSTEMS) {
            String kind = s.mandatory() ? "mandatory" : s.defaultEnabled() ? "switchable" : "switchable, default off";
            String requires = s.requires().isEmpty() ? "-" : String.join(", ", new TreeSet<>(s.requires()));
            expected.add("| " + s.id() + " | " + kind + " | " + requires + " |");
        }
        assertEquals(expected, table);
    }
}
