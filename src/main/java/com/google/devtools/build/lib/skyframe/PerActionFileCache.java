// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.cache.Metadata;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Cache provided by an {@link ActionExecutionFunction}, allowing Blaze to obtain artifact metadata
 * from the graph.
 *
 * <p>Data for the action's inputs is injected into this cache on construction, using the graph as
 * the source of truth.
 */
class PerActionFileCache implements ActionInputFileCache {
  private final Map<Artifact, FileArtifactValue> inputArtifactData;
  private final boolean missingArtifactsAllowed;
  // null until first call to getInputFromDigest()
  private volatile HashMap<ByteString, Artifact> reverseMap;

  /**
   * @param inputArtifactData Map from artifact to metadata, used to return metadata upon request.
   * @param missingArtifactsAllowed whether to tolerate missing artifacts: can happen during input
   *     discovery.
   */
  PerActionFileCache(
      Map<Artifact, FileArtifactValue> inputArtifactData, boolean missingArtifactsAllowed) {
    this.inputArtifactData = Preconditions.checkNotNull(inputArtifactData);
    this.missingArtifactsAllowed = missingArtifactsAllowed;
  }

  @Nullable
  @Override
  public Metadata getMetadata(ActionInput input) {
    if (!(input instanceof Artifact)) {
      return null;
    }
    Metadata result = inputArtifactData.get(input);
    Preconditions.checkState(missingArtifactsAllowed || result != null, "null for %s", input);
    return result;
  }

  @Override
  public Path getInputPath(ActionInput input) {
    return ((Artifact) input).getPath();
  }

  @Override
  public boolean contentsAvailableLocally(ByteString digest) {
    return getInputFromDigest(digest) != null;
  }

  @Nullable
  @Override
  public Artifact getInputFromDigest(ByteString digest) {
    HashMap<ByteString, Artifact> r = reverseMap;  // volatile load
    if (r == null) {
      r = buildReverseMap();
    }
    return r.get(digest);
  }

  private synchronized HashMap<ByteString, Artifact> buildReverseMap() {
    HashMap<ByteString, Artifact> r = reverseMap;  // volatile load
    if (r != null) {
      return r;
    }
    r = new HashMap<>(inputArtifactData.size());
    // It would be nice to have a view of the entries which treats them as a map keyed on digest but
    // does not require constructing another wrapper object for each entry.  Java doesn't come with
    // any collections which can do this, but cloning RegularImmutableSet and adding the necessary
    // features wouldn't be too bad.
    for (Map.Entry<Artifact, FileArtifactValue> e : inputArtifactData.entrySet()) {
      byte[] bytes = e.getValue().getDigest();
      if (bytes != null) {
        ByteString digest = ByteString.copyFrom(BaseEncoding.base16().lowerCase().encode(bytes)
            .getBytes(StandardCharsets.US_ASCII));
        r.put(digest, e.getKey());
      }
    }
    reverseMap = r;  // volatile store
    return r;
  }
}
