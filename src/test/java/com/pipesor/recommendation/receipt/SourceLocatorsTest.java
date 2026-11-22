package com.pipesor.recommendation.receipt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceLocatorsTest {
    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private static String locator(String member, String lines) {
        return "v2:job:12345678901234567890:member:" + member + ":lines:" + lines + ":sha256:" + DIGEST;
    }

    @Test
    void acceptsTheGeneralizedGrammarIncludingEvery21Locator() {
        assertThat(SourceLocators.valid(locator("0_verify.txt", "7-7"))).isTrue();
        assertThat(SourceLocators.valid(locator("build%2F2_Set%20up%20job.txt", "1-200"))).isTrue();
        assertThat(SourceLocators.valid(locator("caf%C3%A9.log", "5-9"))).isTrue();
    }

    @Test
    void rejectsDecodedRuleViolationsAndShapeErrors() {
        assertThat(SourceLocators.valid(locator("x.txt", "1-201"))).isFalse();
        assertThat(SourceLocators.valid(locator("x.txt", "9-8"))).isFalse();
        assertThat(SourceLocators.valid(locator("%2Fetc.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("a%2Fb%2Fc.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("..%2Fx.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("a%5Cb.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("a%0Ab.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("%FF.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("a%2fb.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid(locator("a b.txt", "1-1"))).isFalse();
        assertThat(SourceLocators.valid("v2:job:0:member:x:lines:1-1:sha256:" + DIGEST)).isFalse();
        assertThat(SourceLocators.valid(locator("x.txt", "1-1").toUpperCase())).isFalse();
    }
}
