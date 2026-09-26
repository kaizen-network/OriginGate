package io.github.origingate.core.rules;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.lookup.IpInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountriesTest {
    @Test void codesAreCheckedAndNormalized() {
        assertEquals(Optional.of("CA"), Countries.normalize(" ca "));
        assertEquals(Optional.of("XK"), Countries.normalize("xk"), "Kosovo is missing from Java's list but in use");
        assertTrue(Countries.normalize("XX").isEmpty());
        assertTrue(Countries.normalize("Canada").isEmpty());
    }

    @Test void rowsWithCodesCompareCodes() {
        IpInfo info = TestSupport.info("192.0.2.1", "Japan", "jp", false, false);
        assertTrue(Countries.matchesAny(info, List.of("MX", "JP")));
        assertFalse(Countries.matchesAny(info, List.of("MX")));
        assertFalse(Countries.matchesAny(TestSupport.info("192.0.2.1", "Japan", null, false, false), List.of("JP")),
                "only codes are compared");
    }
}
