package com.myexampleproject.userservice.repository;

import com.myexampleproject.userservice.model.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    List<UserAddress> findAllByUserKeycloakIdOrderByIsDefaultDescUpdatedDateDesc(String userKeycloakId);
    Optional<UserAddress> findByIdAndUserKeycloakId(Long id, String userKeycloakId);
}
