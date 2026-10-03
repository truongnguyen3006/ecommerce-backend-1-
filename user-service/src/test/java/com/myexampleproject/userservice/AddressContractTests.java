package com.myexampleproject.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.userservice.dto.UserAddressResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AddressContractTests {
    @Test void defaultAddressFlagUsesTheFrontendContractName() throws Exception {
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
            UserAddressResponse.builder().id(1L).isDefault(true).build()));
        assertThat(json.path("isDefault").asBoolean()).isTrue();
        assertThat(json.has("default")).isFalse();
    }
}
