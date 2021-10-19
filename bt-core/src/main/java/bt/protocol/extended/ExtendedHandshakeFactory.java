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

package bt.protocol.extended;

import bt.bencoding.types.BEInteger;
import bt.bencoding.types.BEString;
import bt.event.EventSource;
import bt.metainfo.TorrentId;
import bt.protocol.IExtendedHandshakeFactory;
import bt.protocol.crypto.EncryptionPolicy;
import bt.runtime.Config;
import bt.service.ApplicationService;
import bt.torrent.TorrentRegistry;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static bt.protocol.extended.ExtendedHandshake.*;

/**
 *<p><b>Note that this class implements a service.
 * Hence, is not a part of the public API and is a subject to change.</b></p>
 */
public class ExtendedHandshakeFactory implements IExtendedHandshakeFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtendedHandshakeFactory.class);

    private static final String UT_METADATA_SIZE_PROPERTY = "metadata_size";

    private static final String VERSION_TEMPLATE = "Bt %s";

    private final TorrentRegistry torrentRegistry;
    private final ExtendedMessageTypeMapping messageTypeMapping;
    private final ApplicationService applicationService;
    private final EncryptionPolicy encryptionPolicy;
    private final int tcpAcceptorPort;

    private final ConcurrentMap<TorrentId, ExtendedHandshake> extendedHandshakes;

    @Inject
    public ExtendedHandshakeFactory(TorrentRegistry torrentRegistry,
                                    ExtendedMessageTypeMapping messageTypeMapping,
                                    ApplicationService applicationService,
                                    Config config,
                                    EventSource eventSource) {
        this.torrentRegistry = torrentRegistry;
        this.messageTypeMapping = messageTypeMapping;
        this.applicationService = applicationService;
        this.encryptionPolicy = config.getEncryptionPolicy();
        this.tcpAcceptorPort = config.getAcceptorPort();
        this.extendedHandshakes = new ConcurrentHashMap<>();

        // don't leak memory if a torrent is stopped.
        eventSource.onTorrentStopped(null, torrentStoppedEvent -> extendedHandshakes.remove(torrentStoppedEvent.getTorrentId()));
    }

    @Override
    public ExtendedHandshake getHandshake(TorrentId torrentId) {
        ExtendedHandshake handshake = extendedHandshakes.computeIfAbsent(torrentId, this::buildHandshake);
        return handshake;
    }

    private ExtendedHandshake buildHandshake(TorrentId torrentId) {
        ExtendedHandshake.Builder builder = ExtendedHandshake.builder();

        switch (encryptionPolicy) {
            case REQUIRE_PLAINTEXT:
            case PREFER_PLAINTEXT: {
                builder.property(ENCRYPTION_PROPERTY, new BEInteger(0));
            }
            case PREFER_ENCRYPTED:
            case REQUIRE_ENCRYPTED: {
                builder.property(ENCRYPTION_PROPERTY, new BEInteger(1));
            }
            default: {
                // do nothing
            }
        }

        builder.property(TCPPORT_PROPERTY, new BEInteger(tcpAcceptorPort));

        try {
            torrentRegistry.getTorrent(torrentId).ifPresent(torrent -> {
                int metadataSize = torrent.getSource().getExchangedMetadata().length;
                builder.property(UT_METADATA_SIZE_PROPERTY, new BEInteger(metadataSize));
            });
        } catch (Exception e) {
            LOGGER.error("Failed to get metadata size for torrent ID: " + torrentId, e);
        }

        String version;
        try {
            version = getVersion();
        } catch (Exception e) {
            LOGGER.error("Failed to get version", e);
            version = getDefaultVersion();
        }
        builder.property(VERSION_PROPERTY, new BEString(version.getBytes(StandardCharsets.UTF_8)));

        messageTypeMapping.visitMappings(builder::addMessageType);
        return builder.build();
    }

    protected String getVersion() {
        return String.format(VERSION_TEMPLATE, applicationService.getVersion());
    }

    private String getDefaultVersion() {
        return String.format(VERSION_TEMPLATE, "(unknown version)");
    }
}
