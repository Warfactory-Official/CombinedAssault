package com.wf.combinedassault.client.model;

/** Bone name -> pose index; -1 if absent. */
@FunctionalInterface
public interface BoneIndexProvider {

    int getIndex(String boneName);
}
