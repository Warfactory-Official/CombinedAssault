package com.wf.combinedassault.pack.client;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import com.wf.combinedassault.CombinedAssault;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ResourceScanner {

    public static final ExecutorService JSON_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() * 2),
            new ThreadFactory() {

                private final AtomicInteger threadId = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "combinedassault-json-loader-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }

            }
    );

    private record ParsedResource<T>(ResourceLocation id, ResourceLocation source, T data, Exception error) {

        static <T> ParsedResource<T> success(ResourceLocation id, ResourceLocation source, T data) {
            return new ParsedResource<>(id, source, data, null);
        }

        static <T> ParsedResource<T> failure(ResourceLocation id, ResourceLocation source, Exception error) {
            return new ParsedResource<>(id, source, null, error);
        }

    }

    /** Diverges from vanilla scanDirectory: returns the map (not void+callback); JSON comments
     * allowed (lenient parse). */
    public static Map<ResourceLocation, JsonElement> scanDirectory(ResourceManager pResourceManager, String pName, Gson pGson) {
        return scanDirectory(pResourceManager, FileToIdConverter.json(pName), pGson);
    }

    public static Map<ResourceLocation, JsonElement> scanDirectory(ResourceManager pResourceManager, FileToIdConverter filetoidconverter, Gson pGson) {
        return scanDirectory(pResourceManager, filetoidconverter, pGson, JsonElement.class);
    }

    public static <T> Map<ResourceLocation, T> scanDirectory(ResourceManager pResourceManager,
                                                              FileToIdConverter filetoidconverter,
                                                              Gson pGson,
                                                              Class<T> dataClass) {
        Map<ResourceLocation, T> output = Maps.newHashMap();
        List<Future<ParsedResource<T>>> futures = new ArrayList<>();
        pGson.getAdapter(dataClass);
        for (Map.Entry<ResourceLocation, Resource> entry : filetoidconverter.listMatchingResources(pResourceManager).entrySet()) {
            ResourceLocation resourcelocation = entry.getKey();
            ResourceLocation id = filetoidconverter.fileToId(resourcelocation);
            futures.add(JSON_EXECUTOR.submit(() -> {
                try (Reader reader = entry.getValue().openAsReader()) {
                    return ParsedResource.success(id, resourcelocation, GsonHelper.fromJson(pGson, reader, dataClass, true));
                } catch (IllegalArgumentException | IOException | JsonParseException exception) {
                    return ParsedResource.failure(id, resourcelocation, exception);
                }
            }));
        }
        for (Future<ParsedResource<T>> future : futures) {
            try {
                ParsedResource<T> parsed = future.get();
                if (parsed.error() != null) {
                    CombinedAssault.LOGGER.error("Couldn't parse data file {} from {}", parsed.id(), parsed.source(), parsed.error());
                    continue;
                }
                T previous = output.put(parsed.id(), parsed.data());
                if (previous != null) {
                    throw new IllegalStateException("Duplicate data file ignored with ID " + parsed.id());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while parsing resource data", exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Unexpected error while parsing resource data", exception.getCause());
            }
        }
        return output;
    }

}
