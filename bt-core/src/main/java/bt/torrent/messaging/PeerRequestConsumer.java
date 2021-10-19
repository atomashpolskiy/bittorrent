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

package bt.torrent.messaging;

import bt.BtException;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.protocol.InvalidMessageException;
import bt.protocol.Message;
import bt.protocol.Piece;
import bt.protocol.Request;
import bt.torrent.annotation.Consumes;
import bt.torrent.annotation.Produces;
import bt.torrent.data.BlockRead;
import bt.torrent.data.DataWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Consumes block requests, received from the remote peer, and produces blocks.
 *
 * @since 1.0
 */
public class PeerRequestConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PeerRequestConsumer.class);

    private final TorrentId torrentId;
    private final DataWorker dataWorker;

    // note: this map uses the identity hash. This is fragile and bad. This would be better suited for a connection
    // context somewhere.
    private final Map<InetPeer, Queue<BlockRead>> completedRequests;

    public PeerRequestConsumer(TorrentId torrentId, DataWorker dataWorker) {
        this.torrentId = torrentId;
        this.dataWorker = dataWorker;
        this.completedRequests = new ConcurrentHashMap<>();
    }

    @Consumes
    public void consume(Request request, MessageContext context) {
        ConnectionState connectionState = context.getConnectionState();
        if (!connectionState.isChoking()) {
            addBlockRequest(context.getPeer(), request).whenComplete((block, error) -> {
                if (error != null) {
                    LOGGER.error("Failed to perform request to read block", error);
                } else if (block.getError().isPresent()) {
                    LOGGER.error("Failed to perform request to read block", block.getError().get());
                } else if (block.isRejected()) {
                    LOGGER.warn("Failed to perform request to read block: rejected by I/O worker");
                } else {
                    getCompletedRequestsForPeer(context.getPeer()).add(block);
                }
            });
        }
    }

    private CompletableFuture<BlockRead> addBlockRequest(InetPeer peer, Request request) {
        return dataWorker.addBlockRequest(torrentId, peer, request.getPieceIndex(), request.getOffset(), request.getLength());
    }

    private Queue<BlockRead> getCompletedRequestsForPeer(InetPeer peer) {
        Queue<BlockRead> queue = completedRequests.get(peer);
        if (queue == null) {
            queue = new ConcurrentLinkedQueue<>();
            Queue<BlockRead> existing = completedRequests.putIfAbsent(peer, queue);
            if (existing != null) {
                queue = existing;
            }
        }
        return queue;
    }

    @Produces
    public void produce(Consumer<Message> messageConsumer, MessageContext context) {
        InetPeer peer = context.getPeer();
        Queue<BlockRead> queue = getCompletedRequestsForPeer(peer);
        BlockRead block;
        while ((block = queue.poll()) != null) {
            try {
                messageConsumer.accept(new Piece(block.getPieceIndex(), block.getOffset(),
                        block.getLength(), block.getReader().get()));
            } catch (InvalidMessageException e) {
                throw new BtException("Failed to send PIECE", e);
            }
        }
    }
}
