package com.myexampleproject.userservice;

import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.repository.*;
import com.myexampleproject.userservice.service.*;
import com.myexampleproject.userservice.dto.UserAddressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserOwnershipTests {
    UserRepository users=mock(UserRepository.class);
    UserAddressRepository addresses=mock(UserAddressRepository.class);
    KeycloakService keycloak=mock(KeycloakService.class);
    UserService service=new UserService(users,addresses,keycloak,mock(ProvisioningService.class));

    @Test void profileLookupUsesKeycloakSubject() {
        when(users.findByKeycloakId("A")).thenReturn(Optional.of(User.builder().keycloakId("A").fullName("Owner").build()));
        assertThat(service.getUserByKeycloakId("A").getKeycloakId()).isEqualTo("A");verify(users,never()).findById(anyLong());
    }
    @Test void anotherUsersAddressCannotBeChangedOrDeletedOrMadeDefault() {
        when(users.lockOwner("B")).thenReturn(Optional.of(User.builder().keycloakId("B").build()));
        when(addresses.findByIdAndUserKeycloakId(1L,"B")).thenReturn(Optional.empty());
        UserAddressRequest request=new UserAddressRequest();request.setRecipientName("B");request.setRecipientPhone("0123456789");request.setAddressLine("Address");
        assertDenied(() -> service.updateAddress("B",1L,request));
        assertDenied(() -> service.deleteAddress("B",1L));
        assertDenied(() -> service.setDefaultAddress("B",1L));
        verify(addresses,never()).save(any());verify(addresses,never()).delete(any());
    }
    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
    }
}
