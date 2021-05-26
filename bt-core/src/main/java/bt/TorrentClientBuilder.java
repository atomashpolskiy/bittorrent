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

package bt;

import bt.data.Storage;
import bt.magnet.MagnetUri;
import bt.magnet.MagnetUriParser;
import bt.metainfo.IMetadataService;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.processor.ProcessingContext;
import bt.processor.ProcessingStage;
import bt.processor.listener.ListenerSource;
import bt.processor.listener.ProcessingEvent;
import bt.processor.magnet.MagnetContext;
import bt.processor.torrent.TorrentContext;
import bt.runtime.BtRuntime;
import bt.torrent.callbacks.FileDownloadCompleteCallback;
import bt.torrent.fileselector.FilePrioritySkipSelector;
import bt.torrent.fileselector.TorrentFileSelector;
import bt.torrent.selector.PieceSelector;
import bt.torrent.selector.RarestFirstSelector;
import bt.torrent.selector.SequentialSelector;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class TorrentClientBuilder<B extends TorrentClientBuilder> extends BaseClientBuilder<B> {

    private Storage storage;

    private URL torrentUrl;
    private Supplier<Torrent> torrentSupplier;
    private MagnetUri magnetUri;

    private FilePrioritySkipSelector fileSelector;
    private PieceSelector pieceSelector;

    private List<Consumer<Torrent>> torrentConsumers;
    private List<Runnable> fileSelectionListeners;
    private List<FileDownloadCompleteCallback> fileCompletionHandlers;
    private List<Consumer<Torrent>> downloadCompleteListeners;

    private boolean stopWhenDownloaded;

    /**
     * @since 1.4
     */
    protected TorrentClientBuilder() {
        // set default piece selector
        this.pieceSelector = RarestFirstSelector.randomizedRarest();
    }

    /**
     * Set the provided storage as the data back-end
     *
     * @since 1.4
     */
    @SuppressWarnings("unchecked")
    public B storage(Storage storage) {
        this.storage = Objects.requireNonNull(storage, "Missing data storage");
        return (B) this;
    }

    /**
     * Set torrent file URL
     *
     * @see #torrent(Supplier)
     * @since 1.4
     */
    @SuppressWarnings("unchecked")
    public B torrent(URL torrentUrl) {
        Objects.requireNonNull(torrentUrl, "Missing torrent file URL");
        this.torrentUrl = torrentUrl;
        this.torrentSupplier = null;
        this.magnetUri = null;
        return (B) this;
    }

    /**
     * Set custom torrent file supplier
     *
     * @see #torrent(URL)
     * @since 1.4
     */
    @SuppressWarnings("unchecked")
    public B torrent(Supplier<Torrent> torrentSupplier) {
        this.torrentUrl = null;
        this.torrentSupplier = Objects.requireNonNull(torrentSupplier, "Missing torrent supplier");
        this.magnetUri = null;
        return (B) this;
    }

    /**
     * Set magnet URI in BEP-9 format
     *
     * @param magnetUri Magnet URI
     * @see MagnetUriParser
     * @since 1.4
     */
    @SuppressWarnings("unchecked")
    public B magnet(String magnetUri) {
        this.torrentUrl = null;
        this.torrentSupplier = null;
        this.magnetUri = MagnetUriParser.lenientParser().parse(magnetUri);
        return (B) this;
    }

    /**
     * Set magnet URI
     *
     * @param magnetUri Magnet URI
     * @see MagnetUriParser
     * @since 1.4
     */
    @SuppressWarnings("unchecked")
    public B magnet(MagnetUri magnetUri) {
        this.torrentUrl = null;
        this.torrentSupplier = null;
        this.magnetUri = Objects.requireNonNull(magnetUri, "Missing magnet URI");
        return (B) this;
    }

    /**
     * Set piece selection strategy
     *
     * @since 1.4
     */
    @SuppressWarnings("unchecked")
    public B selector(PieceSelector pieceSelector) {
        this.pieceSelector = Objects.requireNonNull(pieceSelector, "Missing piece selector");
        return (B) this;
    }

    /**
     * Use sequential piece selection strategy
     *
     * @since 1.4
     */
    public B sequentialSelector() {
        return selector(SequentialSelector.sequential());
    }

    /**
     * Use rarest first piece selection strategy
     *
     * @since 1.4
     */
    public B rarestSelector() {
        return selector(RarestFirstSelector.rarest());
    }

    /**
     * Use rarest first piece selection strategy
     *
     * @since 1.4
     */
    public B randomizedRarestSelector() {
        return selector(RarestFirstSelector.randomizedRarest());
    }

    /**
     * Stop processing, when the data has been downloaded.
     *
     * @since 1.5
     */
    @SuppressWarnings("unchecked")
    public B stopWhenDownloaded() {
        this.stopWhenDownloaded = true;
        return (B) this;
    }

    /**
     * Provide a callback to invoke when torrent's metadata has been fetched.
     *
     * @param torrentConsumer Callback to invoke when torrent's metadata has been fetched
     * @since 1.5
     */
    @SuppressWarnings("unchecked")
    public B afterTorrentFetched(Consumer<Torrent> torrentConsumer) {
        if (torrentConsumers == null) {
            torrentConsumers = new ArrayList<>();
        }
        torrentConsumers.add(torrentConsumer);
        return (B) this;
    }

    /**
     * Provide a file selector for partial download of the torrent.
     *
     * @param fileSelector A file selector for partial download of the torrent.
     * @since 1.7
     */
    @SuppressWarnings("unchecked")
    public B fileSelector(FilePrioritySkipSelector fileSelector) {
        this.fileSelector = Objects.requireNonNull(fileSelector, "Missing file selector");
        return (B) this;
    }

    /**
     * Provide a callback to invoke when the files have been chosen
     *
     * @param runnable Callback to invoke when the files have been chosen
     * @since 1.7
     */
    @SuppressWarnings("unchecked")
    public B afterFilesChosen(Runnable runnable) {
        Objects.requireNonNull(runnable, "Missing callback");
        if (fileSelectionListeners == null) {
            fileSelectionListeners = new ArrayList<>();
        }
        fileSelectionListeners.add(runnable);
        return (B) this;
    }

    /**
     * Provide a callback to invoke when an individual file in the torrent has completed and verified
     *
     * @param callback The callback to invoke when an individual file in the torrent has fully downloaded
     * @since 1.10
     */
    @SuppressWarnings("unchecked")
    public B afterFileDownloaded(FileDownloadCompleteCallback callback) {
        Objects.requireNonNull(callback, "Missing callback");
        if (fileCompletionHandlers == null) {
            fileCompletionHandlers = new ArrayList<>();
        }
        fileCompletionHandlers.add(callback);
        return (B) this;
    }

    /**
     * Provide a callback to invoke when downloading has completed
     *
     * @param runnable Callback to invoke when downloading has completed
     * @since 1.10
     */
    @SuppressWarnings("unchecked")
    public B afterDownloaded(Consumer<Torrent> runnable) {
        Objects.requireNonNull(runnable, "Missing callback");
        if (downloadCompleteListeners == null) {
            downloadCompleteListeners = new ArrayList<>();
        }
        downloadCompleteListeners.add(runnable);
        return (B) this;
    }

    @Override
    protected ProcessingContext buildProcessingContext(BtRuntime runtime) {
        Objects.requireNonNull(storage, "Missing data storage");

        ProcessingContext context;
        if (torrentUrl != null) {
            context = new TorrentContext(pieceSelector, fileSelector, getCompletionCallback(),
                    storage, () -> fetchTorrentFromUrl(runtime, torrentUrl));
        } else if (torrentSupplier != null) {
            context = new TorrentContext(pieceSelector, fileSelector, getCompletionCallback(), storage, torrentSupplier);
        } else if (this.magnetUri != null) {
            context = new MagnetContext(magnetUri, pieceSelector, fileSelector, getCompletionCallback(), storage);
        } else {
            throw new IllegalStateException("Missing torrent supplier, torrent URL or magnet URI");
        }

        return context;
    }

    private FileDownloadCompleteCallback getCompletionCallback() {
        if (fileCompletionHandlers == null || fileCompletionHandlers.isEmpty()) {
            return null;
        }

        final List<FileDownloadCompleteCallback> callbacks = Collections.unmodifiableList(new ArrayList<>(fileCompletionHandlers));
        return (torrent, tf, s) -> {
            for (FileDownloadCompleteCallback callback : callbacks) {
                callback.fileDownloadCompleted(torrent, tf, s);
            }
        };
    }

    @Override
    protected <C extends ProcessingContext> void collectStageListeners(ListenerSource<C> listenerSource) {
        if (torrentConsumers != null && !torrentConsumers.isEmpty()) {
            BiFunction<C, ProcessingStage<C>, ProcessingStage<C>> listener = (context, next) -> {
                context.getTorrent().ifPresent(torrent -> {
                    for (Consumer<Torrent> torrentConsumer : torrentConsumers) {
                        torrentConsumer.accept(torrent);
                    }
                });
                return next;
            };
            listenerSource.addListener(ProcessingEvent.TORRENT_FETCHED, listener);
        }

        if (fileSelectionListeners != null && !fileSelectionListeners.isEmpty()) {
            BiFunction<C, ProcessingStage<C>, ProcessingStage<C>> listener = (context, next) -> {
                fileSelectionListeners.forEach(Runnable::run);
                return next;
            };
            listenerSource.addListener(ProcessingEvent.FILES_CHOSEN, listener);
        }

        // flush to disk when download completes so external applications can read the downloaded files
        listenerSource.addListener(ProcessingEvent.DOWNLOAD_COMPLETE, (context, next) -> {
            context.getStorage().flush();
            return next;
        });

        if (downloadCompleteListeners != null && !downloadCompleteListeners.isEmpty()) {
            BiFunction<C, ProcessingStage<C>, ProcessingStage<C>> listener = (context, next) -> {
                downloadCompleteListeners.forEach(handler -> handler.accept(context.getTorrent().get()));
                return next;
            };
            listenerSource.addListener(ProcessingEvent.DOWNLOAD_COMPLETE, listener);
        }

        if (stopWhenDownloaded) {
            listenerSource.addListener(ProcessingEvent.DOWNLOAD_COMPLETE, (context, next) -> null);
        }
    }

    private Torrent fetchTorrentFromUrl(BtRuntime runtime, URL metainfoUrl) {
        return runtime.service(IMetadataService.class).fromUrl(metainfoUrl);
    }
}
