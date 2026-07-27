package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;

import java.util.List;

/**
 * A local sensitive-value detector (AF-623). Implementations are pure (no Spring, no state) and
 * examine one sampled string value at a time. {@link #ORDERED} fixes the first-match-wins
 * evaluation order: the stricter checksum detectors run before the looser pattern ones so a card
 * number is never double-counted as a phone number, and an SSN never counts as a phone.
 */
public interface ValueDetector {

    /** Evaluation order for first-match-wins classification of a single value. */
    List<ValueDetector> ORDERED = List.of(new CreditCardDetector(), new IbanDetector(),
            new EmailDetector(), new SsnDetector(), new PhoneDetector());

    DiscoveryDetector type();

    DataClassification classification();

    /** @param value a non-null, non-blank sampled value */
    boolean matches(String value);
}
