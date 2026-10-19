package org.octavio.paymentreconciliationsim.generator;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;

class ScenarioFactoryTest {
    private final ScenarioFactory factory = new ScenarioFactory();
    private final LocalDate date = LocalDate.of(2026, 10, 1);

    @Test
    void canonicalHasOneHundredReferencesAcrossFiveOutcomesAndPreservesEvidence() {
        var scenario = assertDoesNotThrow(() -> factory.generate(42, date, ScenarioKind.CANONICAL));
        assertEquals(80, scenario.purchases().size());
        assertEquals(100, scenario.settlement().size());
        assertEquals(List.of("MATCH-001", "MISS-001", "AMOUNT-001", "DUP-001"), scenario.purchases().subList(0, 4).stream().map(Purchase::transactionReference).toList());
        assertEquals(List.of(10000L, 30000L, 45000L, 50000L, 50000L), scenario.settlement().subList(0, 5).stream().map(SettlementRow::amountCentavos).toList());
        var expected = factory.expected(scenario, ScenarioKind.CANONICAL);
        assertEquals(new Summary(80, 100, 80, 100, Map.of("MATCHED", 20, "MISSING_IN_SETTLEMENT", 20, "MISSING_INTERNALLY", 20, "AMOUNT_MISMATCH", 20, "DUPLICATE", 20)), expected.summary());
        assertEquals(100, expected.results().stream().map(Result::reference).distinct().count());
        assertEquals(new Result("DUP-020", "DUPLICATE", 240000L, "MERCHANT-001", List.of(new SettlementRow(99, "DUP-020", 240000), new SettlementRow(100, "DUP-020", 240000))), expected.results().stream().filter(r -> r.reference().equals("DUP-020")).findFirst().orElseThrow());
        assertNull(expected.results().stream().filter(r -> r.reference().equals("EXT-020")).findFirst().orElseThrow().internalAmountCentavos());
        assertEquals(List.of(), expected.results().stream().filter(r -> r.reference().equals("MISS-020")).findFirst().orElseThrow().settlementEvidence());
        assertEquals(scenario, factory.generate(0, date, ScenarioKind.CANONICAL));
    }

    @Test
    void seededAllMatchedIsRepeatableAndNamespaced() {
        var a = assertDoesNotThrow(() -> factory.generate(Long.MIN_VALUE, date, ScenarioKind.ALL_MATCHED));
        assertEquals(a, factory.generate(Long.MIN_VALUE, date, ScenarioKind.ALL_MATCHED));
        assertNotEquals(a, factory.generate(1, date, ScenarioKind.ALL_MATCHED));
        assertNotEquals(a.purchases().getFirst().transactionReference(), factory.generate(Long.MIN_VALUE, date.plusDays(1), ScenarioKind.ALL_MATCHED).purchases().getFirst().transactionReference());
        assertEquals(100, a.purchases().size());
        var report = factory.expected(a, ScenarioKind.ALL_MATCHED);
        assertEquals(100, report.summary().outcomeCounts().get("MATCHED"));
        for (int i = 0; i < 100; i++) {
            var p = a.purchases().get(i);
            assertTrue(p.transactionReference().matches("[A-Za-z0-9_-]{1,64}"));
            assertTrue(p.amountCentavos() > 0);
            assertEquals(new Result(p.transactionReference(), "MATCHED", p.amountCentavos(), p.merchantId(), List.of(new SettlementRow(i + 1, p.transactionReference(), p.amountCentavos()))), report.results().get(i));
        }
    }

    @Test
    void csvAndChecksumAreExactAndRepeatable() {
        var writer = new SettlementWriter();
        var scenario = assertDoesNotThrow(() -> factory.generate(42, date, ScenarioKind.CANONICAL));
        var bytes = writer.write(scenario);
        var fixture = assertDoesNotThrow(() -> java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../acceptance-tests/src/test/resources/fixtures/canonical.csv")));
        assertArrayEquals(fixture, bytes);
        assertArrayEquals(bytes, writer.write(assertDoesNotThrow(() -> factory.generate(42, date, ScenarioKind.CANONICAL))));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", writer.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verificationIgnoresResultAndEvidenceOrderingButDetectsBusinessChanges() {
        var report = factory.expected(assertDoesNotThrow(() -> factory.generate(42, date, ScenarioKind.CANONICAL)), ScenarioKind.CANONICAL);
        var reordered = new ArrayList<Result>();
        for (var r : report.results()) {
            var evidence = new ArrayList<>(r.settlementEvidence());
            Collections.reverse(evidence);
            reordered.add(new Result(r.reference(), r.outcome(), r.internalAmountCentavos(), r.merchantId(), evidence));
        }
        Collections.reverse(reordered);
        var verifier = new ResultVerifier();
        assertDoesNotThrow(() -> verifier.verify(report, new ExpectedReport(report.summary(), reordered)));
        assertThrows(IllegalStateException.class, () -> verifier.verify(report, new ExpectedReport(report.summary(), List.of())));
        assertThrows(IllegalStateException.class, () -> verifier.verify(report, new ExpectedReport(new Summary(0, 0, 0, 0, Map.of()), report.results())));
    }

    @Test
    void fixtureAndDigestProviderFailuresAreVisible() {
        var broken = new ScenarioFactory(() -> new java.io.ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            int closes;

            @Override
            public void close() throws java.io.IOException {
                if (++closes > 1) throw new java.io.IOException("fixture close");
            }
        });
        assertThrows(java.io.UncheckedIOException.class, () -> broken.expected(new Scenario(date, List.of(), List.of()), ScenarioKind.CANONICAL));
        try (var mocked = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
            mocked.when(() -> java.security.MessageDigest.getInstance("SHA-256")).thenThrow(new java.security.NoSuchAlgorithmException());
            assertThrows(IllegalStateException.class, () -> new SettlementWriter().sha256(new byte[0]));
        }
    }
}
