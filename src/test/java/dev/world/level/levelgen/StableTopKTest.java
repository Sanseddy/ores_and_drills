package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StableTopKTest {
    private static final Comparator<Candidate> NEAREST_FIRST = Comparator
            .comparingDouble(Candidate::distance)
            .thenComparingInt(Candidate::depth);

    @Test
    void matchesThePrefixOfAFullStableSort() {
        List<Candidate> candidates = new ArrayList<>();
        Random random = new Random(0x5EEDBEEFL);
        for (int index = 0; index < 4_000; index++) {
            // A small score domain deliberately creates ties; originalIndex models stable encounter order.
            candidates.add(new Candidate(random.nextInt(128) / 8.0D, random.nextInt(6), index));
        }

        int limit = 360;
        List<Candidate> expected = new ArrayList<>(candidates);
        expected.sort(NEAREST_FIRST);
        expected = List.copyOf(expected.subList(0, limit));

        StableTopK<Candidate> selector = new StableTopK<>(limit);
        candidates.forEach(candidate -> selector.offer(candidate, candidate.distance(), candidate.depth()));

        assertEquals(expected, selector.toSortedList());
    }

    @Test
    void returnsEveryCandidateInOrderWhenBelowLimit() {
        List<Candidate> candidates = List.of(
                new Candidate(3.0D, 0, 0),
                new Candidate(1.0D, 0, 1),
                new Candidate(2.0D, 0, 2)
        );
        StableTopK<Candidate> selector = new StableTopK<>(8);
        candidates.forEach(candidate -> selector.offer(candidate, candidate.distance(), candidate.depth()));

        assertEquals(List.of(candidates.get(1), candidates.get(2), candidates.get(0)), selector.toSortedList());
    }

    private record Candidate(double distance, int depth, int originalIndex) {
    }
}
