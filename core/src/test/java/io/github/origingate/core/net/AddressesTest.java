package io.github.origingate.core.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressesTest {
    private static InetAddress ip(String text) {
        return Addresses.parse(text).orElseThrow();
    }

    @Test void parsesLiteralsOnly() {
        assertTrue(Addresses.parse("192.0.2.1").isPresent());
        assertTrue(Addresses.parse("2001:db8::1").isPresent());
        assertTrue(Addresses.parse("localhost").isEmpty(), "host names are never resolved");
        assertTrue(Addresses.parse("example.com").isEmpty());
        assertTrue(Addresses.parse("256.1.1.1").isEmpty());
        assertTrue(Addresses.parse("1.2.3").isEmpty());
        assertTrue(Addresses.parse("").isEmpty());
        assertTrue(Addresses.parse(null).isEmpty());
    }

    @Test void textIsCanonical() {
        assertEquals("2001:db8:0:0:0:0:0:1", Addresses.text(ip("2001:db8::1")));
        assertEquals("192.0.2.1", Addresses.text(ip("192.0.2.1")));
        assertEquals("192.0.2.1", Addresses.text(ip("::ffff:192.0.2.1")), "IPv4-mapped addresses become IPv4");
    }

    @Test void cidrRanges() {
        AddressRange range = AddressRange.parse("203.0.113.0/24");
        assertTrue(range.contains(ip("203.0.113.0")));
        assertTrue(range.contains(ip("203.0.113.255")));
        assertFalse(range.contains(ip("203.0.114.0")));
        assertFalse(range.contains(ip("2001:db8::1")));
        assertTrue(AddressRange.parse("10.0.0.0/8").contains(ip("10.200.1.1")));
        assertTrue(AddressRange.parse("0.0.0.0/0").contains(ip("198.51.100.1")));
        assertTrue(AddressRange.parse("198.51.100.7").contains(ip("198.51.100.7")));
        assertFalse(AddressRange.parse("198.51.100.7").contains(ip("198.51.100.8")));
        assertTrue(AddressRange.parse("2001:db8::/32").contains(ip("2001:db8:ffff::1")));
        assertFalse(AddressRange.parse("2001:db8::/32").contains(ip("2001:db9::1")));
        assertTrue(AddressRange.parse("192.0.2.128/25").contains(ip("192.0.2.200")));
        assertFalse(AddressRange.parse("192.0.2.128/25").contains(ip("192.0.2.100")));
    }

    @Test void invalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("192.0.2.0/33"));
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("192.0.2.0/x"));
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("example.com/24"));
    }

    @Test void privateAddresses() {
        for (String address : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1",
                "::1", "fe80::1", "fd12:3456::1", "0.0.0.0"}) {
            assertTrue(Addresses.isPrivate(ip(address)), address);
        }
        for (String address : new String[] {"8.8.8.8", "203.0.113.1", "2001:db8::1", "100.64.0.1"}) {
            assertFalse(Addresses.isPrivate(ip(address)), address);
        }
    }
}
