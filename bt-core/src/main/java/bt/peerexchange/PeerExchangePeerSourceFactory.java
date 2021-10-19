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

package bt.peerexchange;

import bt.BtException;
import bt.event.EventSource;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.Peer;
import bt.peer.ImmutablePeer;
import bt.peer.PeerSource;
import bt.peer.PeerSourceFactory;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.runtime.Config;
import bt.service.IRuntimeLifecycleBinder;
import bt.torrent.annotation.Consumes;
import bt.torrent.annotation.Produces;
import bt.torrent.messaging.MessageContext;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * <p><b>Note that this class implements a service.
 * Hence, is not a part of the public API and is a subject to change.</b></p>
 */
public class PeerExchangePeerSourceFactory implements PeerSourceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerExchangePeerSourceFactory.class);

    private static final Duration MAX_PEER_EVENT_STORAGE = Duration.ofMinutes(10);
    private static final Duration CLEANER_INTERVAL = Duration.ofSeconds(37);

    private final Map<TorrentId, PeerExchangePeerSource> peerSources;

    private final Map<TorrentId, Queue<PeerEvent>> peerEvents;
    private final ReentrantReadWriteLock rwLock;

    private final Set<ConnectionKey> peers;
    private final Cache<ConnectionKey, Long> lastSentPEXMessage;

    private final Duration minMessageInterval;
    private final Duration maxMessageInterval;
    private final int minEventsPerMessage;
    private final int maxEventsPerMessage;

    @Inject
    public PeerExchangePeerSourceFactory(EventSource eventSource,
                                         IRuntimeLifecycleBinder lifecycleBinder,
                                         PeerExchangeConfig pexConfig,
                                         Config config) {
        this.peerSources = new ConcurrentHashMap<>();
        this.peerEvents = new ConcurrentHashMap<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.peers = ConcurrentHashMap.newKeySet();
        this.lastSentPEXMessage = CacheBuilder.newBuilder().expireAfterAccess(MAX_PEER_EVENT_STORAGE).build();
        if (pexConfig.getMaxMessageInterval().compareTo(pexConfig.getMinMessageInterval()) < 0) {
            throw new IllegalArgumentException("Max message interval is greater than min interval");
        }
        this.minMessageInterval = pexConfig.getMinMessageInterval();
        this.maxMessageInterval = pexConfig.getMaxMessageInterval();
        this.minEventsPerMessage = pexConfig.getMinEventsPerMessage();
        this.maxEventsPerMessage = pexConfig.getMaxEventsPerMessage();

        eventSource.onPeerConnected(null, e -> onPeerConnected(e.getConnectionKey()));
        eventSource.onPeerDisconnected(null, e -> onPeerDisconnected(e.getConnectionKey()));
        eventSource.onTorrentStopped(null, e -> cleanupTorrent(e.getTorrentId()));

        String threadName = String.format("%d.bt.peerexchange.cleaner", config.getAcceptorPort());
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, threadName));
        lifecycleBinder.onStartup("Schedule periodic cleanup of PEX messages", () -> executor.scheduleAtFixedRate(
                new Cleaner(), CLEANER_INTERVAL.toMillis(), CLEANER_INTERVAL.toMillis(), TimeUnit.MILLISECONDS));
        lifecycleBinder.onShutdown("Shutdown PEX cleanup scheduler", executor::shutdownNow);
    }

    private void onPeerConnected(ConnectionKey connectionKey) {
        //TODO: add code that adds this peer once its port becomes known from the extended handshake
        if (!connectionKey.getPeer().isPortUnknown()) {
            ImmutablePeer immutablePeer = ImmutablePeer.build(connectionKey.getPeer().getInetAddress(),
                    connectionKey.getPeer().getPort());
            getPeerEvents(connectionKey.getTorrentId()).add(PeerEvent.added(immutablePeer));
        }
    }

    private void onPeerDisconnected(ConnectionKey connectionKey) {
        if (!connectionKey.getPeer().isPortUnknown()) {
            ImmutablePeer immutablePeer = ImmutablePeer.build(connectionKey.getPeer().getInetAddress(),
                    connectionKey.getPeer().getPort());
            getPeerEvents(connectionKey.getTorrentId()).add(PeerEvent.dropped(immutablePeer));
        }
        peers.remove(connectionKey);
        lastSentPEXMessage.invalidate(connectionKey);
    }

    private void cleanupTorrent(TorrentId tid) {
        this.peerSources.remove(tid);
        this.peerEvents.remove(tid);
    }

    private Queue<PeerEvent> getPeerEvents(TorrentId torrentId) {
        Queue<PeerEvent> events = peerEvents.get(torrentId);
        if (events == null) {
            events = new PriorityBlockingQueue<>();
            Queue<PeerEvent> existing = peerEvents.putIfAbsent(torrentId, events);
            if (existing != null) {
                events = existing;
            }
        }
        return events;
    }

    @Override
    public PeerSource getPeerSource(TorrentId torrentId) {
        return getOrCreatePeerSource(torrentId);
    }

    private PeerExchangePeerSource getOrCreatePeerSource(TorrentId torrentId) {
        PeerExchangePeerSource peerSource = peerSources.get(torrentId);
        if (peerSource == null) {
            peerSource = new PeerExchangePeerSource();
            PeerExchangePeerSource existing = peerSources.putIfAbsent(torrentId, peerSource);
            if (existing != null) {
                peerSource = existing;
            }
        }
        return peerSource;
    }

    @Consumes
    public void consume(ExtendedHandshake handshake, MessageContext messageContext) {
        if (handshake.getSupportedMessageTypes().contains("ut_pex")) {
            // TODO: peer may eventually turn off the PEX extension
            // moreover the extended handshake message type map is additive,
            // so we can't learn about the peer turning off extensions solely from the message
            peers.add(messageContext.getConnectionKey());
        }
    }

    @Consumes
    public void consume(PeerExchange message, MessageContext messageContext) {
        getOrCreatePeerSource(messageContext.getTorrentId()).addMessage(message);
    }

    @Produces
    public void produce(Consumer<Message> messageConsumer, MessageContext messageContext) {
        ConnectionKey connectionKey = messageContext.getConnectionKey();
        long currentTime = System.currentTimeMillis();
        Long lastSentPEXMessageToPeer = lastSentPEXMessage.getIfPresent(connectionKey);
        if (lastSentPEXMessageToPeer == null) lastSentPEXMessageToPeer = 0L;

        if (peers.contains(connectionKey) && (currentTime - lastSentPEXMessageToPeer) >= minMessageInterval.toMillis()) {
            List<PeerEvent> events = new ArrayList<>();

            rwLock.readLock().lock();
            try {
                Queue<PeerEvent> torrentPeerEvents = getPeerEvents(messageContext.getTorrentId());
                for (PeerEvent event : torrentPeerEvents) {
                    if (event.getInstant() - lastSentPEXMessageToPeer >= 0) {
                        Peer exchangedPeer = event.getPeer();
                        // don't send PEX message if anything of the below is true:
                        // - we don't know the listening port of the current connection's peer yet
                        // - event's peer and connection's peer are the same
                        if (!connectionKey.getPeer().isPortUnknown()
                                && !exchangedPeer.getInetAddress().equals(connectionKey.getPeer().getInetAddress())
                                && exchangedPeer.getPort() != connectionKey.getRemotePort()) {
                            events.add(event);
                        }
                    } else {
                        break;
                    }
                    if (events.size() >= maxEventsPerMessage) {
                        break;
                    }
                }
            } finally {
                rwLock.readLock().unlock();
            }

            if (events.size() >= minEventsPerMessage ||
                    (!events.isEmpty() && (currentTime - lastSentPEXMessageToPeer >= maxMessageInterval.toMillis()))) {
                lastSentPEXMessage.put(connectionKey, currentTime);
                PeerExchange.Builder messageBuilder = PeerExchange.builder();
                events.forEach(event -> {
                    switch (event.getType()) {
                        case ADDED: {
                            messageBuilder.added(event.getPeer());
                            break;
                        }
                        case DROPPED: {
                            messageBuilder.dropped(event.getPeer());
                            break;
                        }
                        default: {
                            throw new BtException("Unknown event type: " + event.getType().name());
                        }
                    }
                });
                messageConsumer.accept(messageBuilder.build());
            }
        }
    }

    private class Cleaner implements Runnable {

        @Override
        public void run() {
            lastSentPEXMessage.cleanUp();
            rwLock.writeLock().lock();
            try {
                long lruEventTime = lastSentPEXMessage.asMap().values().stream()
                        .reduce(Long.MAX_VALUE, (a, b) -> (a < b) ? a : b);

                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Prior to cleaning events. LRU event time: {}, peer events: {}", lruEventTime, peerEvents);
                }

                PeerEvent event;
                for (Queue<PeerEvent> events : peerEvents.values()) {
                    while ((event = events.peek()) != null && event.getInstant() <= lruEventTime) {
                        events.poll();
                    }
                }

                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("After cleaning events. Peer events: {}", peerEvents);
                }

            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }
}
