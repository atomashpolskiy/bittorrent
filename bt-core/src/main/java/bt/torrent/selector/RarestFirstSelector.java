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

package bt.torrent.selector;

import bt.torrent.PieceStatistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Implements the "rarest-first" piece selection algorithm.
 * As the name implies, pieces that appear less frequently
 * and are generally less available are selected in the first place.
 *
 * There are two "flavours" of the "rarest-first" strategy: regular and randomized.

 * Regular rarest-first selects whichever pieces that are the least available
 * (strictly following the order of increasing availability).
 *
 * Randomized rarest-first selects one of the least available pieces randomly
 * (which means that it does not always select THE least available piece, but rather looks at
 * some number N of the least available pieces and then randomly picks one of them).
 *
 * @since 1.1
 **/
public class RarestFirstSelector extends BaseStreamSelector {

    private static final Comparator<Long> comparator = new PackedIntComparator();

    /**
     * Regular rarest-first selector.
     * Selects whichever pieces that are the least available
     * (strictly following the order of increasing availability).
     *
     * @since 1.1
     */
    public static RarestFirstSelector rarest() {
        return new RarestFirstSelector(false);
    }

    /**
     * Randomized rarest-first selector.
     * Selects one of the least available pieces randomly
     * (which means that it does not always select THE least available piece, but rather looks at
     * some number N of the least available pieces and then randomly picks one of them).
     *
     * @since 1.1
     */
    public static RarestFirstSelector randomizedRarest() {
        return new RarestFirstSelector(true);
    }

    private Optional<Random> random;

    private RarestFirstSelector(boolean randomized) {
        this.random = randomized ? Optional.of(new Random(System.currentTimeMillis())) : Optional.empty();
    }

    @Override
    protected PrimitiveIterator.OfInt createIterator(PieceStatistics pieceStatistics) {
        List<Long> queue = orderedQueue(pieceStatistics);
        return random.isPresent() ?
                new RandomizedIteratorOfInt(queue, random.get()) : new SequentialIteratorOfInt(queue);
    }

    // TODO: this is very inefficient when only a few pieces are needed,
    // and this for sure can be moved to PieceStatistics (that will be responsible for maintaining an up-to-date list)
    // UPDATE: giving this another thought, amortized costs of maintaining an up-to-date list (from the '# of operations' POV)
    // are in fact likely to far exceed the costs of periodically rebuilding the statistics from scratch,
    // esp. when there are many connects and disconnects, and statistics are slightly adjusted for each added or removed bitfield.
    private List<Long> orderedQueue(PieceStatistics pieceStatistics) {
        PriorityQueue<Long> rarestFirst = new PriorityQueue<>(comparator);
        int piecesTotal = pieceStatistics.getPiecesTotal();
        for (int pieceIndex = 0; pieceIndex < piecesTotal; pieceIndex++) {
            int count = pieceStatistics.getCount(pieceIndex);
            if (count > 0) {
                long zipped = zip(pieceIndex, count);
                rarestFirst.add(zipped);
            }
        }

        List<Long> result = new ArrayList<>(rarestFirst.size());
        Long l;
        while ((l = rarestFirst.poll()) != null) {
            result.add(l);
        }
        return result;
    }

    private static long zip(int pieceIndex, int count) {
        return (((long)pieceIndex) << 32) + count;
    }

    private static int getPieceIndex(long zipped) {
        return (int)(zipped >> 32);
    }

    private static int getCount(long zipped) {
        return (int) zipped;
    }

    private static class SequentialIteratorOfInt implements PrimitiveIterator.OfInt {
        private final List<Long> list;
        private int position;

        SequentialIteratorOfInt(List<Long> list) {
            this.list = list;
        }

        @Override
        public int nextInt() {
            return getPieceIndex(list.get(position++));
        }

        @Override
        public boolean hasNext() {
            return position < list.size();
        }
    }

    private static class RandomizedIteratorOfInt implements PrimitiveIterator.OfInt {
        private final List<Long> list;
        private final Random random;
        private int position;
        private int limit;

        RandomizedIteratorOfInt(List<Long> list, Random random) {
            this.list = list;
            this.random = random;
            this.limit = calculateLimit(list, 0);
        }

        /**
         * Starting with a given position, iterates over elements of the list, while each subsequent element's
         * <code>count</code> is equal to the initial element's <code>count</code>.
         *
         * Shuffles the subrange of the list, starting with the initial element at {@code position},
         * where all elements have the same <code>count</code>.
         *
         * @return index of the first element in the list with <code>count</code> different from the initial element's
         *          <code>count</code>.
         * @see #getCount(long)
         */
        private int calculateLimit(List<Long> list, int position) {
            if (position >= list.size()) {
                return position;
            }

            int limit = position + 1;
            int count = getCount(list.get(position));

            while (limit < list.size() && getCount(list.get(limit)) == count) {
                limit++;
            }
            // shuffle elements with the same "count" only,
            // because otherwise less available pieces may end up
            // being swapped with more available pieces
            // (i.e. pushed to the bottom of the queue)
            return limit;
        }

        @Override
        public int nextInt() {
            final int bound = limit - position;
            if (bound >= 2) {
                final int randomPosition = position + random.nextInt(bound);
                if (position != randomPosition) {
                    list.set(randomPosition, list.set(position, list.get(randomPosition)));
                }
            }
            int result = getPieceIndex(list.get(position++));
            if (position == limit && position < list.size()) {
                limit = calculateLimit(list, position);
            }
            return result;
        }

        @Override
        public boolean hasNext() {
            return position < list.size();
        }
    }
}
