package com.wf.combinedassault.pack;

import java.nio.file.Path;
import java.util.List;

/**
 * Installed pack. {@code root}: dir, or a zip's root. {@code mod}: providing mod id for {@link Origin#JAR}, else "".
 * {@code sha256}: hex over every file under {@code combinedassault/} and every {@code .gltf}/{@code .glb}/{@code .bin}
 * under {@code assets/}, sorted by path, each {@code path \0 length bytes}.
 */
public record CombinedPack(String id, String description, List<String> requires, Path source, Path root, Origin origin,
                           String mod, String sha256) {

    /** JAR: {@code combinedassault_packs/<dir>} in a mod file; ARCHIVE: {@code packs/<name>.zip}; DIRECTORY: {@code packs/<dir>}. */
    public enum Origin {
        JAR, ARCHIVE, DIRECTORY
    }
}
