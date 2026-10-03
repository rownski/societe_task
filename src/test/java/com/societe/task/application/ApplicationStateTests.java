package com.societe.task.application;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStateTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allTransitions")
    void allowsOnlySpecifiedTransitions(ApplicationState source, ApplicationState target, boolean allowed) {
        assertThat(source.canTransitionTo(target)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(ApplicationState.class)
    void allowsEditingOnlyInCreatedOrVerified(ApplicationState state) {
        assertThat(state.canEditBody()).isEqualTo(state == ApplicationState.CREATED || state == ApplicationState.VERIFIED);
    }

    private static Stream<Arguments> allTransitions() {
        var allowed = List.of("CREATED:VERIFIED", "CREATED:DELETED", "VERIFIED:ACCEPTED",
                "VERIFIED:REJECTED", "ACCEPTED:PUBLISHED", "ACCEPTED:REJECTED");
        return Stream.of(ApplicationState.values()).flatMap(source -> Stream.of(ApplicationState.values())
                .map(target -> Arguments.of(source, target, allowed.contains(source + ":" + target))));
    }
}
