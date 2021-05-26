/*
 * Copyright (c) 2016—2017 Andrei Tomashpolskiy and individual contributors.
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

package bt.torrent;

import bt.data.IDataDescriptorFactory;
import bt.data.Storage;
import bt.event.EventSource;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.metainfo.TorrentId;
import bt.processor.ProcessingContext;
import bt.torrent.callbacks.FileDownloadCompleteCallback;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * Simple in-memory torrent registry, that creates new descriptors upon request.
 *
 * <p><b>Note that this class implements a service.
 * Hence, is not a part of the public API and is a subject to change.</b></p>
 */
public class AdhocTorrentRegistry implements TorrentRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdhocTorrentRegistry.class);

    private IDataDescriptorFactory dataDescriptorFactory;

    private Set<TorrentId> torrentIds;
    private ConcurrentMap<TorrentId, Torrent> torrents;
    private ConcurrentMap<TorrentId, DefaultTorrentDescriptor> descriptors;

    @Inject
    public AdhocTorrentRegistry(IDataDescriptorFactory dataDescriptorFactory,
                                EventSource eventSource) {

        this.dataDescriptorFactory = dataDescriptorFactory;

        this.torrentIds = ConcurrentHashMap.newKeySet();
        this.torrents = new ConcurrentHashMap<>();
        this.descriptors = new ConcurrentHashMap<>();

        eventSource.onTorrentStopped(null, e -> unregister(e.getTorrentId()));
    }

    @Override
    public Collection<Torrent> getTorrents() {
        return Collections.unmodifiableCollection(torrents.values());
    }

    @Override
    public Collection<TorrentId> getTorrentIds() {
        return Collections.unmodifiableCollection(torrentIds);
    }

    @Override
    public Optional<Torrent> getTorrent(TorrentId torrentId) {
        Objects.requireNonNull(torrentId, "Missing torrent ID");
        return Optional.ofNullable(torrents.get(torrentId));
    }

    @Override
    public Optional<TorrentDescriptor> getDescriptor(Torrent torrent) {
        return Optional.ofNullable(descriptors.get(torrent.getTorrentId()));
    }

    @Override
    public Optional<TorrentDescriptor> getDescriptor(TorrentId torrentId) {
        Objects.requireNonNull(torrentId, "Missing torrent ID");
        return Optional.ofNullable(descriptors.get(torrentId));
    }

    @Override
    public TorrentDescriptor getOrCreateDescriptor(Torrent torrent, Storage storage) {
        return register(torrent, storage, null);
    }

    @Override
    public TorrentDescriptor register(Torrent torrent, Storage storage,
                                      FileDownloadCompleteCallback completedFileCallbacks) {
        TorrentId torrentId = torrent.getTorrentId();

        DefaultTorrentDescriptor descriptor = descriptors.get(torrentId);
        if (descriptor != null) {
            if (descriptor.getDataDescriptor() != null) {
                throw new IllegalStateException(
                        "Torrent already registered and data descriptor created: " + torrent.getTorrentId());
            }
            descriptor.setDataDescriptor(dataDescriptorFactory.createDescriptor(torrent, storage, completedFileCallbacks));

        } else {
            descriptor = new DefaultTorrentDescriptor();
            descriptor.setDataDescriptor(dataDescriptorFactory.createDescriptor(torrent, storage, completedFileCallbacks));

            DefaultTorrentDescriptor existing = descriptors.putIfAbsent(torrentId, descriptor);
            if (existing != null) {
                descriptor = existing;
            } else {
                torrentIds.add(torrentId);
            }
        }

        torrents.putIfAbsent(torrentId, torrent);
        return descriptor;
    }

    @Override
    public TorrentDescriptor register(TorrentId torrentId) {
        return getDescriptor(torrentId).orElseGet(() -> {
            DefaultTorrentDescriptor descriptor = new DefaultTorrentDescriptor();

            DefaultTorrentDescriptor existing = descriptors.putIfAbsent(torrentId, descriptor);
            if (existing != null) {
                descriptor = existing;
            } else {
                torrentIds.add(torrentId);
            }

            return descriptor;
        });
    }

    @Override
    public boolean isSupportedAndActive(TorrentId torrentId) {
        Optional<TorrentDescriptor> descriptor = getDescriptor(torrentId);
        // it's OK if descriptor is not present -- torrent might be being fetched at the time
        return getTorrentIds().contains(torrentId)
                && (!descriptor.isPresent() || descriptor.get().isActive());
    }

    public void unregister(TorrentId torrentId) {
        torrentIds.remove(torrentId);
        torrents.remove(torrentId);
        DefaultTorrentDescriptor torrentDescriptor = descriptors.remove(torrentId);
        if (torrentDescriptor != null) {
            try {
                if (torrentDescriptor.getDataDescriptor() != null) {
                    torrentDescriptor.getDataDescriptor().close();
                }
            } catch (IOException e) {
                LOGGER.error("closing DataDescriptor error, torrentId: {}", torrentId, e);
            }
        }
    }

}
