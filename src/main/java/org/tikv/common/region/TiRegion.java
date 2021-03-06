/*
 *
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.tikv.common.region;

import com.google.protobuf.ByteString;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import org.tikv.common.codec.Codec.BytesCodec;
import org.tikv.common.codec.CodecDataInput;
import org.tikv.common.codec.KeyUtils;
import org.tikv.common.exception.TiClientInternalException;
import org.tikv.common.util.FastByteComparisons;
import org.tikv.kvproto.Kvrpcpb;
import org.tikv.kvproto.Kvrpcpb.IsolationLevel;
import org.tikv.kvproto.Metapb;
import org.tikv.kvproto.Metapb.Peer;
import org.tikv.kvproto.Metapb.Region;

public class TiRegion implements Serializable {
  private final Region meta;
  private final Peer peer;
  private final IsolationLevel isolationLevel;
  private final Kvrpcpb.CommandPri commandPri;
  private Kvrpcpb.Context cachedContext;

  public TiRegion(
      Region meta,
      Peer peer,
      IsolationLevel isolationLevel,
      Kvrpcpb.CommandPri commandPri,
      String kvMode) {
    Objects.requireNonNull(meta, "meta is null");
    this.meta = decodeRegion(meta, kvMode.equalsIgnoreCase("RAW"));
    if (peer == null || peer.getId() == 0) {
      if (meta.getPeersCount() == 0) {
        throw new TiClientInternalException("Empty peer list for region " + meta.getId());
      }
      this.peer = meta.getPeers(0);
    } else {
      this.peer = peer;
    }
    this.isolationLevel = isolationLevel;
    this.commandPri = commandPri;
  }

  private TiRegion(
      Region meta, Peer peer, IsolationLevel isolationLevel, Kvrpcpb.CommandPri commandPri) {
    this.meta = meta;
    this.peer = peer;
    this.isolationLevel = isolationLevel;
    this.commandPri = commandPri;
  }

  private TiRegion withNewLeader(Peer p) {
    return new TiRegion(this.meta, p, this.isolationLevel, this.commandPri);
  }

  private Region decodeRegion(Region region, boolean isRawRegion) {
    Region.Builder builder =
        Region.newBuilder()
            .setId(region.getId())
            .setRegionEpoch(region.getRegionEpoch())
            .addAllPeers(region.getPeersList());

    if (region.getStartKey().isEmpty() || isRawRegion) {
      builder.setStartKey(region.getStartKey());
    } else {
      byte[] decodecStartKey = BytesCodec.readBytes(new CodecDataInput(region.getStartKey()));
      builder.setStartKey(ByteString.copyFrom(decodecStartKey));
    }

    if (region.getEndKey().isEmpty() || isRawRegion) {
      builder.setEndKey(region.getEndKey());
    } else {
      byte[] decodecEndKey = BytesCodec.readBytes(new CodecDataInput(region.getEndKey()));
      builder.setEndKey(ByteString.copyFrom(decodecEndKey));
    }

    return builder.build();
  }

  public Peer getLeader() {
    return peer;
  }

  public long getId() {
    return this.meta.getId();
  }

  public ByteString getStartKey() {
    return meta.getStartKey();
  }

  public ByteString getEndKey() {
    return meta.getEndKey();
  }

  public Kvrpcpb.Context getContext() {
    if (cachedContext == null) {
      Kvrpcpb.Context.Builder builder = Kvrpcpb.Context.newBuilder();
      builder.setIsolationLevel(this.isolationLevel);
      builder.setPriority(this.commandPri);
      builder
          .setRegionId(this.meta.getId())
          .setPeer(this.peer)
          .setRegionEpoch(this.meta.getRegionEpoch());
      cachedContext = builder.build();
      return cachedContext;
    }
    return cachedContext;
  }

  public class RegionVerID {
    public final long id;
    public final long confVer;
    public final long ver;

    public RegionVerID(long id, long confVer, long ver) {
      this.id = id;
      this.confVer = confVer;
      this.ver = ver;
    }
  }

  // getVerID returns the Region's RegionVerID.
  public RegionVerID getVerID() {
    return new RegionVerID(
        meta.getId(), meta.getRegionEpoch().getConfVer(), meta.getRegionEpoch().getVersion());
  }

  /**
   * switches current peer to the one on specific store. It return false if no peer matches the
   * storeID.
   *
   * @param leaderStoreID is leader peer id.
   * @return a copy of current region with new leader store id
   */
  public TiRegion withNewLeader(long leaderStoreID) {
    List<Peer> peers = meta.getPeersList();
    for (Peer p : peers) {
      if (p.getStoreId() == leaderStoreID) {
        return withNewLeader(p);
      }
    }
    return this;
  }

  public boolean contains(ByteString key) {
    return (FastByteComparisons.compareTo(
                meta.getStartKey().toByteArray(),
                0,
                meta.getStartKey().size(),
                key.toByteArray(),
                0,
                key.size())
            <= 0)
        && (meta.getEndKey().isEmpty()
            || FastByteComparisons.compareTo(
                    meta.getEndKey().toByteArray(),
                    0,
                    meta.getEndKey().size(),
                    key.toByteArray(),
                    0,
                    key.size())
                > 0);
  }

  public boolean isValid() {
    return peer != null && meta != null;
  }

  public Metapb.RegionEpoch getRegionEpoch() {
    return this.meta.getRegionEpoch();
  }

  public Region getMeta() {
    return meta;
  }

  public String toString() {
    return String.format(
        "{Region[%d] ConfVer[%d] Version[%d] Store[%d] KeyRange[%s]:[%s]}",
        getId(),
        getRegionEpoch().getConfVer(),
        getRegionEpoch().getVersion(),
        getLeader().getStoreId(),
        KeyUtils.formatBytes(getStartKey()),
        KeyUtils.formatBytes(getEndKey()));
  }
}
