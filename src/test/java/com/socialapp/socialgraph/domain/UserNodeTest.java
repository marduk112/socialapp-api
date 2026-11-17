package com.socialapp.socialgraph.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserNodeTest {

    @Test
    void selfFollowIsRejected() {
        assertThatThrownBy(() -> UserNode.ensureCanFollow("u1", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot follow themselves");
    }

    @Test
    void followingSomeoneElseIsAllowed() {
        assertThatCode(() -> UserNode.ensureCanFollow("u1", "u2")).doesNotThrowAnyException();
    }

    @Test
    void equalityIsByUserId() {
        var a = new UserNode("u1", "alice", null);
        var renamed = new UserNode("u1", "alice2", "x.png");
        var other = new UserNode("u2", "alice", null);

        assertThat(a).isEqualTo(renamed).hasSameHashCodeAs(renamed).isNotEqualTo(other);
        assertThat(new UserNode()).isNotEqualTo(new UserNode());
    }
}
