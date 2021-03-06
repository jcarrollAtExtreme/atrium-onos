/*
 * Copyright 2016 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.store.primitives.impl;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Transport;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.storage.Storage;
import io.atomix.copycat.server.storage.StorageLevel;
import io.atomix.manager.state.ResourceManagerState;
import io.atomix.resource.ResourceRegistry;
import io.atomix.resource.ResourceType;
import io.atomix.resource.ResourceTypeResolver;
import io.atomix.resource.ServiceLoaderResourceResolver;

import java.io.File;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.onosproject.cluster.NodeId;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * {@link StoragePartition} server.
 */
public class StoragePartitionServer implements Managed<StoragePartitionServer> {

    private static final int MAX_ENTRIES_PER_LOG_SEGMENT = 32768;
    private final StoragePartition partition;
    private final Address localAddress;
    private final Supplier<Transport> transport;
    private final Serializer serializer;
    private final File dataFolder;
    private final Collection<ResourceType> resourceTypes;
    private CopycatServer server;

    public StoragePartitionServer(Address localAddress,
            StoragePartition partition,
            Serializer serializer,
            Supplier<Transport> transport,
            Collection<ResourceType> resourceTypes,
            File dataFolder) {
        this.partition = partition;
        this.localAddress = localAddress;
        this.serializer = serializer;
        this.transport = transport;
        this.resourceTypes = ImmutableSet.copyOf(resourceTypes);
        this.dataFolder = dataFolder;
    }

    @Override
    public CompletableFuture<Void> open() {
        CompletableFuture<CopycatServer> serverOpenFuture;
        if (partition.getMemberAddresses().contains(localAddress)) {
            if (server != null && server.isOpen()) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (this) {
                server = server();
            }
            serverOpenFuture = server.open();
        } else {
            serverOpenFuture = CompletableFuture.completedFuture(null);
        }
        return serverOpenFuture.thenApply(v -> null);
    }

    @Override
    public CompletableFuture<Void> close() {
        // We do not close the server because doing so is equivalent to this node
        // leaving the cluster and we don't want that here.
        // The Raft protocol should take care of servers leaving unannounced.
        return CompletableFuture.completedFuture(null);
    }

    private CopycatServer server() {
        ResourceTypeResolver resourceResolver = new ServiceLoaderResourceResolver();
        ResourceRegistry registry = new ResourceRegistry();
        resourceTypes.forEach(registry::register);
        resourceResolver.resolve(registry);
        return CopycatServer.builder(localAddress, partition.getMemberAddresses())
                .withName("partition-" + partition.getId())
                .withSerializer(serializer.clone())
                .withTransport(transport.get())
                .withStateMachine(() -> new ResourceManagerState(registry))
                .withStorage(Storage.builder()
                         // FIXME: StorageLevel should be DISK
                        .withStorageLevel(StorageLevel.MEMORY)
                        .withSerializer(serializer.clone())
                        .withDirectory(dataFolder)
                        .withMaxEntriesPerSegment(MAX_ENTRIES_PER_LOG_SEGMENT)
                        .build())
                .build();
    }

    public Set<NodeId> configuredMembers() {
        return Sets.newHashSet(partition.getMembers());
    }

    @Override
    public boolean isOpen() {
        return server.isOpen();
    }

    @Override
    public boolean isClosed() {
        return server.isClosed();
    }
}
