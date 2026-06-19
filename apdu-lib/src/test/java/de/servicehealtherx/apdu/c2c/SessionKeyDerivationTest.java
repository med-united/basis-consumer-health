package de.servicehealtherx.apdu.c2c;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class SessionKeyDerivationTest {

    @Test
    void on_card_derivation_refuses_host_side_key_derivation() {
        assertThrows(UnsupportedOperationException.class,
                () -> SessionKeyDerivation.ON_CARD.derive(List.of()));
    }
}
