/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.mergers;

import static com.android.tools.build.bundletool.mergers.MergingUtils.getSameValueOrNonNull;

import com.android.aapt.Resources.ResourceTable;
import com.android.bundle.Files.NativeLibraries;
import com.android.bundle.Targeting.ApkTargeting;
import com.android.tools.build.bundletool.manifest.AndroidManifest;
import com.android.tools.build.bundletool.model.BundleModuleName;
import com.android.tools.build.bundletool.model.ModuleEntry;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimaps;

/** Merges module splits together that have the same targeting. */
public class SameTargetingMerger implements ModuleSplitMerger {

  @Override
  public ImmutableList<ModuleSplit> merge(ImmutableCollection<ModuleSplit> moduleSplits) {
    ImmutableList.Builder<ModuleSplit> result = ImmutableList.builder();
    ImmutableListMultimap<ApkTargeting, ModuleSplit> splitsByTargeting =
        Multimaps.index(moduleSplits, ModuleSplit::getTargeting);
    for (ApkTargeting targeting : splitsByTargeting.keySet()) {
      result.add(mergeSplits(splitsByTargeting.get(targeting)));
    }
    return result.build();
  }

  private ModuleSplit mergeSplits(ImmutableCollection<ModuleSplit> splits) {
    ModuleSplit.Builder builder = ModuleSplit.builder();
    ImmutableList.Builder<ModuleEntry> entries = ImmutableList.builder();
    AndroidManifest mergedManifest = null;
    ResourceTable mergedResourceTable = null;
    NativeLibraries mergedNativeConfig = null;
    BundleModuleName mergedModuleName = null;
    Boolean mergedIsMasterSplit = null;

    for (ModuleSplit split : splits) {
      if (split.getAndroidManifest().isPresent()) {
        mergedManifest =
            getSameValueOrNonNull(mergedManifest, split.getAndroidManifest().get())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Encountered two distinct manifests while merging."));
      }
      if (split.getResourceTable().isPresent()) {
        mergedResourceTable =
            getSameValueOrNonNull(mergedResourceTable, split.getResourceTable().get())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Unsupported case: encountered two distinct resource tables while "
                                + "merging."));
      }
      if (split.getNativeConfig().isPresent()) {
        mergedNativeConfig =
            getSameValueOrNonNull(mergedNativeConfig, split.getNativeConfig().get())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Encountered two distinct native configs while merging."));
      }
      mergedModuleName =
          getSameValueOrNonNull(mergedModuleName, split.getModuleName())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Encountered two distinct module names while merging."));
      mergedIsMasterSplit =
          getSameValueOrNonNull(mergedIsMasterSplit, Boolean.valueOf(split.isMasterSplit()))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Encountered conflicting isMasterSplit flag values while merging."));
      entries.addAll(split.getEntries());
      builder.setTargeting(split.getTargeting());
    }

    if (mergedManifest != null) {
      builder.setAndroidManifest(mergedManifest);
    }
    if (mergedResourceTable != null) {
      builder.setResourceTable(mergedResourceTable);
    }
    if (mergedNativeConfig != null) {
      builder.setNativeConfig(mergedNativeConfig);
    }
    if (mergedModuleName != null) {
      builder.setModuleName(mergedModuleName);
    }
    if (mergedIsMasterSplit != null) {
      builder.setMasterSplit(mergedIsMasterSplit);
    }
    builder.setEntries(entries.build());
    return builder.build();
  }
}
