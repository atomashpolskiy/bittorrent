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

package bt.net.pipeline;

import bt.BtException;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.net.buffer.ByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.Message;
import bt.protocol.handler.MessageHandler;

import java.util.Objects;

/**
 * Reads and decodes peer messages from a byte buffer.
 */
class MessageDeserializer {

    private final MessageHandler<Message> protocol;
    private final InetPeer peer;

    public MessageDeserializer(InetPeer peer, MessageHandler<Message> protocol) {
        this.peer = peer;
        this.protocol = protocol;
    }

    public Message deserialize(ByteBufferView buffer) {
        if (buffer.remaining() == 0)
            return null;

        int position = buffer.position();
        int limit = buffer.limit();

        Message message = null;
        DecodingContext context = new DecodingContext(peer);
        int consumed = protocol.decode(context, buffer);
        if (consumed > 0) {
            if (consumed > limit - position) {
                throw new BtException("Unexpected amount of bytes consumed: " + consumed);
            }
            buffer.position(position + consumed);
            message = Objects.requireNonNull(context.getMessage());
        } else {
            buffer.limit(limit);
            buffer.position(position);
        }
        return message;
    }
}
